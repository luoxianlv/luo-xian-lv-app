package app.luoxianlv.business.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.luoxianlv.hot.contract.HostActions
import app.luoxianlv.hot.contract.NativePage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class LazyPageHarnessActivity : Activity() {
    var page = LazyItemPage()
        private set

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        render(Bundle())
    }

    fun replace(state: Bundle) {
        page.close()
        page = LazyItemPage()
        render(state)
    }

    private fun render(state: Bundle) {
        page.attachHost(
            object : HostActions {
                override fun launch(key: String, intent: Intent, options: Bundle?) = Unit

                override fun permissions(key: String, permissions: Array<String>) = Unit

                override fun resultReady(key: String) = Unit

                override fun hasPendingResults() = false

                override fun finish() = Unit
            }
        )
        setContentView(page.create(this, state, Bundle(), { _, _ -> }, {}))
        page.lifecycle(NativePage.RESUMED)
    }

    override fun onDestroy() {
        page.close()
        super.onDestroy()
    }
}

class LazyItemPage : ComposePage() {
    private val values = mutableMapOf<Int, MutableState<String>>()
    val edits = mutableMapOf<Int, (String) -> Unit>()
    var list: LazyListState? = null
        private set

    private var scope: CoroutineScope? = null

    fun value(index: Int) = values[index]?.value

    fun scroll(index: Int) {
        scope!!.launch { list!!.scrollToItem(index) }
    }

    @Composable
    override fun Content() {
        MaterialTheme {
            val position = rememberLazyListState()
            val coroutine = rememberCoroutineScope()
            SideEffect {
                list = position
                scope = coroutine
            }
            LazyColumn(Modifier.fillMaxSize(), state = position) {
                // 故意使用默认 key，回归实际线上出现的内部 Parcelable。
                items(40) { index ->
                    val text = rememberSaveable { mutableStateOf("原始值$index") }
                    SideEffect {
                        values[index] = text
                        edits[index] = { text.value = it }
                    }
                    TextButton(
                        onClick = { text.value = "已选择$index" },
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                    ) {
                        Text("列表行$index：${text.value}")
                    }
                }
            }
        }
    }
}

internal object LazyPageStateChecks {
    fun run(runner: Instrumentation) {
        val activity =
            runner.startActivitySync(
                Intent(runner.context, LazyPageHarnessActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as LazyPageHarnessActivity
        fun main(action: () -> Unit) {
            var failure: Throwable? = null
            runner.runOnMainSync {
                try {
                    action()
                } catch (error: Throwable) {
                    failure = error
                }
            }
            failure?.let { throw AssertionError("默认列表状态检查失败", it) }
        }
        fun await(message: String, ready: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 15000
            while (true) {
                var available = false
                main { available = ready() }
                if (available) return
                check(SystemClock.uptimeMillis() < deadline) { message }
                SystemClock.sleep(30)
            }
        }
        try {
            for (index in listOf(0, 10)) {
                await("列表尚未完成组合") { activity.page.list != null }
                main { activity.page.scroll(index) }
                await("目标列表项没有组合") {
                    activity.page.list!!.firstVisibleItemIndex == index &&
                        activity.page.edits.containsKey(index)
                }
                main { activity.page.edits.getValue(index)("已保存的中文$index") }
                await("列表修改未进入保存状态") { activity.page.value(index) == "已保存的中文$index" }
                var saved = Bundle()
                main { saved = activity.page.save() }
                check(saved.getString("compose-state")!!.contains("lazy-key")) { "实际列表没有覆盖默认键保存" }
                main { activity.replace(saved) }
                await("页面恢复没有保留列表位置和输入") {
                    activity.page.list?.firstVisibleItemIndex == index &&
                        activity.page.value(index) == "已保存的中文$index"
                }
            }
        } finally {
            main { activity.finish() }
        }
    }
}
