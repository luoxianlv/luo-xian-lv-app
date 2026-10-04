package app.luoxianlv

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import app.luoxianlv.shared.NoticeSnackbarHost
import app.luoxianlv.shared.SnackbarNotice
import app.luoxianlv.ui.theme.LuoXianLvTheme

/** 验证真实 Activity 生命周期中的提示到期、页面回收和新消息替换。 */
internal fun Instrumentation.checkSnackbarTiming(home: MainActivity) {
    val host = SnackbarHostState()
    val notice = mutableStateOf<String?>(null)
    val pageVisible = mutableStateOf(true)
    runOnMainSync {
        home.businessComposeView().setContent {
            LuoXianLvTheme(darkTheme = false) {
                NoticeSnackbarHost(host)
                if (pageVisible.value) SnackbarNotice(notice.value, host) { notice.value = null }
            }
        }
    }
    fun await(message: String, timeout: Long = 6000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            var matches = false
            runOnMainSync { matches = condition() }
            if (matches) return
            Thread.sleep(50)
        }
        error(message)
    }
    runOnMainSync { notice.value = "已添加乐谱" }
    await("提示未显示") { host.currentSnackbarData != null }
    await("提示到期未消失") { host.currentSnackbarData == null && notice.value == null }
    runOnMainSync { notice.value = "切到后台的提示" }
    await("后台测试提示未显示") { host.currentSnackbarData != null }
    home.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
    Thread.sleep(5000)
    targetContext.startActivity(
        Intent(targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
    )
    await("后台回来仍显示过期提示") {
        home.hasWindowFocus() && host.currentSnackbarData == null && notice.value == null
    }
    runOnMainSync { notice.value = "离开页面的提示" }
    await("页面回收测试提示未显示") { host.currentSnackbarData != null }
    runOnMainSync { pageVisible.value = false }
    await("离开页面后事件没有消费") { notice.value == null && host.currentSnackbarData == null }
    runOnMainSync {
        pageVisible.value = true
        notice.value = "旧提示"
    }
    await("旧提示未显示") { host.currentSnackbarData?.visuals?.message == "旧提示" }
    runOnMainSync { notice.value = "新提示" }
    await("旧协程取消时误清理了新提示") {
        host.currentSnackbarData?.visuals?.message == "新提示" && notice.value == "新提示"
    }
    await("新提示未正常到期") { notice.value == null && host.currentSnackbarData == null }
}
