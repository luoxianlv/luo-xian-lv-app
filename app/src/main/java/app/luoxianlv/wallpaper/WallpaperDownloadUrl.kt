package app.luoxianlv.wallpaper

import java.net.URI
import java.util.Locale

/** 仅接收正式壁纸入口，保留旧版 OSS 链接；临时签名原样传递。 */
internal fun validatedWallpaperDownloadUrl(value: String): String {
    val uri = runCatching { URI(value) }.getOrNull()
    require(
        uri != null &&
            uri.scheme.equals("https", ignoreCase = true) &&
            uri.host?.lowercase(Locale.ROOT) in
                setOf("oss-eo.luoxianlv.cn", "oss-luoxianlv.admilk.cn") &&
            uri.port == -1 &&
            uri.userInfo == null &&
            uri.rawFragment == null
    ) {
        "壁纸下载地址无效"
    }
    return uri.toString()
}
