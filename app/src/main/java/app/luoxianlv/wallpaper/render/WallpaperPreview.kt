package app.luoxianlv.wallpaper.render

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import app.luoxianlv.wallpaper.data.WallpaperProjectStore
import java.io.File
import java.nio.ByteBuffer

/** 选择器和舞台首帧共用有尺寸上限的预览解码。 */
object WallpaperPreview {
    private var cachedKey: String? = null
    private var cachedBytes: ByteArray? = null

    fun preload(context: Context, root: File?) {
        runCatching { bytes(context, root) }
    }

    @Synchronized
    private fun bytes(context: Context, root: File?): ByteArray {
        val preview = root?.let(WallpaperProjectStore::preview)
        val key =
            preview?.let { "${it.absolutePath}:${it.lastModified()}:${it.length()}" }
                ?: "bundled:${WallpaperProjectStore.hasBundled(context)}"
        if (cachedKey == key)
            cachedBytes?.let {
                return it
            }
        val file = root?.let(WallpaperProjectStore::preview)
        val bytes =
            if (file != null && file.length() <= 16L * 1024 * 1024) file.readBytes()
            else
                context.assets
                    .open(
                        if (WallpaperProjectStore.hasLegacyBundled(context))
                            "default-wallpaper/preview.gif"
                        else "practice-sunset.jpg"
                    )
                    .use { it.readBytes() }
        cachedKey = key
        cachedBytes = bytes
        return bytes
    }

    @Synchronized
    fun clearCache() {
        cachedKey = null
        cachedBytes = null
    }

    fun load(context: Context, root: File?, maxEdge: Int = 960): Drawable? = runCatching {
        val bytes = bytes(context, root)
        if (Build.VERSION.SDK_INT >= 28)
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) {
                decoder,
                info,
                _ ->
                val scale =
                    minOf(
                        1f,
                        maxEdge.coerceAtLeast(1).toFloat() /
                            maxOf(info.size.width, info.size.height),
                    )
                decoder.setTargetSize(
                    maxOf(1, (info.size.width * scale).toInt()),
                    maxOf(1, (info.size.height * scale).toInt()),
                )
            }
        else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val options =
                BitmapFactory.Options().apply {
                    while (
                        maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize >
                            maxEdge.coerceAtLeast(1)
                    ) inSampleSize *= 2
                }
            BitmapDrawable(
                context.resources,
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options),
            )
        }
    }
        .getOrNull()
}
