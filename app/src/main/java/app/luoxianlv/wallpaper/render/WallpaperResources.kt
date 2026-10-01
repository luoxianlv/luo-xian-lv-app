package app.luoxianlv.wallpaper.render

import android.content.Context
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import app.luoxianlv.wallpaper.data.WallpaperArchive
import app.luoxianlv.wallpaper.data.WallpaperProjectStore
import app.luoxianlv.hot.contract.OfficialAssets
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

/** 只读离线源，仅允许渲染资源、当前项目和视频分段请求。 */
class WallpaperResources(private val context: Context, private val project: File?) {
    @Volatile var officialFailure: Throwable? = null
        private set
    fun response(request: WebResourceRequest): WebResourceResponse {
        fun denied(code: Int = 404) =
            WebResourceResponse(
                "text/plain",
                "UTF-8",
                code,
                if (code == 416) "Range Not Satisfiable" else "Not Found",
                emptyMap(),
                ByteArrayInputStream(byteArrayOf()),
            )
        val url = request.url
        if (url.scheme != "https" || url.host != "practice.invalid" || request.method != "GET")
            return denied()
        return try {
            val path = url.path.orEmpty()
            val file: File?
            val open: () -> InputStream
            if (
                path in
                    setOf(
                        "/index.html",
                        "/host.mjs",
                        "/webwallgl.mjs",
                        "/compat.mjs",
                        "/clock.mjs",
                        "/scene-video.mjs",
                        "/lifecycle.mjs",
                        "/audio.mjs",
                    )
            ) {
                file = null
                open = { OfficialAssets.open(context, "wallpaperengine", path.removePrefix("/"), "wallpaperengine$path") }
            } else if (path.startsWith("/project/")) {
                val relative = path.removePrefix("/project/")
                if (project == null) {
                    if (relative !in setOf("project.json", "scene.pkg")) return denied()
                    file = null
                    open = { context.assets.open("default-wallpaper/$relative") }
                } else {
                    file =
                        if (relative == "scene.pkg") WallpaperProjectStore.scenePackage(project)
                        else WallpaperArchive.resolve(project, relative)
                    if (file == null) return denied()
                    open = { file.inputStream() }
                }
            } else return denied()
            val extension = path.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
            val mime =
                when (extension) {
                    "js",
                    "mjs" -> "application/javascript"
                    "json" -> "application/json"
                    "wasm" -> "application/wasm"
                    "html",
                    "htm" -> "text/html"
                    else ->
                        MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                            ?: "application/octet-stream"
                }
            val headers =
                mutableMapOf(
                    "Cache-Control" to "no-store",
                    "X-Content-Type-Options" to "nosniff",
                    "Access-Control-Allow-Origin" to "*",
                    "Accept-Ranges" to "bytes",
                )
            var status = 200
            var start = 0L
            var count = file?.length()
            val range =
                request.requestHeaders.entries.firstOrNull { it.key.equals("Range", true) }?.value
            if (range != null && file != null) {
                val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(range) ?: return denied(416)
                val size = file.length()
                val left = match.groupValues[1]
                val right = match.groupValues[2]
                if (left.isEmpty() && right.isEmpty()) return denied(416)
                start =
                    if (left.isEmpty())
                        (size - (right.toLongOrNull() ?: return denied(416))).coerceAtLeast(0)
                    else left.toLongOrNull() ?: return denied(416)
                val end =
                    if (left.isEmpty() || right.isEmpty()) size - 1
                    else (right.toLongOrNull() ?: return denied(416)).coerceAtMost(size - 1)
                if (start !in 0 until size || end < start) return denied(416)
                count = end - start + 1
                status = 206
                headers["Content-Range"] = "bytes $start-$end/$size"
            }
            count?.let { headers["Content-Length"] = it.toString() }
            // WebView 会按 Range 再定位一次，因此不能提前 skip 起点。
            // 它不负责限制有终点的响应体；流从零开始，仅在 end + 1 处截断。
            val input = open()
            val stream =
                count?.let { length ->
                    object : FilterInputStream(input) {
                        var remaining = start + length

                        override fun available() =
                            minOf(super.available().toLong(), remaining).toInt()

                        override fun skip(count: Long): Long =
                            super.skip(count.coerceIn(0, remaining)).also { remaining -= it }

                        override fun read(): Int {
                            if (remaining == 0L) return -1
                            return super.read().also { if (it >= 0) remaining-- }
                        }

                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            if (length == 0) return 0
                            if (remaining == 0L) return -1
                            return `in`
                                .read(buffer, offset, minOf(length.toLong(), remaining).toInt())
                                .also { if (it > 0) remaining -= it }
                        }
                    }
                } ?: input
            WebResourceResponse(
                mime,
                "UTF-8",
                status,
                if (status == 206) "Partial Content" else "OK",
                headers,
                stream,
            )
        } catch (error: Exception) {
            if (url.path.orEmpty().removePrefix("/") in setOf("index.html", "host.mjs", "webwallgl.mjs", "compat.mjs", "clock.mjs", "scene-video.mjs", "lifecycle.mjs", "audio.mjs"))
                if (OfficialAssets.mounted(context, "wallpaperengine")) officialFailure = error
            denied()
        }
    }
}
