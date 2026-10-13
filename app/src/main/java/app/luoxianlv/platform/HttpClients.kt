package app.luoxianlv.platform

import io.ktor.client.HttpClient
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** CIO 的 Android 证书策略绑定域名；最多八个请求和八份客户端，退役时一起关闭。 */
internal class HttpClients(
    private val create: (String) -> HttpClient,
    private val fixed: HttpClient? = null,
) : AutoCloseable {
    constructor(client: HttpClient) : this({ client }, client)

    private class Entry(val client: HttpClient, var active: Int = 0)

    private val entries = LinkedHashMap<String, Entry>(8, 0.75f, true)
    private val permits = Semaphore(8)
    private var closed = false

    suspend fun <T> use(host: String, block: suspend (HttpClient) -> T): T = permits.withPermit {
        val entry =
            synchronized(entries) {
                if (closed) throw CancellationException("本代网络请求已停止")
                val key = if (fixed != null) "" else host.lowercase(Locale.ROOT)
                (entries[key]
                        ?: run {
                            if (entries.size >= 8) {
                                val idle = entries.entries.first { it.value.active == 0 }
                                entries.remove(idle.key)
                                idle.value.client.close()
                            }
                            Entry(create(host)).also { entries[key] = it }
                        })
                    .also { it.active++ }
            }
        try {
            block(entry.client)
        } finally {
            synchronized(entries) { entry.active-- }
        }
    }

    override fun close() {
        val clients =
            synchronized(entries) {
                if (closed) return
                closed = true
                (entries.values.map { it.client } + listOfNotNull(fixed)).distinct().also {
                    entries.clear()
                }
            }
        clients.forEach {
            it.coroutineContext.cancel()
            it.close()
        }
    }
}
