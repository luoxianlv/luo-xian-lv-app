package app.luoxianlv.ui.wallpaper

import android.graphics.drawable.Animatable
import android.widget.ImageView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.luoxianlv.ui.theme.OnBackdropContent
import app.luoxianlv.wallpaper.data.WallpaperProjectStore
import app.luoxianlv.wallpaper.render.WallpaperPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun WallpaperCard(
    entry: WallpaperProjectStore.Entry,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val context = LocalContext.current
    Surface(
        onClick = onSelect,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = if (selected) BorderStroke(1.dp, OnBackdropContent.copy(alpha = .35f)) else null,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            var preview by
                remember(entry.id) { mutableStateOf<android.graphics.drawable.Drawable?>(null) }
            LaunchedEffect(entry.id) {
                preview =
                    withContext(Dispatchers.IO) {
                        WallpaperPreview.load(
                            context,
                            entry.root,
                            (112 * context.resources.displayMetrics.density).toInt(),
                        )
                    }
            }
            Box(
                Modifier.size(112.dp, 70.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clipToBounds()
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                AndroidView(
                    factory = {
                        ImageView(it).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            contentDescription = "${entry.title}预览"
                            cropToPadding = true
                        }
                    },
                    modifier = Modifier.matchParentSize().clipToBounds(),
                    update = { view ->
                        if (view.drawable !== preview) {
                            (view.drawable as? Animatable)?.stop()
                            view.setImageDrawable(preview)
                            (preview as? Animatable)?.start()
                        }
                    },
                    onRelease = {
                        (it.drawable as? Animatable)?.stop()
                        it.setImageDrawable(null)
                    },
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (selected) "使用中" else if (entry.id == null) "默认壁纸" else "已导入",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected)
                Icon(
                    Icons.Default.CheckCircle,
                    "已选择",
                    modifier = Modifier.size(20.dp),
                    tint = OnBackdropContent,
                )
            else Spacer(Modifier.size(20.dp))
        }
    }
}
