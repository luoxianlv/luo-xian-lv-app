package app.luoxianlv.platform

import app.luoxianlv.hot.contract.SharedRealtime
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*

/** 仅 Debug：在真实 Android 引擎上验证回环 IO，不使用用户账号或外网。 */
object NetworkProbe {
    /** 显式 opt-in：读取本项目公开健康接口，不发送账号、Cookie 或安装凭据。 */
    @JvmStatic
    fun verifyTls(fixture: ByteArray, completion: SharedRealtime.Completion) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                withTimeout(20000) {
                    BusinessHttp(
                            HttpClients(::newBusinessClient),
                            "https://www.luoxianlv.cn",
                            "Luoxianlv/test",
                        )
                        .use { http ->
                            check(
                                http
                                    .bytes("https://www.luoxianlv.cn/health", limit = 4096)
                                    .isNotEmpty()
                            )
                        }
                }
                TlsProbe.check(fixture)
                completion.complete(true, "Ktor CIO 系统信任链 HTTPS 通过；临时证书对照验证自签名拒绝和域名不匹配拒绝")
            } catch (failure: Throwable) {
                completion.complete(false, failure.javaClass.simpleName + ": " + failure.message)
            } finally {
                scope.cancel()
            }
        }
    }

    @JvmStatic
    fun verify(completion: SharedRealtime.Completion) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                Peer().use { peer ->
                    val origin = "http://127.0.0.1:${peer.server.localPort}"
                    BusinessHttp(
                            HttpClients(::newBusinessClient),
                            origin,
                            "Luoxianlv/test",
                            allowLocalHttp = true,
                        )
                        .use { http ->
                            check(
                                http
                                    .bytes("$origin/ok", "local-test-token")
                                    .toString(Charsets.UTF_8) == "中文响应"
                            )
                            for (path in listOf("/large", "/large-burst")) repeat(5) {
                                check(
                                    http
                                        .bytes("$origin$path", limit = Peer.LARGE.size)
                                        .contentEquals(Peer.LARGE)
                                ) {
                                    "二进制响应内容改变"
                                }
                            }
                            check(
                                http
                                    .bytes("$origin/redirect", "local-test-token")
                                    .toString(Charsets.UTF_8) == "中文响应"
                            )
                            check(peer.crossOriginAuth.get() == 0) { "重定向泄漏凭据" }
                            check(
                                runCatching { http.bytes("$origin/post", "local-test-token", "{}") }
                                    .isFailure
                            )
                            check(peer.posts.get() == 1) { "写请求重复发送" }
                            supervisorScope {
                                val downloading = async {
                                    http.bytes("$origin/slow", limit = 2 * 1024 * 1024)
                                }
                                withTimeout(5000) { while (peer.slowStarted.get() == 0) delay(10) }
                                downloading.cancelAndJoin()
                                withTimeout(5000) { while (peer.slowClosed.get() == 0) delay(20) }
                            }
                            check(http.bytes("$origin/ok").toString(Charsets.UTF_8) == "中文响应")
                        }
                }
                completion.complete(true, "Ktor CIO 实际请求、中文响应、跨域凭据隔离、写请求不重放、取消关闭 IO 通过")
            } catch (failure: Throwable) {
                completion.complete(false, failure.javaClass.simpleName + ": " + failure.message)
            } finally {
                scope.cancel()
            }
        }
    }

    private class Peer : AutoCloseable {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val crossOriginAuth = AtomicInteger()
        val posts = AtomicInteger()
        val slowStarted = AtomicInteger()
        val slowClosed = AtomicInteger()
        private val sockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
        private val worker = Executors.newFixedThreadPool(4)

        init {
            worker.execute {
                while (!server.isClosed) {
                    try {
                        val socket = server.accept()
                        sockets.add(socket)
                        worker.execute { serve(socket) }
                    } catch (_: java.io.IOException) {
                        break
                    }
                }
            }
        }

        private fun serve(socket: Socket) {
            var slow = false
            try {
                socket.use {
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val request = input.readLine() ?: return
                    val headers = linkedMapOf<String, String>()
                    var total = request.length
                    while (true) {
                        val line = input.readLine() ?: return
                        total += line.length
                        check(total < 8192)
                        if (line.isEmpty()) break
                        val split = line.indexOf(':')
                        if (split > 0)
                            headers[line.substring(0, split).lowercase()] =
                                line.substring(split + 1).trim()
                    }
                    val path = request.split(' ')[1]
                    val out = socket.getOutputStream()
                    if (
                        headers["host"]?.startsWith("localhost:") == true &&
                            headers.containsKey("authorization")
                    )
                        crossOriginAuth.incrementAndGet()
                    when (path) {
                        "/large",
                        "/large-burst" -> {
                            out.write(
                                "HTTP/1.1 200 OK\r\nContent-Length: ${LARGE.size}\r\nConnection: close\r\n\r\n"
                                    .toByteArray(Charsets.US_ASCII)
                            )
                            var offset = 0
                            while (offset < LARGE.size) {
                                val count =
                                    minOf(
                                        if (path == "/large-burst") LARGE.size else 8137,
                                        LARGE.size - offset,
                                    )
                                out.write(LARGE, offset, count)
                                out.flush()
                                offset += count
                                if (path != "/large-burst") Thread.sleep(1)
                            }
                        }
                        "/redirect",
                        "/post" -> {
                            if (path == "/post") posts.incrementAndGet()
                            out.write(
                                "HTTP/1.1 302 Found\r\nLocation: http://localhost:${server.localPort}/ok\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                    .toByteArray(Charsets.US_ASCII)
                            )
                            out.flush()
                        }
                        "/slow" -> {
                            slow = true
                            slowStarted.incrementAndGet()
                            out.write(
                                "HTTP/1.1 200 OK\r\nContent-Length: 1048576\r\nConnection: close\r\n\r\n"
                                    .toByteArray(Charsets.US_ASCII)
                            )
                            out.flush()
                            repeat(1024) {
                                out.write(ByteArray(1024))
                                out.flush()
                                Thread.sleep(10)
                            }
                        }
                        else -> {
                            val bytes = "中文响应".toByteArray(Charsets.UTF_8)
                            out.write(
                                "HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                                    .toByteArray(Charsets.US_ASCII)
                            )
                            out.write(bytes)
                            out.flush()
                        }
                    }
                }
            } catch (_: Exception) {
                if (slow) slowClosed.incrementAndGet()
            } finally {
                sockets.remove(socket)
            }
        }

        override fun close() {
            server.close()
            sockets.forEach { runCatching { it.close() } }
            worker.shutdownNow()
        }

        companion object {
            val LARGE = ByteArray(123_692) { (it * 71 + it / 257).toByte() }
        }
    }
}
