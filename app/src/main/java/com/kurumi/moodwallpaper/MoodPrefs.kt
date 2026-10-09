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

    const val DEFAULT_TIER = 3

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

    fun saveQuote(ctx: Context, pct: Float, tier: Int) {
        prefs(ctx).edit()
            .putFloat(KEY_PCT, pct)
            .putInt(KEY_TIER, tier.coerceIn(1, 5))
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

    fun getLastError(ctx: Context): String? = prefs(ctx).getString(KEY_LAST_ERROR, null)

    fun clearLastError(ctx: Context) {
        prefs(ctx).edit().remove(KEY_LAST_ERROR).apply()
    }

    fun setRenderStatus(ctx: Context, msg: String) {
        prefs(ctx).edit().putString(KEY_RENDER_STATUS, "[${now()}] $msg").apply()
    }

    fun getRenderStatus(ctx: Context): String? = prefs(ctx).getString(KEY_RENDER_STATUS, null)
}
