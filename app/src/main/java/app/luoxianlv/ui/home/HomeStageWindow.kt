package app.luoxianlv.ui.home

import android.content.Context
import android.content.ContextWrapper
import android.graphics.RectF
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.luoxianlv.ui.practice.StageEntry
import app.luoxianlv.ui.theme.OnBackdropContent

@Composable
internal fun HomeStageWindow(onPractice: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val ink = OnBackdropContent
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    var bounds by remember { mutableStateOf(RectF()) }
    DisposableEffect(context) {
        val activity = context.activity()
        val observer =
            object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                    activity
                        ?.window
                        ?.decorView
                        ?.postDelayed(
                            {
                                if (
                                    owner.lifecycle.currentState.isAtLeast(
                                        androidx.lifecycle.Lifecycle.State.RESUMED
                                    )
                                )
                                    app.luoxianlv.wallpaper.render.PreparedWallpaper.prepare(
                                        activity
                                    )
                            },
                            500,
                        )
                }

                override fun onPause(owner: androidx.lifecycle.LifecycleOwner) {
                    app.luoxianlv.wallpaper.render.PreparedWallpaper.pause()
                }

                override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                    app.luoxianlv.wallpaper.render.PreparedWallpaper.clear()
                }
            }
        activity?.lifecycle?.addObserver(observer)
        onDispose {
            activity?.lifecycle?.removeObserver(observer)
            app.luoxianlv.wallpaper.render.PreparedWallpaper.clear()
        }
    }
    val shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)
    Box(
        modifier
            .width(112.dp)
            .height(60.dp)
            .onGloballyPositioned {
                val r = it.boundsInWindow()
                bounds = RectF(r.left, r.top, r.right, r.bottom)
            }
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    if (dark) listOf(Color(0x8860748B), Color(0x88344A64))
                    else listOf(Color(0xB8FFFFFF), Color(0x85ECF3FA))
                )
            )
            .border(
                1.dp,
                Color.White.copy(alpha = if (dark) .12f else .38f),
                shape,
            )
            .clickable(role = Role.Button, onClickLabel = "进入演练场") {
                val activity = context.activity()
                if (activity == null) onPractice(dark)
                else StageEntry.open(activity, bounds, dark) { onPractice(dark) }
            }
    ) {
        Text(
            "演练场",
            color = ink.copy(alpha = .72f),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

private tailrec fun Context.activity(): ComponentActivity? =
    when (this) {
        is ComponentActivity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }
