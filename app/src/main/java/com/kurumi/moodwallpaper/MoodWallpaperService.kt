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
                if (key == MoodPrefs.KEY_DEBUG_TIER || key == MoodPrefs.KEY_TIER) {
                    mainHandler.post { switchIfNeeded(MoodPrefs.effectiveTier(applicationContext)) }
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

        private fun refreshQuote() {
            val debug = MoodPrefs.getDebugTier(applicationContext)
            if (debug in 1..5) {
                switchIfNeeded(debug)
                return
            }
            if (io.isShutdown) return
            io.execute {
                try {
                    val result = IndexFetcher.fetch()
                    val tier = MoodTier.fromPct(result.pct)
                    MoodPrefs.saveQuote(applicationContext, result.pct, tier)
                    mainHandler.post { switchIfNeeded(MoodPrefs.effectiveTier(applicationContext)) }
                } catch (_: Exception) {
                    // Keep last mood (default mood_3)
                    mainHandler.post { switchIfNeeded(MoodPrefs.effectiveTier(applicationContext)) }
                }
            }
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
