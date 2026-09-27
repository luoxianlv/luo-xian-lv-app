package app.luoxianlv.ui.practice

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import java.io.File
import java.nio.ByteBuffer

/** Bounded preview decode shared by the picker and the stage's first frame. */
object WallpaperPreview {
    fun load(context: Context, root: File?): Drawable? = runCatching {
        val file = root?.let(WallpaperProjectStore::preview)
        val bytes = if (file != null && file.length() <= 16L * 1024 * 1024) file.readBytes()
        else context.assets.open(if (WallpaperProjectStore.hasBundled(context))
            "default-wallpaper/preview.gif" else "practice-sunset.jpg").use { it.readBytes() }
        if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            val scale = minOf(1f, 960f / maxOf(info.size.width,info.size.height))
            decoder.setTargetSize(maxOf(1,(info.size.width*scale).toInt()),maxOf(1,(info.size.height*scale).toInt()))
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
            val options = BitmapFactory.Options().apply {
                while (maxOf(bounds.outWidth,bounds.outHeight)/inSampleSize > 960) inSampleSize *= 2
            }
            BitmapDrawable(context.resources,BitmapFactory.decodeByteArray(bytes,0,bytes.size,options))
        }
    }.getOrNull()
}
