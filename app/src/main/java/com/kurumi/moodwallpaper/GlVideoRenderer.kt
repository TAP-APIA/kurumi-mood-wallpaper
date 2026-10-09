package com.kurumi.moodwallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Owns a dedicated GL thread for one wallpaper Engine.
 *
 * Pipeline: MediaPlayer -> Surface(SurfaceTexture(OES tex)) -> full-screen quad (center-crop)
 * drawn with GLES2 onto an EGL window surface created from the wallpaper SurfaceHolder.
 * Until the first video frame arrives (or if the player fails), the tier's still JPG is drawn
 * as a regular GL_TEXTURE_2D, so the wallpaper is never black. If EGL itself fails, we fall
 * back to drawing the still with a software Canvas.
 *
 * All public methods may be called from the main thread; all state below is touched only on
 * the GL thread.
 */
class GlVideoRenderer(context: Context, private val tag: String) {

    private val ctx = context.applicationContext
    private val thread = HandlerThread("KurumiGL-$tag").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var quit = false

    // ---- GL-thread state ----
    private var windowSurface: Surface? = null
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var glFailed = false // EGL/GL init failed -> Canvas fallback

    private var surfW = 0
    private var surfH = 0

    private var progOes: Program? = null
    private var prog2d: Program? = null

    private var stillTex = 0
    private var stillW = 0
    private var stillH = 0
    private var stillBitmapForCanvas: Bitmap? = null // only used in Canvas fallback

    private var oesTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var videoSurface: Surface? = null
    private var player: MediaPlayer? = null
    private var playerPrepared = false
    private var playerGen = 0
    private var videoW = 0
    private var videoH = 0
    private var frameReady = false
    private var loggedPlaying = false

    private var visible = false
    private var desiredTier = -1
    private var loadedTier = -1

    private val texMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private val posBuf: FloatBuffer = floatBuffer(
        floatArrayOf(
            -1f, -1f, // bottom-left
            1f, -1f,  // bottom-right
            -1f, 1f,  // top-left
            1f, 1f    // top-right
        )
    )
    private val texBuf: FloatBuffer = floatBuffer(FloatArray(8))
    private val texArr = FloatArray(8)

    // ---------------------------------------------------------------- public API (any thread)

    fun surfaceCreated(surface: Surface) = post { onSurfaceCreatedGl(surface) }

    fun surfaceChanged(width: Int, height: Int) = post {
        surfW = width
        surfH = height
        render()
    }

    /** Blocks (bounded) until GL resources tied to the surface are released. */
    fun surfaceDestroyed() = runSync { teardownSurface() }

    fun setVisible(v: Boolean) = post {
        visible = v
        if (v) {
            ensurePlayer()
            render()
        } else {
            try {
                if (player?.isPlaying == true) player?.pause()
            } catch (e: Exception) {
                err("pause", e)
            }
        }
    }

    fun setTier(tier: Int) = post {
        desiredTier = tier.coerceIn(1, 5)
        applyTier()
    }

    fun release() {
        if (quit) return
        runSync { teardownSurface() }
        quit = true
        thread.quitSafely()
    }

    // ---------------------------------------------------------------- thread helpers

    private fun post(r: () -> Unit) {
        if (quit) return
        handler.post {
            try {
                r()
            } catch (t: Throwable) {
                err("gl-thread", t)
            }
        }
    }

