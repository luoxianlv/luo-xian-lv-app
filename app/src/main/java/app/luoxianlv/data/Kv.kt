package app.luoxianlv.data

import android.content.Context
import android.content.SharedPreferences
import io.fastkv.FastKV

/**
 * 持久化键值存储统一入口：底层是 FastKV，对外仍返回 [SharedPreferences] 接口。
 *
 * 用 `FastKV.adapt` 做迁移是官方路径：首次打开某个存储时，它会检查同名
 * SharedPreferences 文件 —— 有数据就整体搬进 FastKV（二进制格式，之后
 * 读写全部走 FastKV），没有就直接用空存储。老用户升级无感，
 * 不需要任何手动迁移步骤或版本判断。
 *
 * 读写 API 与 SharedPreferences 完全一致（getXxx / edit().putXxx().apply() /
 * commit() / clear() / all），所以各 Store 只换获取入口、不改业务代码；
 * 将来想改用 FastKV 原生 API（免 Editor 的 putXxx、双文件备份、增量更新），
 * 把这里的返回类型换掉即可，调用点不变。
 *
 * 每个存储名全局只建一个实例（等价于 getSharedPreferences 的按名缓存），
 * 多实例打开同一文件会让 FastKV 的双文件备份互相踩。
 *
 * 注意（FastKV 3.x）：不再支持多进程。本应用所有组件都在主进程，
 * 若将来加 `:remote` 之类的独立进程存储，需要换用 MPFastKV。
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
            FastKV.adapt(context.applicationContext, name)
        }
}
