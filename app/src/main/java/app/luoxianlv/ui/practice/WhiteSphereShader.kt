package app.luoxianlv.ui.practice

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi

/** 液态能量表面，仅在白色核心包围区域计算。 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class WhiteSphereShader(context: android.content.Context) {
    private val strength = app.luoxianlv.business.ui.OfficialRuntimeConfig.read(context).shaderStrength
    private val shader =
        RuntimeShader(
            app.luoxianlv.hot.contract.OfficialAssets.text(context, "shaders", "white-sphere.agsl", "shaders/white-sphere.agsl", 65536)
        )
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = this@WhiteSphereShader.shader }

    fun draw(canvas: Canvas, x: Float, y: Float, radius: Float, opacity: Float, time: Float) {
        if (radius < .1f) return
        shader.setFloatUniform("center", x, y)
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("opacity", opacity * strength)
        shader.setFloatUniform("time", time)
        val extent = radius * 1.5f + 2
        canvas.drawRect(x - extent, y - extent, x + extent, y + extent, paint)
    }
}
