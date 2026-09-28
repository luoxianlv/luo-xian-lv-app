package app.luoxianlv.ui.practice

import android.graphics.*
import android.os.Build
import kotlin.math.*
import kotlin.random.Random

/** 白色球体及不规则立体碎片；复用几何，每帧投影并按深度排序。 */
internal class StageLightRenderer(private val dark: Boolean) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var background: Shader? = null
    private val sphere = if (Build.VERSION.SDK_INT >= 33) WhiteSphereShader() else null
    private val fallback =
        RadialGradient(
            -.32f,
            -.38f,
            1.55f,
            intArrayOf(Color.WHITE, 0xffb8b8b8.toInt()),
            null,
            Shader.TileMode.CLAMP,
        )
    private val random = Random(847216)

    private data class Shard(
        val angle: Float,
        val distance: Float,
        val depth: Float,
        val delay: Float,
        val size: Float,
        val stretch: Float,
        val spinX: Float,
        val spinY: Float,
        val spinZ: Float,
        val bend: Float,
        val key: Int,
        val theta: Float,
        val shape: FloatArray,
    ) {
        val angleCos = cos(angle)
        val angleSin = sin(angle)
        val thetaCos = cos(theta)
        val thetaSin = sin(theta)
    }

    private val shards =
        Array(112) { i ->
            val cluster = floatArrayOf(-.35f, .9f, 2.3f, 3.6f, 5.0f)[random.nextInt(5)]
            Shard(
                cluster + (random.nextFloat() + random.nextFloat() - 1) * .85f,
                .5f + random.nextFloat() * 1.5f,
                (random.nextFloat() - .5f) * 2.4f,
                random.nextFloat() * .08f,
                if (i < 12) .065f + random.nextFloat() * .05f
                else .009f + random.nextFloat().pow(2) * .05f,
                .5f + random.nextFloat() * 1.7f,
                random.nextFloat() * 5 - 2.5f,
                random.nextFloat() * 7 - 3.5f,
                random.nextFloat() * 5 - 2.5f,
                random.nextFloat() * 2 - 1,
                random.nextInt(8),
                random.nextFloat() * 2 * PI.toFloat(),
                floatArrayOf(
                    -.5f,
                    -.3f,
                    .28f + random.nextFloat() * .25f,
                    -.48f,
                    .13f,
                    .4f + random.nextFloat() * .3f,
                    -.32f,
                    .16f,
                ),
            )
        }

    private class Face {
        val x = FloatArray(3)
        val y = FloatArray(3)
        var depth = 0f
        var shade = 1f
        var alpha = 1f
    }

    private val faces = Array(shards.size * 12) { Face() }
    private val order = ArrayList<Face>(faces.size)
    private val depthOrder = Comparator<Face> { a, b -> a.depth.compareTo(b.depth) }
    private val vx = FloatArray(8)
    private val vy = FloatArray(8)
    private val vz = FloatArray(8)
    private val projectedX = FloatArray(8)
    private val projectedY = FloatArray(8)
    private val indices =
        intArrayOf(
            0,
            2,
            1,
            0,
            3,
            2,
            4,
            5,
            6,
            4,
            6,
            7,
            0,
            1,
            5,
            0,
            5,
            4,
            2,
            3,
            7,
            2,
            7,
            6,
            1,
            2,
            6,
            1,
            6,
            5,
            3,
            0,
            4,
            3,
            4,
            7,
        )

    fun resize(w: Int, h: Int) {
        background =
            LinearGradient(
                0f,
                0f,
                w.toFloat(),
                h.toFloat(),
                if (dark) intArrayOf(0xff152133.toInt(), 0xff293449.toInt(), 0xff302c3b.toInt())
                else intArrayOf(0xff91b4ca.toInt(), 0xffaab6c9.toInt(), 0xffc7b6cb.toInt()),
                null,
                Shader.TileMode.CLAMP,
            )
    }

    private fun fill(color: Int, alpha: Float = 1f) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
    }

    fun draw(c: Canvas, w: Float, h: Float, p: Float, time: Float, backgroundReady: Boolean) {
        if (p >= 1f) return
        val reveal = if (backgroundReady) smooth(.61f, .79f, p) else 0f
        fill(Color.WHITE, 1 - reveal)
        paint.shader = background
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        val appear = smooth(.05f, .28f, p)
        val collapse = smooth(.49f, .60f, p)
        val radius = min(w, h) * .19f * (1 - collapse * .2f) * (1 - smooth(.56f, .66f, p))
        if (radius > .1f) {
            if (Build.VERSION.SDK_INT >= 33 && sphere != null)
                sphere.draw(c, w / 2, h / 2, radius, appear, time)
            else {
                c.save()
                c.translate(w / 2, h / 2)
                c.scale(radius, radius)
                fill(Color.WHITE, appear)
                paint.shader = fallback
                c.drawCircle(0f, 0f, 1f, paint)
                c.restore()
                paint.shader = null
            }
        }
        if (p < .56f || p > .985f) return
        val fit = PracticeGeometry.fit(w, h)
        val unit = min(w, h)
        order.clear()
        shards.forEachIndexed { i, s ->
            val age = ((p - .56f - s.delay) / .2f).coerceAtLeast(0f)
            val release = 1 - exp(-age * 3.0f)
            val assemble = smooth(.71f + s.delay * .4f, .93f + s.delay * .4f, p)
            val end = PracticeGeometry.notes[s.key]
            val tx = fit.left + (end.x + s.thetaCos * end.radius) * fit.scale
            val ty = fit.top + (end.y + s.thetaSin * end.radius) * fit.scale
            val spreadX = w / 2 + s.angleCos * unit * s.distance
            val spreadY = h / 2 + s.angleSin * unit * s.distance * .52f
            val x = lerp(w / 2 + s.angleCos * unit * .055f, spreadX, release)
            val y =
                lerp(h / 2 + s.angleSin * unit * .055f, spreadY, release) - age * age * unit * .015f
            val centerX =
                lerp(x, tx, assemble) + sin(assemble * PI.toFloat()) * s.bend * unit * .25f
            val centerY = lerp(y, ty, assemble)
            val z = s.depth * unit * .5f * release * (1 - assemble)
            val visible = smooth(0f, .12f, age) * (1 - smooth(.91f + s.delay * .3f, .985f, p))
            val size = s.size * unit * visible
            if (size < .1f) return@forEachIndexed
            val ax = s.angle + age * s.spinX
            val ay = age * s.spinY
            val az = s.angle + age * s.spinZ
            val sx = sin(ax)
            val cx = cos(ax)
            val sy = sin(ay)
            val cy = cos(ay)
            val sz = sin(az)
            val cz = cos(az)
            repeat(8) { v ->
                val xx = s.shape[(v % 4) * 2] * size * s.stretch
                val yy = s.shape[(v % 4) * 2 + 1] * size
                val zz = (if (v < 4) -.04f else .04f) * size
                val y1 = yy * cx - zz * sx
                val z1 = yy * sx + zz * cx
                val x2 = xx * cy + z1 * sy
                val z2 = -xx * sy + z1 * cy
                vx[v] = x2 * cz - y1 * sz
                vy[v] = x2 * sz + y1 * cz
                vz[v] = z2
                val perspective = unit * 3 / (unit * 3 - z - z2)
                projectedX[v] = w / 2 + (centerX - w / 2 + vx[v]) * perspective
                projectedY[v] = h / 2 + (centerY - h / 2 + vy[v]) * perspective
            }
            repeat(12) { f ->
                val face = faces[i * 12 + f]
                val a = indices[f * 3]
                val b = indices[f * 3 + 1]
                val d = indices[f * 3 + 2]
                val ux = vx[b] - vx[a]
                val uy = vy[b] - vy[a]
                val uz = vz[b] - vz[a]
                val dx = vx[d] - vx[a]
                val dy = vy[d] - vy[a]
                val dz = vz[d] - vz[a]
                val nx = uy * dz - uz * dy
                val ny = uz * dx - ux * dz
                val nz = ux * dy - uy * dx
                val length = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(.0001f)
                val lighting = abs((nx * -.3f + ny * -.5f + nz * .81f) / length)
                face.shade = if (lighting > .65f) 1f else if (lighting > .25f) .86f else .69f
                face.alpha = visible
                face.depth = z + (vz[a] + vz[b] + vz[d]) / 3
                repeat(3) { corner ->
                    val v = indices[f * 3 + corner]
                    face.x[corner] = projectedX[v]
                    face.y[corner] = projectedY[v]
                }
                order.add(face)
            }
        }
        order.sortWith(depthOrder)
        order.forEach { f ->
            val shade = (f.shade * 255).toInt()
            fill(Color.rgb(shade, shade, shade), f.alpha)
            path.rewind()
            path.moveTo(f.x[0], f.y[0])
            path.lineTo(f.x[1], f.y[1])
            path.lineTo(f.x[2], f.y[2])
            path.close()
            c.drawPath(path, paint)
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    companion object {
        fun smooth(start: Float, end: Float, value: Float): Float {
            val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
            return t * t * (3 - 2 * t)
        }
    }
}
