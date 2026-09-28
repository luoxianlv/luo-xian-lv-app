package app.luoxianlv.ui.practice

import android.graphics.*
import kotlin.math.*
import kotlin.random.Random

/** Two windows share a normalized gather/return timeline; geometry survives rotation. */
internal class StageExitRenderer(private val dark: Boolean) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val random = Random(73921)

    private data class Speck(
        val key: Int,
        val theta: Float,
        val delay: Float,
        val depth: Float,
        val bend: Float,
        val size: Float,
    ) {
        val thetaCos = cos(theta)
        val thetaSin = sin(theta)
    }

    private val specks =
        Array(192) {
            Speck(
                it % 8,
                random.nextFloat() * 6.283185f,
                random.nextFloat() * .1f,
                random.nextFloat(),
                random.nextFloat() * 2 - 1,
                2.5f + random.nextFloat() * 3.7f,
            )
        }
    private var gradient: Shader? = null
    private val glow =
        RadialGradient(
            0f,
            0f,
            1f,
            intArrayOf(0xb0eaf6ff.toInt(), 0x407fb9f0, 0x007fb9f0),
            floatArrayOf(0f, .3f, 1f),
            Shader.TileMode.CLAMP,
        )

    private fun glowAt(c: Canvas, x: Float, y: Float, r: Float, alpha: Float) {
        if (alpha <= 0) return
        c.save()
        c.translate(x, y)
        c.scale(r, r)
        paint.style = Paint.Style.FILL
        paint.shader = glow
        paint.alpha = (255 * alpha.coerceIn(0f, 1f)).toInt()
        c.drawCircle(0f, 0f, 1f, paint)
        paint.shader = null
        c.restore()
    }

    fun resize(w: Int, h: Int) {
        gradient =
            LinearGradient(
                0f,
                0f,
                w.toFloat(),
                h.toFloat(),
                if (dark) intArrayOf(0xff1e3a55.toInt(), 0xff243044.toInt(), 0xff3a2a3e.toInt())
                else intArrayOf(0xff344e68.toInt(), 0xff495569.toInt(), 0xff68556f.toInt()),
                null,
                Shader.TileMode.CLAMP,
            )
    }

    fun draw(c: Canvas, w: Float, h: Float, p: Float, target: RectF?) {
        val gather = StageLightRenderer.smooth(0f, .54f, p)
        val home = StageLightRenderer.smooth(.56f, .98f, p)
        paint.style = Paint.Style.FILL
        paint.shader = gradient
        paint.alpha = (255 * StageLightRenderer.smooth(0f, .25f, p) * (1 - home)).toInt()
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        val fit = PracticeGeometry.fit(w, h)
        val unit = min(w, h) / 390f
        val endX = target?.centerX() ?: w * .8f
        val endY = target?.centerY() ?: h * .65f
        val midX = w / 2
        val midY = h / 2
        val travelCenter = StageLightRenderer.smooth(.56f, .98f, p)
        val invCenter = 1 - travelCenter
        val glowX =
            invCenter * invCenter * midX +
                2 * invCenter * travelCenter * (endX + unit * 20) +
                travelCenter * travelCenter * endX
        val glowY =
            invCenter * invCenter * midY +
                2 * invCenter * travelCenter * (midY - unit * 110) +
                travelCenter * travelCenter * endY
        glowAt(
            c,
            glowX,
            glowY,
            unit * (72 - 40 * home),
            StageLightRenderer.smooth(.14f, .48f, p) *
                (1 - StageLightRenderer.smooth(.85f, 1f, p)) *
                .85f,
        )
        val homeWave = sin(home * PI.toFloat())
        specks.forEach { s ->
            val k = StageLightRenderer.smooth(s.delay, .54f, p)
            val gatherWave = sin(k * PI.toFloat())
            val key = PracticeGeometry.notes[s.key]
            val startX = fit.left + (key.x + s.thetaCos * key.radius) * fit.scale
            val startY = fit.top + (key.y + s.thetaSin * key.radius) * fit.scale
            val cloudX = midX + cos(s.theta + gather * 2.5f) * unit * (15 + 29 * s.depth)
            val cloudY = midY + sin(s.theta + gather * 2.5f) * unit * (10 + 18 * s.depth)
            var x = startX + (cloudX - startX) * k + gatherWave * s.bend * unit * 70
            var y = startY + (cloudY - startY) * k - gatherWave * (25 + s.depth * 70) * unit
            if (p >= .54f) {
                val travel =
                    StageLightRenderer.smooth(.56f + s.delay * .5f, .96f + s.delay * .3f, p)
                val inv = 1 - travel
                x =
                    inv * inv * cloudX +
                        2 * inv * travel * (endX + unit * 50 * s.bend) +
                        travel * travel * endX
                y =
                    inv * inv * cloudY +
                        2 * inv * travel * (midY - unit * (80 + s.depth * 70)) +
                        travel * travel * endY
            }
            val fade =
                (1 - StageLightRenderer.smooth(.88f + s.delay * .3f, 1f, p)) *
                    (.45f + .55f * s.depth)
            val length = unit * (12 + 34 * gatherWave + 42 * homeWave)
            paint.strokeCap = Paint.Cap.ROUND
            paint.style = Paint.Style.STROKE
            path.rewind()
            path.moveTo(x - length * s.bend, y + length * .75f)
            path.quadTo(x - length * s.bend * .25f, y + length * .45f, x, y)
            paint.strokeWidth = unit * (4 + s.depth * 3)
            paint.color = 0xff85b9eb.toInt()
            paint.alpha = (70 * fade).toInt()
            c.drawPath(path, paint)
            paint.strokeWidth = unit * (1.1f + s.depth * 1.5f)
            paint.color = 0xffeef8ff.toInt()
            paint.alpha = (245 * fade).toInt()
            c.drawPath(path, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.alpha = (245 * fade).toInt()
            val size = s.size * unit * (1 - .4f * home)
            path.rewind()
            path.moveTo(x, y - size)
            path.lineTo(x + size * .55f, y)
            path.lineTo(x, y + size)
            path.lineTo(x - size * .55f, y)
            path.close()
            c.drawPath(path, paint)
        }
        if (target != null && p > .85f) {
            val flash = sin(StageLightRenderer.smooth(.85f, 1f, p) * PI.toFloat())
            paint.color = Color.WHITE
            paint.alpha = (flash * 150).toInt()
            c.drawRoundRect(target, 24 * unit, 24 * unit, paint)
        }
    }
}
