package app.luoxianlv

import app.luoxianlv.update.ClientVersion
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClientVersionTest {
    private fun connection(value: String) =
        object : HttpURLConnection(URL(value)) {
            override fun connect() = Unit

            override fun disconnect() = Unit

            override fun usingProxy() = false
        }

    @Test
    fun attachesVersionOnlyToConfiguredApiOrigin() {
        val request =
            connection("${BuildConfig.UPDATE_BASE_URL}/api/wallpapers/default?delivery=edgeone")
        ClientVersion.attach(request)
        assertEquals(
            BuildConfig.VERSION_CODE.toString(),
            request.getRequestProperty("X-App-Version-Code"),
        )
        assertEquals(BuildConfig.VERSION_NAME, request.getRequestProperty("X-App-Version-Name"))
    }

    @Test
    fun neverForwardsVersionHeadersToDownloadHostsOrDifferentOrigin() {
        val origin = URI(BuildConfig.UPDATE_BASE_URL)
        val alternatePort = if (origin.port == 8443) 9443 else 8443
        val alternateScheme = if (origin.scheme == "https") "http" else "https"
        for (url in
            listOf(
                "https://oss-eo.luoxianlv.cn/wallpaper.zip?auth_key=fixture",
                "https://oss-luoxianlv.admilk.cn/wallpaper.zip?signature=fixture",
                "${origin.scheme}://${origin.host}:$alternatePort/api/wallpapers/default",
                "$alternateScheme://${origin.rawAuthority}/api/wallpapers/default",
                "${origin.scheme}://${origin.host}.attacker.example/api/wallpapers/default",
            )) {
            val request = connection(url)
            ClientVersion.attach(request)
            assertNull(request.getRequestProperty("X-App-Version-Code"))
            assertNull(request.getRequestProperty("X-App-Version-Name"))
        }
    }
}
