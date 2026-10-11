package com.kurumi.moodwallpaper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * Fetches daily % change for the selected [IndexSource].
 *
 * Every source attempt uses a short timeout (~5 s) and one retry, then moves to the next
 * source. Ordering favors reachability from mainland China. The thrown error lists which
 * sources were tried.
 */
object IndexFetcher {
    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    /** Gate.io rejects browser UAs on its API ("forbidden request"), so use a plain one. */
    private const val PLAIN_UA = "kurumi-mood-wallpaper"
    private const val SINA_REF = "https://finance.sina.com.cn"
    private const val TIMEOUT_MS = 5000
    private const val ATTEMPTS = 2 // 1 try + 1 retry

    data class Result(val pct: Float, val source: String, val label: String)

    /** One candidate source: a name for error messages and a parser that returns % or throws. */
    private class Src(val name: String, val fetch: () -> Double)

    fun fetch(ctx: Context): Result {
        val src = IndexSource.fromId(MoodPrefs.getSourceId(ctx))
        val candidates: List<Src> = when (src) {
            IndexSource.SHANGHAI -> indexSources("1.000001", "s_sh000001", SinaKind.SIMPLE_PCT3, "sh000001")
            IndexSource.CSI300 -> indexSources("1.000300", "s_sh000300", SinaKind.SIMPLE_PCT3, "sh000300")
            IndexSource.NASDAQ -> indexSources(
                "100.NDX", "gb_\$ndx", SinaKind.GB_PCT2, "usNDX",
                sinaAlt = "int_nasdaq" to SinaKind.SIMPLE_PCT3
            )
            IndexSource.HSI -> indexSources("100.HSI", "rt_hkHSI", SinaKind.HK_PCT8, "hkHSI")
            IndexSource.GOLD -> goldSources()
            IndexSource.CUSTOM -> return fetchCustom(ctx)
        }
        return runChain(src, candidates)
    }

    private fun runChain(src: IndexSource, candidates: List<Src>): Result {
        val tried = ArrayList<String>()
        for (c in candidates) {
            val v = withRetry { c.fetch() }
            if (v != null && v.isFinite()) return Result(v.toFloat(), c.name, src.label)
            tried.add(c.name)
        }
        throw IllegalStateException("无法获取 ${src.label} 涨跌幅（已尝试: ${tried.joinToString(", ")}）")
    }

