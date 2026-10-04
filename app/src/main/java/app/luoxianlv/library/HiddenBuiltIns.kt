package app.luoxianlv.library

import org.json.JSONArray

/**
 * 内置谱面隐藏清单的编解码。
 *
 * 抽成文件级纯函数是为了能单测：清单存在 prefs 里，写坏一次就等于 内置示例谱面一起消失，或者删掉的又回来。
 */
fun parseHiddenBuiltIns(raw: String?): Set<String> {
    val array = runCatching { JSONArray(raw ?: "[]") }.getOrDefault(JSONArray())
    return (0 until array.length())
        .mapNotNull { index -> array.optString(index).takeIf(String::isNotBlank) }
        .toSet()
}

fun hiddenBuiltInsJson(ids: Set<String>): String {
    val array = JSONArray()
    ids.forEach(array::put)
    return array.toString()
}
