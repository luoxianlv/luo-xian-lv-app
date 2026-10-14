package app.luoxianlv.runtime.realtime

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import org.json.JSONObject

/** 连续分片才可组成结果；确认窗口由连接线程推进，正文不会进入页面回调。 */
internal class QueryChunks {
    private val output = ByteArrayOutputStream()
    private var metadata: Triple<Int, String, Long>? = null
    private var total = -1
    val offset: Int
        get() = output.size()

    fun accept(part: JSONObject): String? {
        val size = part.getInt("total")
        require(size in 1..MAX_BYTES && part.getInt("offset") == offset)
        val header =
            Triple(part.getInt("status"), part.getString("time"), part.getLong("retryAfterMillis"))
        require(header.first in 100..599 && header.second.matches(Regex("[0-9]{1,12}")))
        require(header.third in 0..3_600_000)
        if (metadata == null) {
            metadata = header
            total = size
        }
        require(metadata == header && total == size)
        val bytes = Base64.getDecoder().decode(part.getString("bytes"))
        require(bytes.isNotEmpty() && bytes.size <= 8192 && offset + bytes.size <= total)
        require(part.getBoolean("done") == (offset + bytes.size == total))
        require(part.getBoolean("done") || bytes.size == 8192)
        output.write(bytes)
        if (offset != total) return null
        val text =
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(output.toByteArray()))
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
    }
}
