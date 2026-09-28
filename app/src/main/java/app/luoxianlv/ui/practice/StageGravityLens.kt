package app.luoxianlv.ui.practice

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi

/** 直接扭曲实际背景，不读回截图，也不绘制装饰圆环。 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class StageGravityLens(private val backdrop: View) {
    private val shader =
        RuntimeShader(
            """
            uniform shader background;
            uniform float2 center;
            uniform float radius;
            uniform float strength;
            half4 main(float2 xy) {
                float2 delta=xy-center;
                float distance=length(delta);
                float r=distance/max(radius,1.0);
                float field=exp(-pow((r-1.12)/0.40,2.0));
                float envelope=1.0-smoothstep(1.8,2.25,r);
                float bend=radius*0.22*field*envelope*strength;
                float2 direction=delta/max(distance,0.001);
                return background.eval(xy-direction*bend);
            }
            """
                .trimIndent()
        )
    private val effect = RenderEffect.createRuntimeShaderEffect(shader, "background")

    fun update(progress: Float) {
        if (backdrop.width <= 0 || backdrop.height <= 0) return
        val strength = 1 - StageLightRenderer.smooth(.57f, .78f, progress)
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
