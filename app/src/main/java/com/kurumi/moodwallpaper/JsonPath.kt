package com.kurumi.moodwallpaper

import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal JSON path: dot-separated keys with optional [index],
 * e.g. `data.f170`, `data.items[0].pct`.
 */
object JsonPath {
    fun readNumber(root: Any, path: String): Double {
        val cleaned = path.trim().trimStart('$', '.')
        if (cleaned.isEmpty()) throw IllegalArgumentException("JSON 路径为空")
        var cur: Any? = root
        val tokens = tokenize(cleaned)
        for (tok in tokens) {
            cur = when (tok) {
                is Tok.Key -> {
                    val o = cur as? JSONObject
                        ?: throw IllegalArgumentException("路径 '${tok.name}' 需要对象，实际=${typeName(cur)}")
                    if (!o.has(tok.name) || o.isNull(tok.name)) {
                        throw IllegalArgumentException("缺少字段 '${tok.name}'")
                    }
                    o.get(tok.name)
                }
                is Tok.Index -> {
                    val a = cur as? JSONArray
                        ?: throw IllegalArgumentException("路径 '[${tok.i}]' 需要数组，实际=${typeName(cur)}")
                    if (tok.i < 0 || tok.i >= a.length()) {
                        throw IllegalArgumentException("数组下标越界 [${tok.i}] (len=${a.length()})")
                    }
                    a.get(tok.i)
                }
            }
        }
        return when (cur) {
            is Number -> cur.toDouble()
            is String -> cur.trim().toDoubleOrNull()
                ?: throw IllegalArgumentException("无法把 '$cur' 解析为数字")
            else -> throw IllegalArgumentException("路径结果不是数字 (${typeName(cur)})")
        }
    }

    private fun typeName(v: Any?): String = when (v) {
        null -> "null"
        is JSONObject -> "object"
        is JSONArray -> "array"
        else -> v.javaClass.simpleName
    }

    private sealed class Tok {
        data class Key(val name: String) : Tok()
        data class Index(val i: Int) : Tok()
    }

    private fun tokenize(path: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        while (i < path.length) {
            when (path[i]) {
                '.' -> i++
                '[' -> {
                    val end = path.indexOf(']', i)
                    if (end < 0) throw IllegalArgumentException("未闭合的 [")
                    val idx = path.substring(i + 1, end).trim().toIntOrNull()
                        ?: throw IllegalArgumentException("非法下标 ${path.substring(i, end + 1)}")
                    out.add(Tok.Index(idx))
                    i = end + 1
                }
                else -> {
                    var j = i
                    while (j < path.length && path[j] != '.' && path[j] != '[') j++
                    val name = path.substring(i, j)
                    if (name.isEmpty()) throw IllegalArgumentException("空字段名 near $i")
                    out.add(Tok.Key(name))
                    i = j
                }
            }
        }
        return out
    }
}
