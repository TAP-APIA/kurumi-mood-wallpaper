package com.kurumi.moodwallpaper

import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * Fetches daily % change for the selected [IndexSource].
 *
 * Presets try (in order): Eastmoney push2his f170/100 → Sina → Tencent.
 * push2.eastmoney.com returns 502 from many networks; push2his works (with retries).
 * Custom source: user URL + JSON path + multiplier.
 */
object IndexFetcher {
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val SINA_REF = "https://finance.sina.com.cn"

    data class Result(val pct: Float, val source: String, val label: String)

    fun fetch(ctx: Context): Result {
        val src = IndexSource.fromId(MoodPrefs.getSourceId(ctx))
        return when (src) {
            IndexSource.SHANGHAI -> fetchPreset(
                src,
                eastmoneySecid = "1.000001",
                sinaList = "s_sh000001",
                sinaKind = SinaKind.SIMPLE_PCT3,
                tencentCodes = "sh000001"
            )
            IndexSource.CSI300 -> fetchPreset(
                src,
                eastmoneySecid = "1.000300",
                sinaList = "s_sh000300",
                sinaKind = SinaKind.SIMPLE_PCT3,
                tencentCodes = "sh000300"
            )
            IndexSource.NASDAQ -> fetchPreset(
                src,
                eastmoneySecid = "100.NDX",
                sinaList = "gb_\$ndx",
                sinaKind = SinaKind.GB_PCT2,
                sinaAltList = "int_nasdaq",
                sinaAltKind = SinaKind.SIMPLE_PCT3,
                tencentCodes = "usNDX"
            )
            IndexSource.HSI -> fetchPreset(
                src,
                eastmoneySecid = "100.HSI",
                sinaList = "rt_hkHSI",
                sinaKind = SinaKind.HK_PCT8,
                tencentCodes = "hkHSI"
            )
            IndexSource.GOLD -> fetchPaxg(src)
            IndexSource.CUSTOM -> fetchCustom(ctx)
        }
    }

    private enum class SinaKind {
        /** name,price,change,pct,... */
        SIMPLE_PCT3,
        /** gb_$xxx: name,price,pct,... */
        GB_PCT2,
        /** rt_hkHSI: code,name,cur,prev,open,high,low,...,change,pct(at 8) */
        HK_PCT8
    }

    private fun fetchPreset(
        src: IndexSource,
        eastmoneySecid: String,
        eastmoneyAltSecid: String? = null,
        sinaList: String,
        sinaKind: SinaKind,
        sinaAltList: String? = null,
        sinaAltKind: SinaKind? = null,
        tencentCodes: String
    ): Result {
        tryEastmoney(eastmoneySecid)?.let {
            return Result(it, "eastmoney:$eastmoneySecid", src.label)
        }
        if (eastmoneyAltSecid != null) {
            tryEastmoney(eastmoneyAltSecid)?.let {
                return Result(it, "eastmoney:$eastmoneyAltSecid", src.label)
            }
        }
        trySina(sinaList, sinaKind)?.let {
            return Result(it, "sina:$sinaList", src.label)
        }
        if (sinaAltList != null && sinaAltKind != null) {
            trySina(sinaAltList, sinaAltKind)?.let {
                return Result(it, "sina:$sinaAltList", src.label)
            }
        }
        tryTencent(tencentCodes)?.let {
            return Result(it, "tencent:$tencentCodes", src.label)
        }
        throw IllegalStateException("无法获取 ${src.label} 涨跌幅（Eastmoney/Sina/Tencent 均失败）")
    }

    /** PAX Gold, trades 24/7. CoinGecko 24h change (%), fallback OKX last vs sodUtc8/open24h. */
    private fun fetchPaxg(src: IndexSource): Result {
        try {
            val body = httpGet(
                "https://api.coingecko.com/api/v3/simple/price?ids=pax-gold&vs_currencies=usd&include_24hr_change=true",
                null, preferGbk = false
            )
            val o = JSONObject(body).getJSONObject("pax-gold")
            val pct = o.getDouble("usd_24h_change")
            if (pct.isFinite()) return Result(pct.toFloat(), "coingecko:pax-gold", src.label)
        } catch (_: Exception) {
        }
        try {
            val body = httpGet(
                "https://www.okx.com/api/v5/market/ticker?instId=PAXG-USDT", null, preferGbk = false
            )
            val t = JSONObject(body).getJSONArray("data").getJSONObject(0)
            val last = t.getString("last").toDouble()
            var base = t.optString("sodUtc8").toDoubleOrNull() ?: 0.0
            if (base <= 0.0) base = t.optString("open24h").toDoubleOrNull() ?: 0.0
            if (base > 0.0) {
                return Result(((last - base) / base * 100.0).toFloat(), "okx:PAXG-USDT", src.label)
            }
        } catch (_: Exception) {
        }
        throw IllegalStateException("无法获取 PAXG 涨跌幅（CoinGecko/OKX 均失败）")
    }

