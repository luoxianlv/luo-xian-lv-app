package app.luoxianlv.business.ui

import android.content.Context
import app.luoxianlv.hot.contract.OfficialAssets
import org.json.JSONObject

/** 只影响本代渲染/音频参数，不写用户偏好。核心准备阶段已执行严格JSON校验。 */
object OfficialRuntimeConfig {
    data class Values(val harmonicaGain: Float = 1f, val shaderStrength: Float = 1f)
    fun read(context: Context): Values {
        if (!OfficialAssets.mounted(context, "config")) return Values()
        val path = OfficialAssets.documentPath(context, "config", "config.json")
        val value = JSONObject(OfficialAssets.text(context, "config", path, "", 65536))
        require(value.length() == 3 && value.getInt("schema") == 1)
        val gain = value.getInt("harmonicaGainPermille")
        val strength = value.getInt("shaderStrengthPermille")
        require(gain in 0..1000 && strength in 0..1000)
        return Values(gain / 1000f, strength / 1000f)
    }
}
