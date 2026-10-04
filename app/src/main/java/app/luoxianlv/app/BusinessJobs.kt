package app.luoxianlv.app

import android.os.Handler
import app.luoxianlv.hot.contract.WorkGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 业务加载器各持一份门禁；租约包含回调，避免入库尚未结束就切换界面来源。 */
object BusinessJobs {
    val gate = WorkGate()

    /** 租约覆盖挂起和回到调用线程；取消后也须等阻塞 I/O 真正离开 finally。 */
    suspend fun <T> io(action: suspend CoroutineScope.() -> T): T {
        val lease = gate.acquire() ?: throw CancellationException("本代业务已停止接受工作")
        return try {
            withContext(Dispatchers.IO, action)
        } finally {
            lease.close()
        }
    }

    /** 后台结果排到主线程时继续持有租约，防止回退后迟到回调操作另一代服务。 */
    fun post(handler: Handler, action: () -> Unit): Boolean {
        val lease = gate.acquire() ?: return false
        try {
            if (
                handler.post {
                    try {
                        action()
                    } finally {
                        lease.close()
                    }
                }
            )
                return true
        } catch (failure: Throwable) {
            lease.close()
            throw failure
        }
        lease.close()
        return false
    }

    fun thread(name: String, action: () -> Unit): Boolean {
        val lease = gate.acquire() ?: return false
        try {
            Thread(
                    {
                        try {
                            action()
                        } finally {
                            lease.close()
                        }
                    },
                    name,
                )
                .apply { isDaemon = true }
                .start()
        } catch (failure: Throwable) {
            lease.close()
            throw failure
        }
        return true
    }
}
