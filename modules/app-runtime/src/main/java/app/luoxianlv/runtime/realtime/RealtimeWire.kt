package app.luoxianlv.runtime.realtime

import org.json.JSONObject
import org.json.JSONTokener

/** 在递归解析前限制字节和嵌套；不把远端文本或授权字段写入诊断。 */
internal object RealtimeWire {
    const val MAX_BYTES = 16_384
    private val id = Regex("[A-Za-z0-9._:-]{1,96}")

    fun validId(value: String) = id.matches(value)

    fun objectValue(text: String): JSONObject {
        require(text.length <= MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        var depth = 0
        var quoted = false
        var escaped = false
        text.forEach { c ->
            if (quoted) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else
                when (c) {
                    '"' -> quoted = true
                    '{',
                    '[' -> {
                        depth++
                        require(depth <= 16)
                    }
                    '}',
                    ']' -> {
                        depth--
                        require(depth >= 0)
                    }
                }
        }
        require(!quoted && depth == 0)
        val parser = JSONTokener(text)
        val result = parser.nextValue()
        require(result is JSONObject && parser.nextClean() == '\u0000')
        return result
    }

    fun positive(value: String, zero: Boolean = false): Long {
        require(value.length <= 19 && value.isNotEmpty() && value.all { it in '0'..'9' })
        return value.toLong().also { require(if (zero) it >= 0 else it > 0) }
    }

    fun encode(epoch: String, id: String, topic: String, type: String, data: JSONObject): String =
        JSONObject()
            .put("protocolVersion", 1)
            .put("messageId", id)
            .put("connectionEpoch", epoch)
            .put("topic", topic)
            .put("type", type)
            .put("data", data)
            .toString()
}
