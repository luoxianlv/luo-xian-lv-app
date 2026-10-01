package app.luoxianlv.ui.theme

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import app.luoxianlv.hot.contract.OfficialAssets
import org.json.JSONObject

internal class OfficialPalette private constructor(private val light: List<Color>, private val dark: List<Color>) {
    fun apply(darkTheme: Boolean, base: ColorScheme): ColorScheme {
        val colors = if (darkTheme) dark else light
        return base.copy(primary = colors[0], onPrimary = colors[1], secondary = colors[2],
            background = colors[3], surface = colors[4], onBackground = colors[5], onSurface = colors[6])
    }
    companion object {
        fun load(context: Context): OfficialPalette? {
            if (!OfficialAssets.mounted(context, "theme")) return null
            val path = OfficialAssets.documentPath(context, "theme", "palette.json")
            val value = JSONObject(OfficialAssets.text(context, "theme", path, "", 65536))
            require(value.length() == 3 && value.getInt("schema") == 1)
            fun colors(mode: String): List<Color> {
                val block = value.getJSONObject(mode)
                require(block.length() == 7)
                return listOf("primary", "onPrimary", "secondary", "background", "surface", "onBackground", "onSurface").map {
                    val raw = block.getString(it)
                    require(raw.matches(Regex("#[0-9a-fA-F]{8}")))
                    Color(raw.substring(1).toLong(16))
                }
            }
            return OfficialPalette(colors("light"), colors("dark"))
        }
    }
}
