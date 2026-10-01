package app.luoxianlv.data

import android.content.Context
import android.content.SharedPreferences
import io.fastkv.FastKV

/**
 * 键值存储统一入口：FastKV.adapt 自动迁移同名 SharedPreferences，读写接口保持不变。 同名存储全局复用，避免双文件备份互相覆盖；FastKV 3.x
 * 仅支持单进程，新增独立进程时须改用 MPFastKV。
 */
object Kv {
    private val adapters = HashMap<String, SharedPreferences>()

    /** 打开（并按需迁移）名为 [name] 的存储，同名返回同一实例。 */
    @Synchronized
    fun of(
        context: Context,
        name: String,
    ): SharedPreferences =
        adapters.getOrPut(name) {
            FastKV.adapt(app.luoxianlv.hot.contract.PlatformApplication.of(context), name)
        }
}
