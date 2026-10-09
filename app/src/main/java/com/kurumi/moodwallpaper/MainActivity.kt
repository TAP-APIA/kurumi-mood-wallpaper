package com.kurumi.moodwallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var pctText: TextView
    private lateinit var tierText: TextView
    private var spinnerReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pctText = findViewById(R.id.pctText)
        tierText = findViewById(R.id.tierText)
        val btn = findViewById<Button>(R.id.setWallpaperBtn)
        val spinner = findViewById<Spinner>(R.id.debugSpinner)

        val adapter = ArrayAdapter.createFromResource(
            this,
            R.array.debug_tiers,
            android.R.layout.simple_spinner_item
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(MoodPrefs.getDebugTier(this).coerceIn(0, 5), false)
        spinnerReady = true
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                if (!spinnerReady) return
                MoodPrefs.setDebugTier(this@MainActivity, position)
                updateLabels()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btn.setOnClickListener { openLiveWallpaperPicker() }
        updateLabels()
        refreshFromNetwork()
    }

    override fun onResume() {
        super.onResume()
        updateLabels()
        refreshFromNetwork()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun updateLabels() {
        val pct = MoodPrefs.getPct(this)
        val pctStr = if (pct.isNaN()) "—" else String.format("%+.2f%%", pct)
        pctText.text = getString(R.string.current_pct, pctStr)

        val effective = MoodPrefs.effectiveTier(this)
        val debug = MoodPrefs.getDebugTier(this)
        val suffix = if (debug in 1..5) " (调试强制)" else ""
        tierText.text = getString(R.string.current_tier, MoodTier.label(effective) + suffix)
    }

    private fun refreshFromNetwork() {
        if (MoodPrefs.getDebugTier(this) in 1..5) {
            updateLabels()
            return
        }
        io.execute {
            try {
                val result = IndexFetcher.fetch()
                val tier = MoodTier.fromPct(result.pct)
                MoodPrefs.saveQuote(this, result.pct, tier)
                runOnUiThread { updateLabels() }
            } catch (_: Exception) {
                runOnUiThread { updateLabels() }
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
