package app.luoxianlv.platform

import org.json.JSONObject

/** Response compatibility and defaults; no HTTP or account state. */
internal class PlatformResponseParser(private val baseUrl: String) {
    fun parsePlatformScores(root: JSONObject): List<PlatformScore> {
        val items = root.optJSONArray("items") ?: org.json.JSONArray()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").trim()
            if (id.isBlank()) return@mapNotNull null
            PlatformScore(
                id,
                item.optString(
                    "title",
                    "未命名谱子",
                ),
                item.optString("author", "落弦律用户"),
                item.optInt("bpm", 120).coerceIn(1, 999),
                item.optString("keySignature").ifBlank {
                    null
                },
                item.optString("notationText"),
            )
        }
    }

    /** 服务端编译自带 HTTP 超时，失败会抛出带说明的异常交给调用方展示。 */
    private fun resolve(value: String): String =
        if (value.startsWith("http://") || value.startsWith("https://")) {
            value
        } else {
            "$baseUrl/${value.trimStart('/')}"
        }

    fun parseSyncedScore(item: JSONObject?): SyncedScore? {
        if (item == null) return null
        val id = firstString(item, "scoreId", "score_id", "id") ?: return null
        val title = firstString(item, "title", "name") ?: "未命名谱子"
        val notation = firstString(item, "notationText", "notation", "score", "content") ?: ""
        val version = firstLong(item, "contentVersion", "content_version", "version")
        val key = firstString(item, "keySignature", "key_signature")
        val contentUrl = firstString(item, "contentUrl", "content_url", "url")?.let(::resolve)
        return SyncedScore(
            id = id,
            title = title,
            contentVersion = version,
            notationText = notation,
            bpm = item.optInt("bpm", 120).coerceIn(1, 999),
            keySignature = key,
            contentUrl = contentUrl,
            updatedAt = firstString(item, "updatedAt", "updated_at"),
        )
    }

    fun parseLibraryPage(payload: String): Pair<org.json.JSONArray, String?> {
        if (payload.startsWith("[")) return org.json.JSONArray(payload) to null
        val root = JSONObject(payload)
        val items =
            root.optJSONArray("items") ?: root.optJSONArray("scores") ?: org.json.JSONArray()
        val next = firstString(root, "nextCursor", "next_cursor", "cursor")
        return items to next
    }

    private fun firstString(
        item: JSONObject,
        vararg names: String,
    ): String? =
        names
            .asSequence()
            .mapNotNull { name -> item.optString(name, "").trim().ifBlank { null } }
            .firstOrNull()

    private fun firstLong(
        item: JSONObject,
        vararg names: String,
    ): Long =
        names
            .asSequence()
            .mapNotNull { name ->
                val raw = item.opt(name)
                when (raw) {
                    is Number -> raw.toLong()
                    is String -> raw.trim().toLongOrNull()
                    else -> null
                }
            }
            .firstOrNull { it > 0 } ?: 0L
}
