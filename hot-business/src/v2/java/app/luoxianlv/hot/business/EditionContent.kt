package app.luoxianlv.hot.business

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** 只存在于第二版业务包，用于验收新增原生类与已有主题共同工作。 */
class NextBadgeView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var backgroundColor = 0
    private var foregroundColor = 0

    fun colors(background: Int, foreground: Int) {
        backgroundColor = background
        foregroundColor = foreground
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        paint.color = backgroundColor
        canvas.drawRoundRect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            height / 2f,
            height / 2f,
            paint,
        )
        paint.color = foregroundColor
        paint.textSize = 15f * resources.displayMetrics.scaledDensity
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(
            "保持热爱，继续演奏",
            width / 2f,
            height / 2f - (paint.ascent() + paint.descent()) / 2f,
            paint,
        )
    }
}

@Composable
internal fun EditionContent(modifier: Modifier) {
    val background = MaterialTheme.colorScheme.primary.toArgb()
    val foreground = MaterialTheme.colorScheme.onPrimary.toArgb()
    AndroidView(
        factory = { NextBadgeView(it) },
        modifier = modifier.fillMaxWidth().height(52.dp),
        update = { it.colors(background, foreground) },
    )
}