    private fun runSync(r: () -> Unit) {
        if (quit) return
        if (Looper.myLooper() == thread.looper) {
            r(); return
        }
        val latch = CountDownLatch(1)
        val posted = handler.post {
            try {
                r()
            } catch (t: Throwable) {
                err("gl-thread-sync", t)
            } finally {
                latch.countDown()
            }
        }
        if (posted) {
            try {
                latch.await(2, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
            }
        }
    }

    private fun err(where: String, t: Throwable) {
        val msg = "[$tag] $where: ${t.javaClass.simpleName}: ${t.message}"
        Log.e(TAG, msg, t)
        MoodPrefs.recordError(ctx, msg)
    }

    private fun err(msg: String) {
        Log.e(TAG, "[$tag] $msg")
        MoodPrefs.recordError(ctx, "[$tag] $msg")
    }

    private fun status(msg: String) {
        Log.i(TAG, "[$tag] $msg")
        MoodPrefs.setRenderStatus(ctx, "[$tag] $msg")
    }

    // ---------------------------------------------------------------- surface lifecycle

    private fun onSurfaceCreatedGl(surface: Surface) {
        if (windowSurface != null) teardownSurface()
        windowSurface = surface
        glFailed = false
        loadedTier = -1
        try {
            initEgl(surface)
            progOes = Program(VERTEX_SHADER, FRAGMENT_OES)
            prog2d = Program(VERTEX_SHADER, FRAGMENT_2D)
        } catch (t: Throwable) {
            err("GL init", t)
            releaseGlObjects()
            releaseEgl()
            glFailed = true
        }
        applyTier()
        render()
    }

    private fun teardownSurface() {
        releasePlayer()
        releaseGlObjects()
        releaseEgl()
        stillBitmapForCanvas?.recycle()
        stillBitmapForCanvas = null
        windowSurface = null
        loadedTier = -1
        surfW = 0
        surfH = 0
    }

    private fun initEgl(surface: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay failed")
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) {
            throw RuntimeException("eglInitialize failed 0x${Integer.toHexString(EGL14.eglGetError())}")
        }
        val config = chooseConfig(eglDisplay)
        eglContext = EGL14.eglCreateContext(
            eglDisplay, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("eglCreateContext failed 0x${Integer.toHexString(EGL14.eglGetError())}")
        }
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, config, surface, intArrayOf(EGL14.EGL_NONE), 0
        )
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("eglCreateWindowSurface failed 0x${Integer.toHexString(EGL14.eglGetError())}")
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent failed 0x${Integer.toHexString(EGL14.eglGetError())}")
        }
    }

    private fun chooseConfig(display: EGLDisplay): EGLConfig {
        val candidates = listOf(
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
            ),
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
            ),
            intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
            )
        )
        for (attribs in candidates) {
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) &&
                num[0] > 0 && configs[0] != null
            ) {
                return configs[0]!!
            }
        }
        throw RuntimeException("No suitable EGLConfig")
    }

    private fun releaseEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            // NOTE: no eglTerminate: the default display is shared with other engines
            // (e.g. preview + real wallpaper) in this process.
        }
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
    }

    private fun hasGl(): Boolean = !glFailed && eglSurface != EGL14.EGL_NO_SURFACE

    private fun releaseGlObjects() {
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            // context is current on this thread
            if (stillTex != 0) GLES20.glDeleteTextures(1, intArrayOf(stillTex), 0)
            progOes?.release()
            prog2d?.release()
        }
        stillTex = 0
        stillW = 0
        stillH = 0
        progOes = null
        prog2d = null
    }

    // ---------------------------------------------------------------- tier / content

    private fun applyTier() {
        val t = desiredTier
        if (t !in 1..5 || windowSurface == null) return
        if (t == loadedTier) {
            ensurePlayer()
            return
        }
        loadedTier = t
        releasePlayer()
        loadStill(t)
        render() // show still immediately
        if (hasGl()) startPlayer(t)
    }

    private fun decodeStill(t: Int): Bitmap? = try {
        val opts = BitmapFactory.Options().apply {
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeResource(ctx.resources, MoodTier.stillResId(t), opts)
    } catch (t2: Throwable) {
        err("decode still", t2)
        null
    }

    private fun loadStill(t: Int) {
        val bmp = decodeStill(t) ?: return
        if (hasGl()) {
            if (stillTex != 0) GLES20.glDeleteTextures(1, intArrayOf(stillTex), 0)
            stillTex = createTexture(GLES20.GL_TEXTURE_2D)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            stillW = bmp.width
            stillH = bmp.height
            bmp.recycle()
            checkGl("upload still")
        } else {
            stillBitmapForCanvas?.recycle()
            stillBitmapForCanvas = bmp
            stillW = bmp.width
            stillH = bmp.height
        }
    }

    private fun startPlayer(t: Int) {
        val gen = ++playerGen
        try {
            oesTex = createTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
            val st = SurfaceTexture(oesTex)
            st.setOnFrameAvailableListener({ if (gen == playerGen) onFrameAvailable() }, handler)
            surfaceTexture = st
            val vs = Surface(st)
            videoSurface = vs

            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            ctx.resources.openRawResourceFd(MoodTier.rawResId(t)).use { afd ->
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            mp.setSurface(vs)
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setOnVideoSizeChangedListener { _, w, h ->
                if (gen == playerGen && w > 0 && h > 0) {
                    videoW = w; videoH = h
                }
            }
            mp.setOnPreparedListener { p ->
                if (gen != playerGen) return@setOnPreparedListener
                playerPrepared = true
                if (p.videoWidth > 0 && p.videoHeight > 0) {
                    videoW = p.videoWidth; videoH = p.videoHeight
                }
                if (visible) p.start()
            }
            mp.setOnErrorListener { _, what, extra ->
                if (gen == playerGen) {
                    err("MediaPlayer error what=$what extra=$extra tier=$t")
                    releasePlayer()
                    render() // falls back to still
                }
                true
            }
            mp.prepareAsync()
        } catch (e: Throwable) {
            err("startPlayer tier=$t", e)
            releasePlayer()
            render()
        }
    }

    /** Recreate the player if it went away (e.g. after an error), or resume it. */
    private fun ensurePlayer() {
        if (!hasGl() || loadedTier !in 1..5) return
        val p = player
        if (p == null) {
            startPlayer(loadedTier)
            return
        }
        if (visible && playerPrepared) {
            try {
                if (!p.isPlaying) p.start()
            } catch (e: Exception) {
                err("resume", e)
            }
        }
    }

    private fun releasePlayer() {
        playerGen++ // invalidates callbacks from the old player
        player?.let { p ->
            try { p.setSurface(null) } catch (_: Exception) {}
            try { p.release() } catch (_: Exception) {}
        }
        player = null
        playerPrepared = false
        frameReady = false
        loggedPlaying = false
        videoW = 0
        videoH = 0
        try { surfaceTexture?.setOnFrameAvailableListener(null) } catch (_: Exception) {}
        videoSurface?.release()
        videoSurface = null
        surfaceTexture?.release()
        surfaceTexture = null
        if (oesTex != 0 && eglSurface != EGL14.EGL_NO_SURFACE) {
            GLES20.glDeleteTextures(1, intArrayOf(oesTex), 0)
        }
        oesTex = 0
    }

    private fun onFrameAvailable() {
        val st = surfaceTexture ?: return
        if (!hasGl()) return
        try {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
            frameReady = true
            if (!loggedPlaying) {
                loggedPlaying = true
                status("video playing tier=$loadedTier video=${videoW}x$videoH surface=${surfW}x$surfH")
            }
        } catch (e: Throwable) {
            err("updateTexImage", e)
            return
        }
        render()
    }

    // ---------------------------------------------------------------- drawing

    private fun render() {
        if (windowSurface == null) return
        if (!hasGl()) {
            canvasFallback()
            return
        }
        // Prefer actual EGL surface size; fall back to onSurfaceChanged values.
        val wh = IntArray(1)
        var w = surfW
        var h = surfH
        if (EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, wh, 0) && wh[0] > 0) w = wh[0]
        if (EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, wh, 0) && wh[0] > 0) h = wh[0]
        if (w <= 0 || h <= 0) return

        GLES20.glViewport(0, 0, w, h)
        // Dark plum instead of pure black, so "black" never means "nothing drawn".
        GLES20.glClearColor(0.10f, 0.06f, 0.12f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val oes = progOes
        val tex2d = prog2d
        if (frameReady && player != null && oesTex != 0 && oes != null) {
            val cw = if (videoW > 0) videoW else stillW
            val ch = if (videoH > 0) videoH else stillH
            drawQuad(oes, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex, texMatrix, cw, ch, w, h, flipV = false)
        } else if (stillTex != 0 && tex2d != null) {
            drawQuad(tex2d, GLES20.GL_TEXTURE_2D, stillTex, identity, stillW, stillH, w, h, flipV = true)
        }

        if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
            val e = EGL14.eglGetError()
            err("eglSwapBuffers failed 0x${Integer.toHexString(e)}")
        }
    }

    /**
     * Full-screen quad with center-crop. Texcoords are cropped in the content's normalized space
     * (before the SurfaceTexture transform for OES).
     *
     * Vertex order (triangle strip): BL, BR, TL, TR.
     * - OES (flipV=false): BL->(u0,v0) ... matches SurfaceTexture's transform convention.
     * - Bitmap 2D (flipV=true): GLUtils uploads bitmap row 0 at t=0, so screen-bottom gets v1.
     */
    private fun drawQuad(
        prog: Program, target: Int, tex: Int, m: FloatArray,
        contentW: Int, contentH: Int, viewW: Int, viewH: Int, flipV: Boolean
    ) {
        var u0 = 0f; var u1 = 1f; var v0 = 0f; var v1 = 1f
        if (contentW > 0 && contentH > 0) {
            val contentAspect = contentW.toFloat() / contentH
            val viewAspect = viewW.toFloat() / viewH
            if (contentAspect > viewAspect) {
                val f = viewAspect / contentAspect // visible fraction of width
                u0 = (1f - f) / 2f; u1 = 1f - u0
            } else {
                val f = contentAspect / viewAspect // visible fraction of height
                v0 = (1f - f) / 2f; v1 = 1f - v0
            }
        }
        if (!flipV) {
            texArr[0] = u0; texArr[1] = v0
            texArr[2] = u1; texArr[3] = v0
            texArr[4] = u0; texArr[5] = v1
            texArr[6] = u1; texArr[7] = v1
        } else {
            texArr[0] = u0; texArr[1] = v1
            texArr[2] = u1; texArr[3] = v1
            texArr[4] = u0; texArr[5] = v0
            texArr[6] = u1; texArr[7] = v0
        }
        texBuf.clear()
        texBuf.put(texArr).position(0)
        posBuf.position(0)

        GLES20.glUseProgram(prog.id)
        GLES20.glEnableVertexAttribArray(prog.aPos)
        GLES20.glVertexAttribPointer(prog.aPos, 2, GLES20.GL_FLOAT, false, 0, posBuf)
        GLES20.glEnableVertexAttribArray(prog.aTex)
        GLES20.glVertexAttribPointer(prog.aTex, 2, GLES20.GL_FLOAT, false, 0, texBuf)
        GLES20.glUniformMatrix4fv(prog.uTexM, 1, false, m, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, tex)
        GLES20.glUniform1i(prog.sTex, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(prog.aPos)
        GLES20.glDisableVertexAttribArray(prog.aTex)
        GLES20.glBindTexture(target, 0)
        GLES20.glUseProgram(0)
    }

    /** Software fallback when EGL can't be set up: draw the still center-cropped via Canvas. */
    private fun canvasFallback() {
        val s = windowSurface ?: return
        if (stillBitmapForCanvas == null && loadedTier in 1..5) {
            stillBitmapForCanvas = decodeStill(loadedTier)
        }
        val bmp = stillBitmapForCanvas
        try {
            val c = s.lockCanvas(null) ?: return
            try {
                c.drawColor(Color.rgb(26, 15, 31))
                if (bmp != null) {
                    val vw = c.width
                    val vh = c.height
                    val scale = maxOf(vw.toFloat() / bmp.width, vh.toFloat() / bmp.height)
                    val srcW = (vw / scale).toInt().coerceAtMost(bmp.width)
                    val srcH = (vh / scale).toInt().coerceAtMost(bmp.height)
                    val sx = (bmp.width - srcW) / 2
                    val sy = (bmp.height - srcH) / 2
                    c.drawBitmap(
                        bmp, Rect(sx, sy, sx + srcW, sy + srcH), Rect(0, 0, vw, vh),
                        Paint(Paint.FILTER_BITMAP_FLAG)
                    )
                }
            } finally {
                s.unlockCanvasAndPost(c)
            }
            status("canvas fallback (no GL) tier=$loadedTier")
        } catch (e: Throwable) {
            err("canvas fallback", e)
        }
    }

    // ---------------------------------------------------------------- GL utils

    private fun createTexture(target: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(target, ids[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        checkGl("createTexture")
        return ids[0]
    }

    private fun checkGl(op: String) {
        val e = GLES20.glGetError()
        if (e != GLES20.GL_NO_ERROR) err("$op: glError 0x${Integer.toHexString(e)}")
    }

    private class Program(vs: String, fs: String) {
        val id: Int
        val aPos: Int
        val aTex: Int
        val uTexM: Int
        val sTex: Int

        init {
            val v = compile(GLES20.GL_VERTEX_SHADER, vs)
            val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, v)
            GLES20.glAttachShader(p, f)
            GLES20.glLinkProgram(p)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
            GLES20.glDeleteShader(v)
            GLES20.glDeleteShader(f)
            if (ok[0] == 0) {
                val log = GLES20.glGetProgramInfoLog(p)
                GLES20.glDeleteProgram(p)
                throw RuntimeException("link failed: $log")
            }
            id = p
            aPos = GLES20.glGetAttribLocation(p, "aPos")
            aTex = GLES20.glGetAttribLocation(p, "aTex")
            uTexM = GLES20.glGetUniformLocation(p, "uTexM")
            sTex = GLES20.glGetUniformLocation(p, "sTex")
            if (aPos < 0 || aTex < 0 || uTexM < 0 || sTex < 0) {
                throw RuntimeException("bad locations aPos=$aPos aTex=$aTex uTexM=$uTexM sTex=$sTex")
            }
        }

        fun release() = GLES20.glDeleteProgram(id)

        private fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(s)
                GLES20.glDeleteShader(s)
                throw RuntimeException("shader compile failed: $log")
            }
            return s
        }
    }

    companion object {
        private const val TAG = "KurumiGL"

        private const val VERTEX_SHADER = """
attribute vec4 aPos;
attribute vec4 aTex;
uniform mat4 uTexM;
varying vec2 vTex;
void main() {
    gl_Position = aPos;
    vTex = (uTexM * aTex).xy;
}
"""

        private const val FRAGMENT_OES = """#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vTex;
uniform samplerExternalOES sTex;
void main() {
    gl_FragColor = texture2D(sTex, vTex);
}
"""

        private const val FRAGMENT_2D = """
precision mediump float;
varying vec2 vTex;
uniform sampler2D sTex;
void main() {
    gl_FragColor = texture2D(sTex, vTex);
}
"""

        private fun floatBuffer(data: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply { put(data); position(0) }
    }
}
