package com.kurumi.moodwallpaper

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MoodPrefs {
    private const val NAME = "kurumi_mood"
    const val KEY_PCT = "last_pct"
    const val KEY_TIER = "last_tier"
    const val KEY_DEBUG_TIER = "debug_tier" // 0 = auto, 1..5 = forced
    const val KEY_LAST_ERROR = "last_error"
    const val KEY_RENDER_STATUS = "render_status"
    const val KEY_SOURCE_ID = "index_source_id"
    const val KEY_SOURCE_LABEL = "last_source_label"
    const val KEY_CUSTOM_URL = "custom_url"
    const val KEY_CUSTOM_PATH = "custom_path"
    const val KEY_CUSTOM_MULT = "custom_mult"
    const val KEY_QUOTE_ERROR = "quote_error"
    const val KEY_QUOTE_ERROR_AT = "quote_error_at"
    const val KEY_QUOTE_OK_AT = "quote_ok_at"

    const val DEFAULT_TIER = 3
    const val DEFAULT_SOURCE = "shanghai"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getTier(ctx: Context): Int =
        prefs(ctx).getInt(KEY_TIER, DEFAULT_TIER).coerceIn(1, 5)

    fun getPct(ctx: Context): Float =
        prefs(ctx).getFloat(KEY_PCT, Float.NaN)

    fun getDebugTier(ctx: Context): Int =
        prefs(ctx).getInt(KEY_DEBUG_TIER, 0)

    fun setDebugTier(ctx: Context, tier: Int) {
        prefs(ctx).edit().putInt(KEY_DEBUG_TIER, tier).apply()
    }

    fun getSourceId(ctx: Context): String =
        prefs(ctx).getString(KEY_SOURCE_ID, DEFAULT_SOURCE) ?: DEFAULT_SOURCE

    fun setSourceId(ctx: Context, id: String) {
        prefs(ctx).edit().putString(KEY_SOURCE_ID, id).apply()
    }

    fun getSourceLabel(ctx: Context): String =
        prefs(ctx).getString(KEY_SOURCE_LABEL, "") ?: ""

    fun getCustomUrl(ctx: Context): String =
        prefs(ctx).getString(KEY_CUSTOM_URL, "") ?: ""

    fun getCustomPath(ctx: Context): String =
        prefs(ctx).getString(KEY_CUSTOM_PATH, "data.f170") ?: "data.f170"

    fun getCustomMult(ctx: Context): Float =
        prefs(ctx).getFloat(KEY_CUSTOM_MULT, 0.01f)

    fun saveCustom(ctx: Context, url: String, path: String, mult: Float) {
        prefs(ctx).edit()
            .putString(KEY_CUSTOM_URL, url.trim())
            .putString(KEY_CUSTOM_PATH, path.trim().ifEmpty { "data.f170" })
            .putFloat(KEY_CUSTOM_MULT, if (mult == 0f || mult.isNaN()) 1f else mult)
            .apply()
    }

    fun saveQuote(ctx: Context, pct: Float, tier: Int, sourceLabel: String) {
        prefs(ctx).edit()
            .putFloat(KEY_PCT, pct)
            .putInt(KEY_TIER, tier.coerceIn(1, 5))
            .putString(KEY_SOURCE_LABEL, sourceLabel)
            .apply()
    }

    /** Effective tier: debug override if set, else cached/live tier. */
    fun effectiveTier(ctx: Context): Int {
        val debug = getDebugTier(ctx)
        return if (debug in 1..5) debug else getTier(ctx)
    }

    private fun now(): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())

    fun recordError(ctx: Context, msg: String) {
        prefs(ctx).edit().putString(KEY_LAST_ERROR, "[${now()}] $msg").apply()
    }

    fun getLastError(ctx: Context): String? {
        val e = prefs(ctx).getString(KEY_LAST_ERROR, null)
        // Pre-1.4 builds stored quote failures here; those now live in KEY_QUOTE_ERROR.
        if (e != null && e.contains("] 行情:")) {
            clearLastError(ctx)
            return null
        }
        return e
    }

    fun clearLastError(ctx: Context) {
        prefs(ctx).edit().remove(KEY_LAST_ERROR).apply()
    }

    // ---- quote fetch outcome (separate from render/other errors) ----
    private val quoteLock = Any()

    /**
     * Successful fetch started at [startedAt]: save it (unless a newer success already landed)
     * and clear any quote error from an attempt that started no later than this one.
     */
    fun onQuoteSuccess(ctx: Context, startedAt: Long, pct: Float, tier: Int, sourceLabel: String) =
        synchronized(quoteLock) {
            val p = prefs(ctx)
            if (startedAt < p.getLong(KEY_QUOTE_OK_AT, 0L)) return@synchronized
            val e = p.edit()
                .putFloat(KEY_PCT, pct)
                .putInt(KEY_TIER, tier.coerceIn(1, 5))
                .putString(KEY_SOURCE_LABEL, sourceLabel)
                .putLong(KEY_QUOTE_OK_AT, startedAt)
            if (p.getLong(KEY_QUOTE_ERROR_AT, 0L) <= startedAt) {
                e.remove(KEY_QUOTE_ERROR).remove(KEY_QUOTE_ERROR_AT)
            }
            e.commit()
        }

    /** Failed fetch: only recorded if no fetch that started later has already succeeded. */
    fun onQuoteFailure(ctx: Context, startedAt: Long, msg: String) = synchronized(quoteLock) {
        val p = prefs(ctx)
        if (startedAt < p.getLong(KEY_QUOTE_OK_AT, 0L)) return@synchronized
        p.edit()
            .putString(KEY_QUOTE_ERROR, "[${now()}] 行情: $msg")
            .putLong(KEY_QUOTE_ERROR_AT, startedAt)
            .commit()
    }

    fun getQuoteError(ctx: Context): String? = prefs(ctx).getString(KEY_QUOTE_ERROR, null)

    fun getQuoteOkAt(ctx: Context): Long = prefs(ctx).getLong(KEY_QUOTE_OK_AT, 0L)

    fun clearQuoteError(ctx: Context) {
        prefs(ctx).edit().remove(KEY_QUOTE_ERROR).remove(KEY_QUOTE_ERROR_AT).apply()
    }

    fun setRenderStatus(ctx: Context, msg: String) {
        prefs(ctx).edit().putString(KEY_RENDER_STATUS, "[${now()}] $msg").apply()
    }

    fun getRenderStatus(ctx: Context): String? = prefs(ctx).getString(KEY_RENDER_STATUS, null)
}
