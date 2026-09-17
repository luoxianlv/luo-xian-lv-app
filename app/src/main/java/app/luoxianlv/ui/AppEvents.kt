package app.luoxianlv.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 跨页面轻量事件总线。
 *
 * 曲库数据会被导入页 / 发现页改动，曲库页必须重新读取；
 * 而 HorizontalPager 会销毁离屏页面，不能指望「回到该页时重组」来刷新。
 * 统一从这里发通知，避免每个改动点自己想办法。
 */
object AppEvents {
    private val _libraryChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 曲库内容发生变化（导入 / 平台下载 / 另存为 / 删除 / 热更）。 */
    val libraryRefresh = _libraryChanged.asSharedFlow()

    fun notifyLibraryChanged() {
        _libraryChanged.tryEmit(Unit)
    }
}
