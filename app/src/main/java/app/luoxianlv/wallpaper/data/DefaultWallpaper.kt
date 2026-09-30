package app.luoxianlv.wallpaper.data

import android.content.Context
import app.luoxianlv.BuildConfig
import app.luoxianlv.business.BusinessJobs
import app.luoxianlv.storage.AppStorage
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 默认项目按需下载，完整校验后原子安装；保留旧版已解压的同一项目。 */
object DefaultWallpaper {
    const val ID = "34518073-0800-4000-8000-000000000001"
    const val TITLE = "世界很温柔 · 上杉绘梨衣"
    private val downloadLock = Mutex()

    fun folder(context: Context) = File(AppStorage.wallpapers(context), ID)

    fun installed(context: Context) =
        AppStorage.wallpaperRoots(context).any {
            File(File(it, ID), ".root").isFile
        }

    private fun prefs(context: Context) =
        context.getSharedPreferences("practice_wallpaper", Context.MODE_PRIVATE)

    fun shouldOffer(context: Context) =
        !installed(context) &&
            WallpaperProjectStore.current(context) == null &&
            !prefs(context).getBoolean("skip_default_download", false)

    fun stopOffering(context: Context) {
        prefs(context).edit().putBoolean("skip_default_download", true).apply()
    }

    suspend fun download(context: Context, onProgress: (Long, Long) -> Unit) = BusinessJobs.io {
        downloadLock.withLock {
            if (installed(context)) return@withLock
            val job = currentCoroutineContext()
            val manifestConnection =
                open("${BuildConfig.UPDATE_BASE_URL.trimEnd('/')}/api/wallpapers/default")
            val metadata =
                try {
                    checkResponse(manifestConnection)
                    val bytes =
                        manifestConnection.inputStream.use { it.readBytesLimited(64 * 1024) }
                    JSONObject(bytes.toString(Charsets.UTF_8))
                } finally {
                    manifestConnection.disconnect()
                }
            val size = metadata.getLong("size")
            val sha = metadata.getString("sha256")
            require(size in 1..256L * 1024 * 1024 && sha.matches(Regex("[0-9a-f]{64}"))) {
                "壁纸下载资料无效"
            }
            val url = URL(metadata.getString("url"))
            require(
                url.protocol == "https" &&
                    url.host == "oss-luoxianlv.admilk.cn" &&
                    url.userInfo == null
            ) {
                "壁纸下载地址无效"
            }
            val archive = File(AppStorage.imports(context), "default-wallpaper.download")
            val destination = folder(context)
            val staging = File(destination.parentFile, ".install-$ID")
            try {
                require(archive.parentFile!!.usableSpace > size + 32L * 1024 * 1024) {
                    "存储空间不足"
                }
                val connection = open(url.toString())
                try {
                    checkResponse(connection)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var received = 0L
                    var lastPercent = -1L
                    connection.inputStream.use { input ->
                        archive.outputStream().use { output ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                job.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                received += count
                                require(received <= size) { "壁纸文件大小异常" }
                                output.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                                val percent = received * 100 / size
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    withContext(Dispatchers.Main) { onProgress(received, size) }
                                }
                            }
                        }
                    }
                    require(
                        received == size &&
                            digest.digest().joinToString("") { "%02x".format(it) } == sha
                    ) {
                        "壁纸文件校验失败，请重试"
                    }
                } finally {
                    connection.disconnect()
                }
                job.ensureActive()
                if (staging.exists()) check(staging.deleteRecursively()) { "无法清理未完成的壁纸" }
                check(staging.mkdirs()) { "无法准备壁纸目录" }
                WallpaperArchive.extract(archive, staging) { job.ensureActive() }
                val projectFile = File(staging, "project.json")
                require(projectFile.isFile && projectFile.length() <= 1024 * 1024) {
                    "壁纸缺少项目资料"
                }
                val project = JSONObject(projectFile.readText(Charsets.UTF_8))
                require(
                    project.optString("type").lowercase(java.util.Locale.ROOT) in
                        setOf("video", "scene", "image", "web", "gif")
                ) {
                    "壁纸类型不支持"
                }
                require(WallpaperArchive.resolve(staging, project.getString("file")) != null) {
                    "壁纸入口文件缺失"
                }
                require(!File(staging, ".root").exists()) { "壁纸包含保留文件" }
                File(staging, ".root").writeText("", Charsets.UTF_8)
                job.ensureActive()
                if (installed(context)) return@withLock
                // 本下载器只接管固定默认项目目录，不覆盖用户导入的其他项目。
                if (destination.exists()) check(destination.deleteRecursively()) { "无法清理未完成的默认壁纸" }
                check(staging.renameTo(destination)) { "无法安装壁纸" }
                app.luoxianlv.debug.AppLog.i("壁纸", "默认壁纸下载完成，已校验并安装")
            } finally {
                archive.delete()
                staging.deleteRecursively()
            }
        }
    }

    private fun open(url: String) =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            instanceFollowRedirects = false
            setRequestProperty("Accept-Encoding", "identity")
        }

    private fun checkResponse(connection: HttpURLConnection) {
        check(connection.responseCode != 429) { "下载请求过于频繁，请稍后再试" }
        check(connection.responseCode == 200) { "壁纸暂时无法下载，请稍后重试" }
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit) { "壁纸下载资料过大" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
