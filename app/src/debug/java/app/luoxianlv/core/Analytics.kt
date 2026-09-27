package app.luoxianlv.core

import android.content.Context

/** Debug deliberately has no telemetry SDK, initialization, event storage or reporting. */
@Suppress("UNUSED_PARAMETER")
object Analytics {
    const val APP_KEY = ""
    const val CHANNEL = "debug-disabled"

    data class DiagEntry(val time: Long, val kind: String, val detail: String)

    val diagEntries: List<DiagEntry> = emptyList()
    val initAt: Long? = null

    fun preInitialize(context: Context) = Unit

    fun initialize(context: Context) = Unit

    fun recordScheme(dataString: String?) = Unit

    fun logEvent(context: Context, event: String) = Unit

    fun pageStart(page: String) = Unit

    fun pageEnd(page: String) = Unit
}
