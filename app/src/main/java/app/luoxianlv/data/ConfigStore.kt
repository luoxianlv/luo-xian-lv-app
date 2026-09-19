package app.luoxianlv.data
import android.content.Context
import app.luoxianlv.core.score.PlayMode
import org.json.JSONObject

data class KeyLayout(
    val noteX: FloatArray,
    val noteY: Float,
    val modes: Map<PlayMode, FloatArray>,
)

object ConfigStore {
    private const val NAME = "ratio_config_v3"

    fun load(context: Context): KeyLayout {
        val prefs = Kv.of(context, NAME)
        val xs = floatArrayOf(.189f, .278f, .367f, .456f, .545f, .633f, .722f, .810f)
        val modes =
            mapOf(
                PlayMode.SEMITONE to .364f,
                PlayMode.RAISE to .464f,
                PlayMode.NATURAL to .552f,
                PlayMode.LOWER to .639f,
            ).mapValues { (mode, x) ->
                floatArrayOf(prefs.getFloat(mode.name + "X", x), prefs.getFloat(mode.name + "Y", .425f))
            }
        return KeyLayout(FloatArray(8) { prefs.getFloat("noteX$it", xs[it]) }, prefs.getFloat("noteY", .608f), modes)
    }

    fun save(
        context: Context,
        layout: KeyLayout,
    ) {
        Kv
            .of(context, NAME)
            .edit()
            .apply {
                layout.noteX.forEachIndexed { index, x -> putFloat("noteX$index", x) }
                putFloat("noteY", layout.noteY)
                layout.modes.forEach { (mode, point) ->
                    putFloat(mode.name + "X", point[0])
                    putFloat(mode.name + "Y", point[1])
                }
            }.apply()
    }

    fun applyHotLayout(
        context: Context,
        json: JSONObject,
    ) {
        val current = load(context)
        val xs =
            json.optJSONArray("noteX")?.let { array ->
                FloatArray(8) { array.optDouble(it, current.noteX.getOrElse(it) { .5f }.toDouble()).toFloat() }
            }
                ?: current.noteX
        val modes = current.modes.toMutableMap()
        val modeJson = json.optJSONObject("modes")
        modes.keys.forEach { mode ->
            val value = modeJson?.optJSONObject(mode.name) ?: return@forEach
            modes[mode] =
                floatArrayOf(
                    value.optDouble("x", current.modes.getValue(mode)[0].toDouble()).toFloat(),
                    value.optDouble("y", current.modes.getValue(mode)[1].toDouble()).toFloat(),
                )
        }
        save(context, KeyLayout(xs, json.optDouble("noteY", current.noteY.toDouble()).toFloat(), modes))
    }
}