    private inline fun withRetry(block: () -> Double): Double? {
        for (attempt in 0 until ATTEMPTS) {
            try {
                return block()
            } catch (_: Exception) {
                if (attempt < ATTEMPTS - 1) {
                    try { Thread.sleep(400) } catch (_: InterruptedException) { return null }
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------------ index presets

    private enum class SinaKind {
        /** name,price,change,pct,... */
        SIMPLE_PCT3,
        /** gb_$xxx: name,price,pct,... */
        GB_PCT2,
        /** rt_hkHSI: code,name,cur,prev,open,high,low,...,change,pct(at 8) */
        HK_PCT8
    }

    private fun indexSources(
        secid: String,
        sinaList: String,
        sinaKind: SinaKind,
        tencent: String,
        sinaAlt: Pair<String, SinaKind>? = null
    ): List<Src> {
        val list = arrayListOf(
            Src("eastmoney:$secid") { eastmoney(secid) },
            Src("sina:$sinaList") { sina(sinaList, sinaKind) }
        )
        if (sinaAlt != null) list.add(Src("sina:${sinaAlt.first}") { sina(sinaAlt.first, sinaAlt.second) })
        list.add(Src("tencent:$tencent") { tencent(tencent) })
        return list
    }

    /** Eastmoney push2his (push2 often 502s): f170 is pct*100. */
    private fun eastmoney(secid: String): Double {
        val body = httpGet(
            "https://push2his.eastmoney.com/api/qt/stock/get?secid=$secid&fields=f43,f170,f60,f14"
        )
        val data = JSONObject(body).getJSONObject("data")
        return data.getDouble("f170") / 100.0
    }

    private fun sina(list: String, kind: SinaKind): Double {
        val body = httpGet("https://hq.sinajs.cn/list=$list", referer = SINA_REF, gbk = true)
        val start = body.indexOf('"')
        val end = body.lastIndexOf('"')
        require(start >= 0 && end > start) { "sina empty" }
        val f = body.substring(start + 1, end).split(",")
        val idx = when (kind) {
            SinaKind.SIMPLE_PCT3 -> 3
            SinaKind.GB_PCT2 -> 2
            SinaKind.HK_PCT8 -> 8
        }
        return f[idx].trim().toDouble()
    }

    /** Tencent qt.gtimg.cn — pct at field index 32. */
    private fun tencent(code: String): Double {
        val body = httpGet("https://qt.gtimg.cn/q=$code", gbk = true)
        val q = body.substringAfter('=').trim().trim(';').trim('"')
        return q.split("~")[32].trim().toDouble()
    }

    // ------------------------------------------------------------------ gold (PAXG, 7×24)

    private fun goldSources(): List<Src> = listOf(
        Src("okx(www)") { okx("www.okx.com") },
        Src("okx(aws)") { okx("aws.okx.com") },
        Src("gate") { gate() },
        Src("htx(XAUT)") { htx() },
        Src("bitget") { bitget() },
        Src("kucoin") { kucoin() },
        Src("mexc") { mexc() },
        Src("coingecko") { coingecko() }
    )

    /** OKX: (last - sodUtc8)/sodUtc8 (fallback open24h). */
    private fun okx(host: String): Double {
        val t = JSONObject(httpGet("https://$host/api/v5/market/ticker?instId=PAXG-USDT"))
            .getJSONArray("data").getJSONObject(0)
        val last = t.getString("last").toDouble()
        var base = t.optString("sodUtc8").toDoubleOrNull() ?: 0.0
        if (base <= 0.0) base = t.getString("open24h").toDouble()
        return pctOf(last, base)
    }

    /** Gate: change_percentage is already % (e.g. "-0.02"). */
    private fun gate(): Double {
        val arr = JSONArray(
            httpGet("https://api.gateio.ws/api/v4/spot/tickers?currency_pair=PAXG_USDT", ua = PLAIN_UA)
        )
        return arr.getJSONObject(0).getString("change_percentage").toDouble()
    }

    /** HTX has no PAXG pair; XAUT (Tether Gold) tracks gold too. (close-open)/open. */
    private fun htx(): Double {
        val o = JSONObject(httpGet("https://api.huobi.pro/market/detail/merged?symbol=xautusdt"))
        require(o.optString("status") == "ok") { "htx status ${o.optString("status")}" }
        val tick = o.getJSONObject("tick")
        return pctOf(tick.getDouble("close"), tick.getDouble("open"))
    }

    /** Bitget: change24h is a fraction (-0.00018 = -0.018%). */
    private fun bitget(): Double {
        val o = JSONObject(httpGet("https://api.bitget.com/api/v2/spot/market/tickers?symbol=PAXGUSDT"))
        require(o.optString("code") == "00000") { "bitget code ${o.optString("code")}" }
        return o.getJSONArray("data").getJSONObject(0).getString("change24h").toDouble() * 100.0
    }

    /** KuCoin: changeRate is a fraction. */
    private fun kucoin(): Double {
        val o = JSONObject(httpGet("https://api.kucoin.com/api/v1/market/stats?symbol=PAXG-USDT"))
        return o.getJSONObject("data").getString("changeRate").toDouble() * 100.0
    }

    /** MEXC symbol is GOLD(PAXG)USDT; priceChangePercent is a coarse fraction, so compute from prices. */
    private fun mexc(): Double {
        val o = JSONObject(httpGet("https://api.mexc.com/api/v3/ticker/24hr?symbol=GOLD%28PAXG%29USDT"))
        return pctOf(o.getString("lastPrice").toDouble(), o.getString("prevClosePrice").toDouble())
    }

    /** CoinGecko: usd_24h_change is already %. */
    private fun coingecko(): Double {
        val o = JSONObject(
            httpGet("https://api.coingecko.com/api/v3/simple/price?ids=pax-gold&vs_currencies=usd&include_24hr_change=true")
        )
        return o.getJSONObject("pax-gold").getDouble("usd_24h_change")
    }

    private fun pctOf(last: Double, base: Double): Double {
        require(base > 0.0) { "base <= 0" }
        return (last - base) / base * 100.0
    }

    // ------------------------------------------------------------------ custom

    fun fetchCustom(ctx: Context): Result {
        val url = MoodPrefs.getCustomUrl(ctx).trim()
        if (url.isEmpty()) throw IllegalArgumentException("自定义 API：请先填写 URL")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw IllegalArgumentException("自定义 API：URL 须以 http:// 或 https:// 开头")
        }
        val path = MoodPrefs.getCustomPath(ctx).ifBlank { "data.f170" }
        val mult = MoodPrefs.getCustomMult(ctx).let { if (it == 0f || it.isNaN()) 1f else it }
        var body: String? = null
        var lastErr: Exception? = null
        for (attempt in 0 until ATTEMPTS) {
            try {
                body = httpGet(url)
                break
            } catch (e: Exception) {
                lastErr = e
                if (attempt < ATTEMPTS - 1) Thread.sleep(400)
            }
        }
        val trimmed = body?.trim()
            ?: throw IllegalStateException("自定义 API：请求失败（${lastErr?.message ?: "未知错误"}）")
        if (trimmed.isEmpty() || (!trimmed.startsWith("{") && !trimmed.startsWith("["))) {
            throw IllegalArgumentException(
                "自定义 API：响应不是 JSON（开头=${trimmed.take(40).replace('\n', ' ')}）"
            )
        }
        val root: Any = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
        val raw = JsonPath.readNumber(root, path)
        val pct = (raw * mult).toFloat()
        if (!pct.isFinite()) throw IllegalArgumentException("自定义 API：解析结果无效 ($pct)")
        return Result(pct, "custom", IndexSource.CUSTOM.label)
    }

    // ------------------------------------------------------------------ http

    private fun httpGet(
        urlStr: String,
        referer: String? = null,
        gbk: Boolean = false,
        ua: String = BROWSER_UA
    ): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", ua)
            setRequestProperty("Accept", "application/json, text/plain, */*")
            if (referer != null) setRequestProperty("Referer", referer)
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val charset = if (gbk) Charset.forName("GBK") else Charsets.UTF_8
            return BufferedReader(InputStreamReader(conn.inputStream, charset)).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
