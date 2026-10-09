package com.kurumi.moodwallpaper

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * Fetches Shanghai Composite (上证指数) daily % change.
 * Prefer Eastmoney f170 (value is pct*100); fall back to Sina hq.sinajs.cn.
 */
object IndexFetcher {
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private const val EASTMONEY =
        "https://push2.eastmoney.com/api/qt/stock/get?secid=1.000001&fields=f170"

    private const val SINA = "https://hq.sinajs.cn/list=s_sh000001"

    data class Result(val pct: Float, val source: String)

    fun fetch(): Result {
        tryEastmoney()?.let { return it }
        return trySina()
            ?: throw IllegalStateException("Unable to fetch 上证指数 pct from Eastmoney or Sina")
    }

    private fun tryEastmoney(): Result? {
        return try {
            val body = httpGet(EASTMONEY, null)
            val data = JSONObject(body).optJSONObject("data") ?: return null
            if (!data.has("f170") || data.isNull("f170")) return null
            // f170 is pct change * 100 (e.g. 5 means 0.05%)
            val raw = data.getDouble("f170")
            Result((raw / 100.0).toFloat(), "eastmoney")
        } catch (_: Exception) {
            null
        }
    }

    private fun trySina(): Result? {
        return try {
            val body = httpGet(SINA, "https://finance.sina.com.cn")
            // var hq_str_s_sh000001="名称,现价,涨跌额,涨跌幅%,成交量,成交额";
            val start = body.indexOf('"')
            val end = body.lastIndexOf('"')
            if (start < 0 || end <= start) return null
            val fields = body.substring(start + 1, end).split(",")
            if (fields.size < 4) return null
            val pct = fields[3].trim().toFloat()
            Result(pct, "sina")
        } catch (_: Exception) {
            null
        }
    }

    private fun httpGet(urlStr: String, referer: String?): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            requestMethod = "GET"
            setRequestProperty("User-Agent", UA)
            if (referer != null) {
                setRequestProperty("Referer", referer)
            }
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                ?: throw IllegalStateException("HTTP $code")
            // Sina often returns GBK
            val charset = if (urlStr.contains("sinajs")) {
                Charset.forName("GBK")
            } else {
                Charsets.UTF_8
            }
            return BufferedReader(InputStreamReader(stream, charset)).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
