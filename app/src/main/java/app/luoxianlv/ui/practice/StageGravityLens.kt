package app.luoxianlv.ui.practice

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi

/** 直接扭曲实际背景，不读回截图，也不绘制装饰圆环。 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class StageGravityLens(private val backdrop: View) {
    private val strengthScale = app.luoxianlv.business.ui.OfficialRuntimeConfig.read(backdrop.context).shaderStrength
    private val shader =
        RuntimeShader(
            app.luoxianlv.hot.contract.OfficialAssets.text(backdrop.context, "shaders", "gravity-lens.agsl", "shaders/gravity-lens.agsl", 65536)
        )
    private val effect = RenderEffect.createRuntimeShaderEffect(shader, "background")

    fun update(progress: Float) {
        if (backdrop.width <= 0 || backdrop.height <= 0) return
        val strength = (1 - StageLightRenderer.smooth(.57f, .78f, progress)) * strengthScale
        if (strength <= 0) {
            clear()
            return
        }
        shader.setFloatUniform("center", backdrop.width / 2f, backdrop.height / 2f)
        shader.setFloatUniform("radius", minOf(backdrop.width, backdrop.height) * .19f)
        shader.setFloatUniform("strength", strength)
        backdrop.setRenderEffect(effect)
    }

    fun clear() {
        backdrop.setRenderEffect(null)
    }
}