    fun fetchCustom(ctx: Context): Result {
        val url = MoodPrefs.getCustomUrl(ctx).trim()
        if (url.isEmpty()) throw IllegalArgumentException("自定义 API：请先填写 URL")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw IllegalArgumentException("自定义 API：URL 须以 http:// 或 https:// 开头")
        }
        val path = MoodPrefs.getCustomPath(ctx).ifBlank { "data.f170" }
        val mult = MoodPrefs.getCustomMult(ctx).let { if (it == 0f || it.isNaN()) 1f else it }
        val body = httpGet(url, referer = null, preferGbk = false)
        val trimmed = body.trim()
        if (trimmed.isEmpty() || (!trimmed.startsWith("{") && !trimmed.startsWith("["))) {
            throw IllegalArgumentException(
                "自定义 API：响应不是 JSON（开头=${trimmed.take(40).replace('\n', ' ')}）"
            )
        }
        val root: Any = if (trimmed.startsWith("[")) {
            org.json.JSONArray(trimmed)
        } else {
            JSONObject(trimmed)
        }
        val raw = JsonPath.readNumber(root, path)
        val pct = (raw * mult).toFloat()
        if (!pct.isFinite()) throw IllegalArgumentException("自定义 API：解析结果无效 ($pct)")
        return Result(pct, "custom", IndexSource.CUSTOM.label)
    }

    /** Eastmoney: f170 is pct*100 (e.g. 5 → 0.05%). Host push2his; retry on flaky SSL. */
    private fun tryEastmoney(secid: String): Float? {
        val url =
            "https://push2his.eastmoney.com/api/qt/stock/get?secid=$secid&fields=f43,f170,f60,f14"
        repeat(3) { attempt ->
            try {
                val body = httpGet(url, referer = null, preferGbk = false)
                if (body.contains("Bad Gateway") || body.trimStart().startsWith("<")) return@repeat
                val data = JSONObject(body).optJSONObject("data") ?: return@repeat
                if (!data.has("f170") || data.isNull("f170")) return@repeat
                return (data.getDouble("f170") / 100.0).toFloat()
            } catch (_: Exception) {
                if (attempt == 2) return null
                try { Thread.sleep(300L * (attempt + 1)) } catch (_: InterruptedException) {}
            }
        }
        return null
    }

    private fun trySina(list: String, kind: SinaKind): Float? {
        return try {
            val body = httpGet("https://hq.sinajs.cn/list=$list", SINA_REF, preferGbk = true)
            val start = body.indexOf('"')
            val end = body.lastIndexOf('"')
            if (start < 0 || end <= start) return null
            val fields = body.substring(start + 1, end).split(",")
            when (kind) {
                SinaKind.SIMPLE_PCT3 -> fields.getOrNull(3)?.trim()?.toFloatOrNull()
                SinaKind.GB_PCT2 -> fields.getOrNull(2)?.trim()?.toFloatOrNull()
                SinaKind.HK_PCT8 -> fields.getOrNull(8)?.trim()?.toFloatOrNull()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Tencent qt.gtimg.cn — pct at field index 32. */
    private fun tryTencent(codes: String): Float? {
        return try {
            val body = httpGet("https://qt.gtimg.cn/q=$codes", null, preferGbk = true)
            val line = body.lineSequence().firstOrNull { it.contains('=') } ?: return null
            val q = line.substringAfter('=').trim().trim('"').trim(';')
            q.split("~").getOrNull(32)?.trim()?.toFloatOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun httpGet(urlStr: String, referer: String?, preferGbk: Boolean): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            requestMethod = "GET"
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "*/*")
            if (referer != null) setRequestProperty("Referer", referer)
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                ?: throw IllegalStateException("HTTP $code")
            val charset = if (preferGbk) Charset.forName("GBK") else Charsets.UTF_8
            return BufferedReader(InputStreamReader(stream, charset)).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
