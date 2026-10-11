package com.kurumi.moodwallpaper

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.util.concurrent.Executors

class MoodWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = MoodEngine()

    private inner class MoodEngine : Engine() {
        private val mainHandler = Handler(Looper.getMainLooper())
        private val io = Executors.newSingleThreadExecutor()
        private var renderer: GlVideoRenderer? = null
        private var visible = false
        private var currentTier = -1

        private val refreshRunnable = object : Runnable {
            override fun run() {
                if (!visible) return
                refreshQuote()
                mainHandler.postDelayed(this, REFRESH_MS)
            }
        }

        // Debug spinner in MainActivity changes this; apply immediately (useful in preview).
        private val prefListener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                when (key) {
                    MoodPrefs.KEY_DEBUG_TIER, MoodPrefs.KEY_TIER ->
                        mainHandler.post {
                            switchIfNeeded(MoodPrefs.effectiveTier(applicationContext))
                        }
                    MoodPrefs.KEY_SOURCE_ID, MoodPrefs.KEY_CUSTOM_URL,
                    MoodPrefs.KEY_CUSTOM_PATH, MoodPrefs.KEY_CUSTOM_MULT ->
                        mainHandler.post { refreshQuote(force = true) }
                }
            }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            setOffsetNotificationsEnabled(false)
            val r = GlVideoRenderer(applicationContext, if (isPreview) "preview" else "wallpaper")
            renderer = r
            switchIfNeeded(MoodPrefs.effectiveTier(applicationContext))
            MoodPrefs.prefs(applicationContext).registerOnSharedPreferenceChangeListener(prefListener)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            renderer?.surfaceCreated(holder.surface)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            renderer?.surfaceChanged(width, height)
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            renderer?.surfaceChanged(holder.surfaceFrame.width(), holder.surfaceFrame.height())
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            // Blocks until EGL surface / player are released, before the Surface goes away.
            renderer?.surfaceDestroyed()
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            renderer?.setVisible(visible)
            mainHandler.removeCallbacks(refreshRunnable)
            if (visible) {
                switchIfNeeded(MoodPrefs.effectiveTier(applicationContext))
                refreshQuote()
                mainHandler.postDelayed(refreshRunnable, REFRESH_MS)
            }
        }

        override fun onDestroy() {
            mainHandler.removeCallbacks(refreshRunnable)
            MoodPrefs.prefs(applicationContext).unregisterOnSharedPreferenceChangeListener(prefListener)
            renderer?.release()
            renderer = null
            io.shutdownNow()
            super.onDestroy()
        }

        private fun refreshQuote(force: Boolean = false) {
            val debug = MoodPrefs.getDebugTier(applicationContext)
            if (debug in 1..5) {
                switchIfNeeded(debug)
                return
            }
            if (io.isShutdown) return
            io.execute {
                val startedAt = System.currentTimeMillis()
                try {
                    val result = IndexFetcher.fetch(applicationContext)
                    val tier = MoodTier.fromPct(result.pct)
                    MoodPrefs.onQuoteSuccess(
                        applicationContext, startedAt, result.pct, tier,
                        "${result.label} (${result.source})"
                    )
                } catch (e: Exception) {
                    // Keep last mood (default mood_3); error only kept if still the latest attempt.
                    MoodPrefs.onQuoteFailure(
                        applicationContext, startedAt, e.message ?: e.javaClass.simpleName
                    )
                }
                mainHandler.post { switchIfNeeded(MoodPrefs.effectiveTier(applicationContext)) }
            }
            if (force) { /* just kick off above */ }
        }

        /** Only switch video when the tier actually changes. */
        private fun switchIfNeeded(tier: Int) {
            val t = tier.coerceIn(1, 5)
            if (t == currentTier) return
            currentTier = t
            renderer?.setTier(t)
        }
    }

    companion object {
        private const val REFRESH_MS = 15 * 60 * 1000L
    }
}
