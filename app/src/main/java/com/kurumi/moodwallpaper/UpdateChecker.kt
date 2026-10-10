package com.kurumi.moodwallpaper

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** GitHub releases update check + APK download. All network calls: call off the main thread. */
object UpdateChecker {
    private const val API =
        "https://api.github.com/repos/TAP-APIA/kurumi-mood-wallpaper/releases/latest"
    private const val KEY_LAST_CHECK = "update_last_check"
    const val INTERVAL_MS = 12 * 60 * 60 * 1000L

    data class Release(
        val tag: String,
        val version: String,
        val notes: String,
        val apkUrl: String?,
        val htmlUrl: String
    )

    fun shouldAutoCheck(ctx: Context): Boolean {
        val last = MoodPrefs.prefs(ctx).getLong(KEY_LAST_CHECK, 0L)
        return System.currentTimeMillis() - last >= INTERVAL_MS
    }

    fun markChecked(ctx: Context) {
        MoodPrefs.prefs(ctx).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
    }

    fun fetchLatest(): Release {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 10000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "kurumi-mood-wallpaper")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("GitHub HTTP $code")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(body)
            val tag = o.getString("tag_name")
            val assets = o.optJSONArray("assets")
            var apk: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                        apk = a.optString("browser_download_url").ifBlank { null }
                        break
                    }
                }
            }
            return Release(
                tag = tag,
                version = tag.trim().removePrefix("v").removePrefix("V"),
                notes = o.optString("body", ""),
                apkUrl = apk,
                htmlUrl = o.optString(
                    "html_url",
                    "https://github.com/TAP-APIA/kurumi-mood-wallpaper/releases/latest"
                )
            )
        } finally {
            conn.disconnect()
        }
    }

    /** Semver-ish compare: numeric parts, missing parts = 0, ignores pre-release suffix. */
    fun compareVersions(a: String, b: String): Int {
        fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V")
            .substringBefore('-').substringBefore('+')
            .split('.').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /** Downloads to cacheDir/updates/ (follows GitHub's redirect to its CDN). */
    fun downloadApk(ctx: Context, release: Release, onProgress: (Int) -> Unit): File {
        val url = release.apkUrl ?: throw IllegalStateException("release 无 APK 附件")
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "kurumi-${release.version}.apk")
        var current = url
        var redirects = 0
        while (true) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "kurumi-mood-wallpaper")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                        ?: throw IllegalStateException("重定向缺少 Location")
                    current = URL(URL(current), loc).toString()
                    if (++redirects > 5) throw IllegalStateException("重定向过多")
                    continue
                }
                if (code !in 200..299) throw IllegalStateException("下载 HTTP $code")
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                            }
                        }
                    }
                }
                if (total > 0 && out.length() != total) {
                    throw IllegalStateException("下载不完整 ${out.length()}/$total")
                }
                return out
            } finally {
                conn.disconnect()
            }
        }
    }
}
