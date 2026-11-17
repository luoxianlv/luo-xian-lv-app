package app.luoxianlv.platform

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.CertificateException
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.withTimeout

/** 公开测试证书只进入对照客户端，不写入系统信任或生产网络配置。 */
internal object TlsProbe {
    suspend fun check(fixture: ByteArray) =
        withTimeout(20000) {
            val password = "public-test".toCharArray()
            val keys =
                KeyStore.getInstance("PKCS12").apply { load(fixture.inputStream(), password) }
            val executor = Executors.newSingleThreadExecutor()
            val managers =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                    init(keys, password)
                }
            val tls = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
            val server =
                tls.serverSocketFactory.createServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
                    as SSLServerSocket
            try {
                server.enabledProtocols = arrayOf("TLSv1.2")
                executor.execute {
                    repeat(3) {
                        runCatching {
                            server.accept().use { socket ->
                                socket.soTimeout = 5000
                                val input = socket.getInputStream().bufferedReader()
                                var size = 0
                                while (true) {
                                    val line = input.readLine() ?: return@use
                                    size += line.length
                                    check(size < 8192)
                                    if (line.isEmpty()) break
                                }
                                socket.getOutputStream().apply {
                                    write(
                                        "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                                            .toByteArray(Charsets.US_ASCII)
                                    )
                                    flush()
                                }
                            }
                        }
                    }
                }
                val origin = "https://127.0.0.1:${server.localPort}"
                val anchors =
                    KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                        load(null)
                        setCertificateEntry("test", keys.getCertificate("test-local"))
                    }
                val trust =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                        .apply { init(anchors) }
                        .trustManagers
                        .filterIsInstance<X509TrustManager>()
                        .single()
                fun client(name: String) =
                    HttpClient(CIO) {
                        followRedirects = false
                        install(HttpTimeout)
                        engine {
                            https {
                                trustManager = trust
                                serverName = name
                            }
                        }
                    }
                // 先证明同一服务确实能握手并返回 HTTP，网络不通不能算证书拒绝。
                BusinessHttp(HttpClients(client("127.0.0.1")), origin, "Luoxianlv/test").use {
                    check(it.bytes(origin).decodeToString() == "ok")
                }
                val untrusted =
                    runCatching {
                        BusinessHttp(HttpClients(::newBusinessClient), origin, "Luoxianlv/test")
                            .use { it.bytes(origin) }
                    }
                        .exceptionOrNull() ?: error("系统信任意外接受自签名证书")
                check(
                    generateSequence(untrusted) { it.cause }
                        .take(10)
                        .any { it is CertificateException || it is java.io.EOFException }
                ) {
                    "未得到证书拒绝：${untrusted.javaClass.simpleName}"
                }
                val wrong =
                    runCatching {
                        BusinessHttp(HttpClients(client("wrong.invalid")), origin, "Luoxianlv/test")
                            .use { it.bytes(origin) }
                    }
                        .exceptionOrNull() ?: error("错误域名被接受")
                check(
                    generateSequence(wrong) { it.cause }
                        .take(10)
                        .any {
                            it.javaClass.simpleName == "TLSException" || it is java.io.EOFException
                        }
                ) {
                    "未得到域名拒绝：${wrong.javaClass.simpleName}"
                }
            } finally {
                server.close()
                executor.shutdownNow()
            }
        }
}
