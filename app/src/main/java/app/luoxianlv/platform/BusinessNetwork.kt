package app.luoxianlv.platform

import android.net.http.X509TrustManagerExtensions
import app.luoxianlv.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.plugins.HttpTimeout
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CancellationException

/** 单个业务加载器持有有界域名客户端池，退役后不能再创建网络请求。 */
internal object BusinessNetwork {
    @Volatile private var retired = false
    private val transport = lazy {
        BusinessHttp(
            HttpClients(::newBusinessClient),
            BuildConfig.UPDATE_BASE_URL,
            "Luoxianlv/${BuildConfig.VERSION_NAME}",
            mapOf(
                "X-App-Version-Code" to BuildConfig.VERSION_CODE.toString(),
                "X-App-Version-Name" to BuildConfig.VERSION_NAME,
            ),
            BuildConfig.INTERNAL_BUILD,
        )
    }

    @Synchronized
    fun current(): BusinessHttp {
        if (retired) throw CancellationException("本代网络请求已停止")
        return transport.value
    }

    @Synchronized
    fun retire() {
        retired = true
        if (transport.isInitialized()) transport.value.close()
    }
}

/** CIO 避免部分系统 HttpURLConnection 的并发读/关闭异常，禁止流水线和隐式多次连接尝试。 */
internal fun newBusinessClient(host: String) =
    HttpClient(CIO) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout)
        engine {
            https {
                serverName = host
                trustManager = AndroidServerTrust(host)
            }
            maxConnectionsCount = 12
            pipelining = false
            endpoint {
                maxConnectionsPerRoute = 4
                pipelineMaxSize = 1
                connectAttempts = 1
                connectTimeout = 8_000
                keepAliveTime = 15_000
            }
        }
    }

/** 委托 Android 的域名策略和系统根证书；CIO 另外校验实际请求域名的 SAN。 */
private class AndroidServerTrust(private val host: String) : X509TrustManager {
    private val manager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .single()
    private val extensions = X509TrustManagerExtensions(manager)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        extensions.checkServerTrusted(chain, authType, host)
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        manager.checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = manager.acceptedIssuers
}
