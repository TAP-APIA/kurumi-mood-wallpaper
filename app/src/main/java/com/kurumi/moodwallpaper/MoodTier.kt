package com.kurumi.moodwallpaper

object MoodTier {
    fun fromPct(pct: Float): Int = when {
        pct > 2f -> 5
        pct >= 0.5f -> 4
        pct >= -0.5f -> 3
        pct >= -2f -> 2
        else -> 1
    }

    fun label(tier: Int): String = when (tier) {
        1 -> "1 cry"
        2 -> "2 worried"
        3 -> "3 calm"
        4 -> "4 smile"
        5 -> "5 bigsmile"
        else -> "3 calm"
    }

    fun rawResId(tier: Int): Int = when (tier.coerceIn(1, 5)) {
        1 -> R.raw.mood_1_cry
        2 -> R.raw.mood_2_worried
        3 -> R.raw.mood_3_calm
        4 -> R.raw.mood_4_smile
        else -> R.raw.mood_5_bigsmile
    }
}
