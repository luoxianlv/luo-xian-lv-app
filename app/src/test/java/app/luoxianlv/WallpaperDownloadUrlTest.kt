package app.luoxianlv

import app.luoxianlv.wallpaper.data.validatedWallpaperDownloadUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WallpaperDownloadUrlTest {
    @Test
    fun acceptsOfficialEdgeOneAndLegacyOssWithoutChangingSignature() {
        for (host in listOf("oss-eo.luoxianlv.cn", "oss-luoxianlv.admilk.cn")) {
            val url =
                "https://$host/luoxianlv/wallpapers/fixture/default.zip?auth_key=1-2-0-a%2Bb&x=%2F"
            assertEquals(url, validatedWallpaperDownloadUrl(url))
        }
    }

    @Test
    fun rejectsUnknownOriginAndUrlAmbiguities() {
        for (url in
            listOf(
                "http://oss-eo.luoxianlv.cn/default.zip",
                "https://oss-eo-test.luoxianlv.cn/default.zip",
                "https://oss-eo.luoxianlv.cn.attacker.example/default.zip",
                "https://oss-eo.luoxianlv.cn./default.zip",
                "https://user@oss-eo.luoxianlv.cn/default.zip",
                "https://oss-eo.luoxianlv.cn:443/default.zip",
                "https://oss-eo.luoxianlv.cn:8443/default.zip",
                "https://oss-eo.luoxianlv.cn/default.zip#fragment",
                "//oss-eo.luoxianlv.cn/default.zip",
                "/default.zip",
                "https://oss-eo.luoxianlv.cn/default.zip?bad=%",
            )) {
            val error =
                assertThrows(IllegalArgumentException::class.java) {
                    validatedWallpaperDownloadUrl(url)
                }
            assertEquals("壁纸下载地址无效", error.message)
        }
    }
}
