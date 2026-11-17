package app.luoxianlv.runtime.realtime

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import org.json.JSONObject

/** 连续分片才可组成结果；确认窗口由连接线程推进，正文不会进入页面回调。 */
internal class QueryChunks {
    private var output = ByteArray(0)
    private var metadata: Triple<Int, String, Long>? = null
    private var total = -1
    var offset = 0
        private set

    fun accept(part: JSONObject): String? {
        val size = part.getInt("total")
        require(size in 1..MAX_BYTES && part.getInt("offset") == offset)
        val header =
            Triple(part.getInt("status"), part.getString("time"), part.getLong("retryAfterMillis"))
        require(header.first in 100..599 && header.second.matches(TIME))
        require(header.third in 0..3_600_000)
        if (metadata == null) {
            metadata = header
            total = size
            // 总长已由首片声明并受上限约束，一次分配，避免逐片扩容与完成时再整体复制。
            output = ByteArray(size)
        }
        require(metadata == header && total == size)
        val bytes = Base64.getDecoder().decode(part.getString("bytes"))
        require(bytes.isNotEmpty() && bytes.size <= 8192 && offset + bytes.size <= total)
        require(part.getBoolean("done") == (offset + bytes.size == total))
        require(part.getBoolean("done") || bytes.size == 8192)
        bytes.copyInto(output, offset)
        offset += bytes.size
        if (offset != total) return null
        val text =
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(output))
                .toString()
        return JSONObject()
            .put("status", header.first)
            .put("time", header.second)
            .put("retryAfterMillis", header.third)
            .put("body", text)
            .toString()
    }

    companion object {
        const val MAX_BYTES = 5 * 1024 * 1024
        private val TIME = Regex("[0-9]{1,12}")
    }
}
