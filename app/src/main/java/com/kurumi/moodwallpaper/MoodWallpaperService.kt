package com.kurumi.moodwallpaper

import android.media.MediaPlayer
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
        private var player: MediaPlayer? = null
        private var surfaceReady = false
        private var visible = false
        private var playingTier: Int = -1

        private val refreshRunnable = object : Runnable {
            override fun run() {
                if (!visible) return
                refreshQuote()
                mainHandler.postDelayed(this, REFRESH_MS)
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            // Prefer wallpaper surface for video
            setTouchEventsEnabled(false)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            surfaceReady = true
            ensurePlayer(MoodPrefs.effectiveTier(applicationContext))
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            releasePlayer()
            super.onSurfaceDestroyed(holder)
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            // MediaPlayer scaling mode handles center-crop; restart if needed
            if (surfaceReady && playingTier > 0) {
                player?.setSurface(holder.surface)
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                refreshQuote()
                mainHandler.removeCallbacks(refreshRunnable)
                mainHandler.postDelayed(refreshRunnable, REFRESH_MS)
                if (surfaceReady) {
                    ensurePlayer(MoodPrefs.effectiveTier(applicationContext))
                    try {
                        player?.start()
                    } catch (_: Exception) {
                    }
                }
            } else {
                mainHandler.removeCallbacks(refreshRunnable)
                try {
                    if (player?.isPlaying == true) player?.pause()
                } catch (_: Exception) {
                }
            }
        }

        override fun onDestroy() {
            mainHandler.removeCallbacks(refreshRunnable)
            releasePlayer()
            io.shutdownNow()
            super.onDestroy()
        }

        private fun refreshQuote() {
            // Debug override: skip network, just apply forced tier
            val debug = MoodPrefs.getDebugTier(applicationContext)
            if (debug in 1..5) {
                mainHandler.post { switchIfNeeded(debug) }
                return
            }
            io.execute {
                try {
                    val result = IndexFetcher.fetch()
                    val tier = MoodTier.fromPct(result.pct)
                    MoodPrefs.saveQuote(applicationContext, result.pct, tier)
                    mainHandler.post { switchIfNeeded(tier) }
                } catch (_: Exception) {
                    // Keep last mood (default mood_3 already in prefs)
                    mainHandler.post {
                        switchIfNeeded(MoodPrefs.getTier(applicationContext))
                    }
                }
            }
        }

        private fun switchIfNeeded(tier: Int) {
            val t = tier.coerceIn(1, 5)
            if (t == playingTier && player != null) return
            ensurePlayer(t)
        }

        private fun ensurePlayer(tier: Int) {
            if (!surfaceReady) return
            val t = tier.coerceIn(1, 5)
            if (t == playingTier && player != null) {
                if (visible) {
                    try {
                        if (player?.isPlaying != true) player?.start()
                    } catch (_: Exception) {
                    }
                }
                return
            }
            releasePlayer()
            playingTier = t
            try {
                val mp = MediaPlayer.create(applicationContext, MoodTier.rawResId(t))
                    ?: return
                mp.isLooping = true
                mp.setVolume(0f, 0f)
                mp.setVideoScalingMode(
                    MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                )
                mp.setSurface(surfaceHolder.surface)
                mp.setOnErrorListener { _, _, _ ->
                    releasePlayer()
                    true
                }
                player = mp
                if (visible) {
                    mp.start()
                }
            } catch (_: Exception) {
                releasePlayer()
            }
        }

        private fun releasePlayer() {
            try {
                player?.setSurface(null)
            } catch (_: Exception) {
            }
            try {
                player?.reset()
            } catch (_: Exception) {
            }
            try {
                player?.release()
            } catch (_: Exception) {
            }
            player = null
            playingTier = -1
        }
    }

    companion object {
        private const val REFRESH_MS = 15 * 60 * 1000L
    }
}
