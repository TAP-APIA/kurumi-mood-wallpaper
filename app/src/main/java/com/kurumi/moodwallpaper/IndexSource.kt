package com.kurumi.moodwallpaper

/**
 * Preset market sources that yield a daily % change.
 * [CUSTOM] uses user-provided URL + JSON path.
 */
enum class IndexSource(val id: String, val label: String) {
    SHANGHAI("shanghai", "上证指数"),
    CSI300("csi300", "沪深300"),
    NASDAQ("nasdaq", "纳斯达克"),
    HSI("hsi", "恒生指数"),
    GOLD("gold", "黄金 (7×24 PAXG)"),
    CUSTOM("custom", "自定义 API");

    companion object {
        fun fromId(id: String): IndexSource =
            entries.firstOrNull { it.id == id } ?: SHANGHAI

        fun labels(): Array<String> = entries.map { it.label }.toTypedArray()

        fun indexOf(id: String): Int =
            entries.indexOf(fromId(id)).coerceAtLeast(0)
    }
}
