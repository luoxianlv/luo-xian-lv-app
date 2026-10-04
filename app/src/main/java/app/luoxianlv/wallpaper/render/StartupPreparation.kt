package app.luoxianlv.wallpaper.render

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

/** 只限制调用方等待；后台初始化及其真实租约仍等到引擎回调后结束。 */
internal suspend fun awaitStartupPreparation(result: Deferred<Unit>, timeoutMs: Long = 60_000) {
    if (withTimeoutOrNull(timeoutMs) { result.await() } == null) {
        throw IllegalStateException("壁纸引擎初始化等待超时")
    }
}
