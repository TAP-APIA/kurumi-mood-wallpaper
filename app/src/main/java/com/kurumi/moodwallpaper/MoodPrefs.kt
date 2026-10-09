package com.kurumi.moodwallpaper

import android.content.Context
import android.content.SharedPreferences

object MoodPrefs {
    private const val NAME = "kurumi_mood"
    const val KEY_PCT = "last_pct"
    const val KEY_TIER = "last_tier"
    const val KEY_DEBUG_TIER = "debug_tier" // 0 = auto, 1..5 = forced

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
}
