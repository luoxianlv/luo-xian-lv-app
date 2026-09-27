package app.luoxianlv

import app.luoxianlv.core.Analytics
import org.junit.Assert.assertEquals
import org.junit.Test

class AnalyticsDiagnosticsTest {
    @Test
    fun recordingPastCapacityKeepsTheLatestHundredEntries() {
        Analytics.diagEntries.clear()
        try {
            repeat(100) { Analytics.recordScheme("scheme-$it") }
            assertEquals(100, Analytics.diagEntries.size)
            assertEquals("scheme-0", Analytics.diagEntries.first().detail)

            // 1.0.6 在第 101 条记录淘汰旧记录时调用了旧 Android 不支持的 List.removeFirst。
            repeat(5) { Analytics.recordScheme("scheme-${100 + it}") }
            assertEquals(100, Analytics.diagEntries.size)
            assertEquals("scheme-5", Analytics.diagEntries.first().detail)
            assertEquals("scheme-104", Analytics.diagEntries.last().detail)
        } finally {
            Analytics.diagEntries.clear()
        }
    }
}
