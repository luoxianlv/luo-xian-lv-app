package app.luoxianlv.ui.home

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.luoxianlv.ui.components.decodeSampleSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 顶部插画候选资源名，按序取第一个能解码的。
 *
 * 多扩展名是为了能直接丢一张现成的图进来，不用改名。
 *
 * ⚠️ 当前 `hero_home.png` 是临时占位：拷自参考项目 QEdge 的 `logo.png`， 属于第三方美术，**正式发布前必须换掉**（换成自制插画或买断素材）。
 * 文件不存在时自动回退到渐变插画位，删掉图片不会编译失败。
 */
private val HERO_ASSETS = listOf("hero_home.webp", "hero_home.png", "hero_home.jpg")

/**
 * 顶部插画。
 *
 * 结构抄自参考实现的 `HomeHero`：两个半透明装饰圆 + 居中图标。 底不铺自己的渐变 —— 外层内容卡整卡铺着同一支渐变（见 HomeScreen 的
 * cardBrush），插画只是坐在渐变上段，彩色自然延伸进下方信息区， 所以这里也不再需要「底部渐变过渡带」。
 *
 * 居中图标用 `shadow(18.dp, CircleShape).clip(CircleShape)`： **不是整块铺满**，而是裁成圆形 + 一圈阴影作边界，四周露出渐变。
 * 我们那张占位图恰好是 512×512 方图，圆裁后正好不被截， 侧边也不会再出现“人像显示不全”。
 */
@Composable
internal fun HomeHero(
    height: Dp = 280.dp,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth().height(height)) {
        // 图标直径：屏宽的 76%、可用高度的 72%、350dp 三者取小（抄参考实现）
        val logoSize = minOf(maxWidth * 0.76f, maxHeight * 0.72f, 350.dp)

        // 两个半透明装饰圆
        Box(
            modifier =
                Modifier.align(Alignment.TopEnd)
                    .padding(top = 28.dp, end = 18.dp)
                    .size(118.dp)
                    .background(Color.White.copy(alpha = 0.14f), CircleShape)
        )
        Box(
            modifier =
                Modifier.align(Alignment.BottomStart)
                    .padding(start = 22.dp, bottom = 70.dp)
                    .size(76.dp)
                    .background(Color.White.copy(alpha = 0.12f), CircleShape)
        )

        // 居中圆形图标：shadow 形成“边框”感，clip 裁圆，四周就是渐变
        val image = rememberHeroImage()
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier.align(Alignment.Center)
                        .size(logoSize)
                        .shadow(18.dp, CircleShape)
                        .clip(CircleShape),
            )
        } else {
            Surface(
                modifier =
                    Modifier.align(Alignment.Center)
                        .size(logoSize)
                        .shadow(18.dp, CircleShape)
                        .clip(CircleShape),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(logoSize * 0.46f),
                    )
                }
            }
        }

        // 底部不再有渐变过渡带：整卡同一片渐变，插画与信息区之间
        // 没有需要「过渡」的断层（原 124dp 落白过渡已随白卡一起撤掉）。
    }
}

/** 读取插画资源；全部候选都不存在或都解码失败时返回 null，由调用方回退到渐变。 */
@Composable
private fun rememberHeroImage(): ImageBitmap? {
    val context = LocalContext.current
    val state =
        produceState<ImageBitmap?>(initialValue = null) {
            value = withContext(Dispatchers.IO) { decodeHeroAsset(context) }
        }
    return state.value
}

private fun decodeHeroAsset(context: Context): ImageBitmap? {
    HERO_ASSETS.forEach { name ->
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val readable = runCatching {
            context.assets.open(name).use { BitmapFactory.decodeStream(it, null, bounds) }
        }
            .isSuccess
        if (!readable || bounds.outWidth <= 0 || bounds.outHeight <= 0) return@forEach

        val options =
            BitmapFactory.Options().apply {
                inSampleSize = decodeSampleSize(bounds.outWidth, bounds.outHeight)
            }
        val decoded = runCatching {
            context.assets.open(name).use {
                BitmapFactory.decodeStream(it, null, options)?.asImageBitmap()
            }
        }
            .getOrNull()
        if (decoded != null) return decoded
    }
    return null
}
