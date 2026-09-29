package app.luoxianlv.ui.wallpaper

import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun WallpaperButton() {
    val context = LocalContext.current
    IconButton(
        onClick = {
            context.startActivity(
                Intent().setClassName(context, "app.luoxianlv.ui.practice.WallpaperPickerActivity")
            )
        }
    ) {
        Icon(Icons.Default.Wallpaper, contentDescription = "选择演练场壁纸")
    }
}
