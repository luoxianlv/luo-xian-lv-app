package app.luoxianlv.update

import java.net.URI
import org.json.JSONObject

/**
 * 一个下载渠道。官方渠道（oss）用 manifest 顶层签名链接， GitHub 渠道走服务端代理 `/api/update/github`（302 到 release asset）。
 * [sha256] / [size] 取渠道自己的校验值；老 manifest 没给时回退到顶层 apkSha256 / apkSize。
 */
data class UpdateSource(
    val id: String,
    val url: String,
    val sha256: String = "",
    val size: Long = 0,
) {
    val label: String
        get() = if (id == "github") "GitHub 渠道" else "官方渠道"
}

data class AppRelease(
    val versionCode: Int,
    val versionName: String,
    val sha256: String,
    val size: Long,
    val notes: List<ReleaseNoteSection>,
    val mandatory: Boolean,
    val sources: List<UpdateSource>,
    val deliveryJson: String? = null,
    val certificateSha256: String? = null,
)

data class ReleaseNoteSection(
    val title: String,
    val items: List<String>,
)

private fun parseReleaseNotes(value: Any?): List<ReleaseNoteSection> {
    if (value is org.json.JSONArray) {
        val items = List(value.length()) { value.optString(it).trim() }.filter(String::isNotBlank)
        return if (items.isEmpty()) emptyList() else listOf(ReleaseNoteSection("更新内容", items))
    }
    val root = value as? JSONObject ?: return emptyList()
    val sections = root.optJSONArray("sections") ?: return emptyList()
    return List(sections.length()) { index ->
            val section = sections.optJSONObject(index) ?: JSONObject()
            val title = section.optString("title").trim().ifBlank { "更新内容" }
            val items =
                section
                    .optJSONArray("items")
                    ?.let { array ->
                        List(array.length()) { array.optString(it).trim() }
                            .filter(String::isNotBlank)
                    }
                    .orEmpty()
            ReleaseNoteSection(title, items)
        }
        .filter { it.items.isNotEmpty() }
}

/** 仅接受更高版本；各渠道链接必须指向同一签名安装包。 */
fun parseAppRelease(
    json: JSONObject,
    currentCode: Int,
    baseUrl: String,
    preferred: String,
    allowLocal: Boolean,
): AppRelease? {
    if (!json.optBoolean("enabled", true)) return null
    val code = json.getInt("latestVersionCode")
    if (code <= currentCode) return null
    val sha = json.getString("apkSha256").lowercase()
    val delivery =
        if (json.has("deliveryV1")) {
            requireNotNull(json.optJSONObject("deliveryV1")) { "增量更新说明格式无效" }
        } else null
    require(sha.matches(Regex("[a-f0-9]{64}"))) { "更新信息缺少有效的安装包校验值" }
    val sources = mutableListOf<UpdateSource>()
    val channels = json.optJSONObject("channels")
    val topSize = json.optLong("apkSize", 0)
    if (delivery != null) require(topSize in 1..512L * 1024 * 1024) { "签名更新缺少有效的安装包大小" }
    for (id in listOf(preferred, "oss", "github").distinct()) {
        val channel = channels?.optJSONObject(id) ?: continue
        val url = channel.optString("url").ifBlank { null } ?: continue
        if (delivery != null) {
            if (channel.has("sha256"))
                require(channel.optString("sha256").lowercase() == sha) {
                    "下载渠道与签名目标不一致"
                }
            if (channel.has("size"))
                require(channel.optLong("size", -1) == topSize) {
                    "下载渠道与签名目标不一致"
                }
        }
        val channelSha =
            channel.optString("sha256").lowercase().takeIf { it.matches(Regex("[a-f0-9]{64}")) }
                ?: sha
        val channelSize = channel.optLong("size", 0).takeIf { it > 0 } ?: topSize
        if (delivery != null)
            require(channelSha == sha && channelSize == topSize) {
                "下载渠道与签名目标不一致"
            }
        sources +=
            UpdateSource(id, validatedUpdateUrl(url, baseUrl, allowLocal), channelSha, channelSize)
    }
    if (sources.isEmpty())
        sources +=
            UpdateSource(
                "oss",
                validatedUpdateUrl(json.getString("apkUrl"), baseUrl, allowLocal),
                sha,
                topSize,
            )
    val notes = parseReleaseNotes(json.opt("releaseNotes"))
    return AppRelease(
        code,
        json.getString("latestVersionName"),
        sha,
        json.optLong("apkSize", 0),
        notes,
        true,
        sources,
        delivery?.toString(),
        json.optString("apkCertificateSha256").takeIf { it.matches(Regex("[a-f0-9]{64}")) },
    ) // 检测到更高版本后必须更新，不允许跳过。
}

fun validatedUpdateUrl(value: String, baseUrl: String, allowLocal: Boolean): String {
    val uri = URI(baseUrl.trimEnd('/') + "/").resolve(value)
    val local = allowLocal && uri.host in listOf("127.0.0.1", "localhost", "10.0.2.2")
    require(
        uri.host != null &&
            uri.userInfo == null &&
            (uri.scheme == "https" || (local && uri.scheme == "http"))
    ) {
        "更新下载地址必须使用 HTTPS"
    }
    return uri.toString()
}
