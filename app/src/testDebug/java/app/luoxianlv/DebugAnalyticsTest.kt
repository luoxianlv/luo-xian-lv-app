package app.luoxianlv

import android.content.ContextWrapper
import app.luoxianlv.core.Analytics
import org.junit.Assert.*
import org.junit.Test

class DebugAnalyticsTest {
    @Test
    fun debugTelemetryIsAbsentAndCallsStoreNothing() {
        assertTrue(BuildConfig.INTERNAL_BUILD)
        val context = ContextWrapper(null)
        Analytics.preInitialize(context)
        Analytics.initialize(context)
        Analytics.recordScheme("luoxianlv://test")
        Analytics.pageStart("practice")
        Analytics.logEvent(context, "test_event")
        Analytics.pageEnd("practice")
        assertNull(Analytics.initAt)
        assertTrue(Analytics.diagEntries.isEmpty())
        for (name in
            listOf(
                "com.umeng.commonsdk.UMConfigure",
                "com.umeng.analytics.MobclickAgent",
                "com.umeng.umcrash.UMCrash",
                "com.efs.sdk.base.EfsReporter",
            )) {
            try {
                Class.forName(name)
                fail("Debug must not contain $name")
            } catch (_: ClassNotFoundException) {
                // 通过构建依赖隔离统计 SDK，而不是只关闭运行时开关。
            }
        }
    }
}
