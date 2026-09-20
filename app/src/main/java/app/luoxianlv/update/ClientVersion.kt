package app.luoxianlv.update

import app.luoxianlv.BuildConfig
import java.net.HttpURLConnection
import java.net.URI

internal object ClientVersion {
    fun attach(connection: HttpURLConnection) {
        val origin = URI(BuildConfig.UPDATE_BASE_URL)
        val target = connection.url.toURI()
        if (origin.scheme == target.scheme && origin.host == target.host && origin.port == target.port) {
            connection.setRequestProperty("X-App-Version-Code", BuildConfig.VERSION_CODE.toString())
            connection.setRequestProperty("X-App-Version-Name", BuildConfig.VERSION_NAME)
        }
    }
}
