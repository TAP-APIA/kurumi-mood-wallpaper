package com.kurumi.moodwallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.PackageInfoCompat
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var pctText: TextView
    private lateinit var tierText: TextView
    private lateinit var sourceText: TextView
    private lateinit var versionText: TextView
    private lateinit var statusText: TextView
    private lateinit var errorText: TextView
    private lateinit var batteryText: TextView
    private lateinit var customPanel: LinearLayout
    private lateinit var customUrl: EditText
    private lateinit var customPath: EditText
    private lateinit var customMult: EditText
    private var spinnersReady = false
    private var batteryPrompted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        versionText = findViewById(R.id.versionText)
        sourceText = findViewById(R.id.sourceText)
        pctText = findViewById(R.id.pctText)
        tierText = findViewById(R.id.tierText)
        statusText = findViewById(R.id.statusText)
        errorText = findViewById(R.id.errorText)
        batteryText = findViewById(R.id.batteryText)
        customPanel = findViewById(R.id.customPanel)
        customUrl = findViewById(R.id.customUrl)
        customPath = findViewById(R.id.customPath)
        customMult = findViewById(R.id.customMult)

        versionText.text = versionString()

        findViewById<Button>(R.id.clearErrorBtn).setOnClickListener {
            MoodPrefs.clearLastError(this)
            updateLabels()
        }
        findViewById<Button>(R.id.setWallpaperBtn).setOnClickListener { openLiveWallpaperPicker() }
        findViewById<Button>(R.id.batteryRequestBtn).setOnClickListener { requestIgnoreBattery() }
        findViewById<Button>(R.id.batterySettingsBtn).setOnClickListener { openBatterySettings() }
        findViewById<Button>(R.id.customSaveBtn).setOnClickListener { saveCustomFields() }
        findViewById<Button>(R.id.customTestBtn).setOnClickListener { testCustomApi() }

        setupIndexSpinner()
        setupDebugSpinner()
        loadCustomFields()

        updateLabels()
        updateBatteryLabel()
        refreshFromNetwork()
    }

    override fun onResume() {
        super.onResume()
        updateLabels()
        updateBatteryLabel()
        if (!batteryPrompted && !isIgnoringBatteryOptimizations()) {
            batteryPrompted = true
            requestIgnoreBattery()
        }
        refreshFromNetwork()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun setupIndexSpinner() {
        val spinner = findViewById<Spinner>(R.id.indexSpinner)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, IndexSource.labels())
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(IndexSource.indexOf(MoodPrefs.getSourceId(this)), false)
        customPanel.visibility =
            if (MoodPrefs.getSourceId(this) == IndexSource.CUSTOM.id) View.VISIBLE else View.GONE
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                if (!spinnersReady) return
                val src = IndexSource.entries[position]
                MoodPrefs.setSourceId(this@MainActivity, src.id)
                customPanel.visibility =
                    if (src == IndexSource.CUSTOM) View.VISIBLE else View.GONE
                updateLabels()
                refreshFromNetwork()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupDebugSpinner() {
        val spinner = findViewById<Spinner>(R.id.debugSpinner)
        val adapter = ArrayAdapter.createFromResource(
            this, R.array.debug_tiers, android.R.layout.simple_spinner_item
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(MoodPrefs.getDebugTier(this).coerceIn(0, 5), false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                if (!spinnersReady) return
                MoodPrefs.setDebugTier(this@MainActivity, position)
                updateLabels()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        spinnersReady = true
    }

    private fun loadCustomFields() {
        customUrl.setText(MoodPrefs.getCustomUrl(this))
        customPath.setText(MoodPrefs.getCustomPath(this))
        customMult.setText(MoodPrefs.getCustomMult(this).toString())
    }

    private fun saveCustomFields() {
        val mult = customMult.text.toString().trim().toFloatOrNull() ?: 0.01f
        MoodPrefs.saveCustom(
            this,
            customUrl.text.toString(),
            customPath.text.toString(),
            mult
        )
        Toast.makeText(this, "已保存自定义设置", Toast.LENGTH_SHORT).show()
        if (MoodPrefs.getSourceId(this) == IndexSource.CUSTOM.id) {
            refreshFromNetwork()
        }
    }

    private fun testCustomApi() {
        saveCustomFields()
        Toast.makeText(this, "正在测试…", Toast.LENGTH_SHORT).show()
        io.execute {
            try {
                val result = IndexFetcher.fetchCustom(this)
                val tier = MoodTier.fromPct(result.pct)
                MoodPrefs.saveQuote(
                    this, result.pct, tier, "${result.label} (${result.source})"
                )
                runOnUiThread {
                    updateLabels()
                    Toast.makeText(
                        this,
                        String.format(
                            "解析成功: %+.2f%% → %s",
                            result.pct,
                            MoodTier.label(tier)
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                MoodPrefs.recordError(this, "自定义测试: ${e.message ?: e.javaClass.simpleName}")
                runOnUiThread {
                    updateLabels()
                    Toast.makeText(
                        this,
                        "测试失败: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun updateLabels() {
        val srcId = MoodPrefs.getSourceId(this)
        val srcName = IndexSource.fromId(srcId).label
        val last = MoodPrefs.getSourceLabel(this)
        sourceText.text = getString(
            R.string.current_source,
            if (last.isNotBlank()) "$srcName · $last" else srcName
        )

        val pct = MoodPrefs.getPct(this)
        val pctStr = if (pct.isNaN()) "—" else String.format("%+.2f%%", pct)
        pctText.text = getString(R.string.current_pct, pctStr)

        val effective = MoodPrefs.effectiveTier(this)
        val debug = MoodPrefs.getDebugTier(this)
        val suffix = if (debug in 1..5) " (调试强制)" else ""
        tierText.text = getString(R.string.current_tier, MoodTier.label(effective) + suffix)

        statusText.text = getString(
            R.string.status_fmt, MoodPrefs.getRenderStatus(this) ?: getString(R.string.none)
        )
        errorText.text = getString(
            R.string.error_fmt, MoodPrefs.getLastError(this) ?: getString(R.string.none)
        )
    }

    private fun versionString(): String = try {
        @Suppress("DEPRECATION")
        val pi = packageManager.getPackageInfo(packageName, 0)
        getString(
            R.string.version_fmt,
            pi.versionName ?: "?",
            PackageInfoCompat.getLongVersionCode(pi).toInt()
        )
    } catch (e: Exception) {
        "版本 ?"
    }

    private fun refreshFromNetwork() {
        if (MoodPrefs.getDebugTier(this) in 1..5) {
            updateLabels()
            return
        }
        if (MoodPrefs.getSourceId(this) == IndexSource.CUSTOM.id &&
            MoodPrefs.getCustomUrl(this).isBlank()
        ) {
            updateLabels()
            return
        }
        io.execute {
            try {
                val result = IndexFetcher.fetch(this)
                val tier = MoodTier.fromPct(result.pct)
                MoodPrefs.saveQuote(
                    this, result.pct, tier, "${result.label} (${result.source})"
                )
                runOnUiThread { updateLabels() }
            } catch (e: Exception) {
                MoodPrefs.recordError(this, "行情: ${e.message ?: e.javaClass.simpleName}")
                runOnUiThread { updateLabels() }
            }
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun updateBatteryLabel() {
        batteryText.text = getString(
            if (isIgnoringBatteryOptimizations()) R.string.battery_ok else R.string.battery_need
        )
    }

    private fun requestIgnoreBattery() {
        if (isIgnoringBatteryOptimizations()) {
            updateBatteryLabel()
            Toast.makeText(this, "已忽略电池优化", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            MoodPrefs.recordError(this, "电池优化请求失败: ${e.message}")
            openBatterySettings()
        }
    }

    private fun openBatterySettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )
        } catch (_: Exception) {
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            } catch (e2: Exception) {
                Toast.makeText(this, "无法打开电池设置: ${e2.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openLiveWallpaperPicker() {
        try {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(this@MainActivity, MoodWallpaperService::class.java)
                )
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (e2: Exception) {
                Toast.makeText(
                    this,
                    "无法打开壁纸选择器: ${e2.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
