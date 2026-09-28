package app.luoxianlv.ui.practice

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi

/** 液态能量表面，仅在白色核心包围区域计算。 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class WhiteSphereShader {
    private val shader =
        RuntimeShader(
            """
            uniform float2 center;
            uniform float radius;
            uniform float opacity;
            uniform float time;
            half4 main(float2 xy) {
                float2 q = (xy - center) / radius;
                // 大尺度移动波瓣改变轮廓，而不仅改变表面光照。
                q.x /= 1.02 + 0.018*sin(time*0.91);
                q.y /= 1.0 + 0.015*cos(time*0.73);
                q += float2(sin(q.y*2.8+time*1.1),cos(q.x*2.6-time*0.8))*0.022;
                float angle = atan(q.y,q.x);
                float contour = 1.0 + 0.050*sin(angle*3.0-time*1.05)
                    + 0.024*sin(angle*5.0+time*0.78)
                    + 0.010*sin(angle*8.0-time*1.31);
                q /= contour;
                float rr = dot(q,q);
                float coverage = 1.0-smoothstep(0.985,1.012,rr);
                if (coverage<=0.0) return half4(0.0);
                float3 sphereNormal = float3(q.x,-q.y,sqrt(max(0.0,1.0-rr)));
                float spin = time*0.23;
                float cs=cos(spin), sn=sin(spin);
                float3 p=float3(cs*sphereNormal.x+sn*sphereNormal.z,
                    sphereNormal.y, -sn*sphereNormal.x+cs*sphereNormal.z);
                float3 k1=float3(3.6,2.8,3.3);
                float3 k2=float3(-3.1,5.1,2.2);
                float3 k3=float3(8.0,-4.5,5.5);
                float a=dot(p,k1)+time*1.0;
                float b=dot(p,k2)-time*1.25+sin(a)*0.55;
                float d=dot(p,k3)+time*0.7;
                float fluid=sin(a)*0.52+sin(b)*0.34+sin(d)*0.14;
                float3 grad=cos(a)*k1*0.52
                    +cos(b)*(k2+cos(a)*k1*0.55)*0.34+cos(d)*k3*0.14;
                grad=float3(cs*grad.x-sn*grad.z,grad.y,sn*grad.x+cs*grad.z);
                grad-=sphereNormal*dot(grad,sphereNormal);
                float3 n=normalize(sphereNormal-grad*0.23);
                float3 light=normalize(float3(-0.5,0.7,1.0));
                float diffuse=max(0.0,dot(n,light));
                float spec=pow(max(0.0,dot(n,normalize(light+float3(0.0,0.0,1.0)))),32.0);
                // 浅色流动褶皱与宽高光，不绘制彩色边缘、圆环或描边。
                float folds=smoothstep(0.06,0.28,abs(fluid+0.08));
                float value=0.59+0.27*diffuse+0.11*folds+0.22*spec;
                value=mix(value,0.86,0.16*pow(1.0-sphereNormal.z,2.0));
                float alpha=coverage*opacity;
                return half4(half3(clamp(value,0.0,1.0)*alpha),half(alpha));
            }
            """
                .trimIndent()
        )
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = this@WhiteSphereShader.shader }

    fun draw(canvas: Canvas, x: Float, y: Float, radius: Float, opacity: Float, time: Float) {
        if (radius < .1f) return
        shader.setFloatUniform("center", x, y)
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("opacity", opacity)
        shader.setFloatUniform("time", time)
        val extent = radius * 1.5f + 2
        canvas.drawRect(x - extent, y - extent, x + extent, y + extent, paint)
    }
}
