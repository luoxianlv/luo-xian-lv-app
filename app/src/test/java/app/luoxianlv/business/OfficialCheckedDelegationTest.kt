package app.luoxianlv.business

import app.luoxianlv.hot.contract.HostActions
import app.luoxianlv.hot.contract.NativePage
import app.luoxianlv.hot.contract.NativePlaybackSession
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

/** 装饰器不得吞掉 Java default 宿主事件；使用实际接口枚举，新增 hook 也会被发现。 */
class OfficialCheckedDelegationTest {
    @Test
    fun eachJavaDefaultHasAnActualWrapperOverride() {
        for ((contract, wrapper) in listOf(
            NativePage::class.java to OfficialCheckedPage::class.java,
            NativePlaybackSession::class.java to OfficialCheckedPlayback::class.java,
        )) {
            for (method in contract.methods.filter { it.isDefault }) {
                assertEquals("缺少 default 委托桥：${method.name}", wrapper,
                    wrapper.getMethod(method.name, *method.parameterTypes).declaringClass)
            }
        }
    }

    @Test
    fun pageHostResultsReplacementAndRetainedStateReachTheSameDelegate() {
        val calls = mutableMapOf<String, Int>()
        val arguments = mutableMapOf<String, List<Any?>>()
        var retainedClosed = 0
        val retained = NativePage.Retained { retainedClosed++ }
        val delegate = proxy(NativePage::class.java) { name, args ->
            calls[name] = (calls[name] ?: 0) + 1
            arguments[name] = args
            when (name) {
                "canReplace" -> false
                "result", "back" -> true
                "retain" -> retained
                else -> null
            }
        }
        val host = proxy(HostActions::class.java) { _, _ -> null }
        val page = OfficialCheckedPage(delegate)
        page.attachHost(host)
        assertSame(host, arguments["attachHost"]!!.single())
        page.newIntent(null)
        assertTrue(page.result("result-key", -1, null))
        assertEquals(listOf("result-key", -1, null), arguments["result"])
        assertTrue(page.back())
        page.windowTouch()
        page.hostWarning("test-warning", null)
        page.configurationChanged(null)
        page.finishing()
        assertSame(retained, page.retain())
        page.restoreRetained(retained)
        assertSame(retained, arguments["restoreRetained"]!!.single())
        assertEquals(0, retainedClosed)
        assertFalse(page.canReplace())
        for (method in NativePage::class.java.methods.filter { it.isDefault }) {
            assertEquals("宿主事件未转发：${method.name}", 1, calls[method.name])
        }
    }

    @Test
    fun playbackHandoverAndReleaseFlagsAreDelegatedRatherThanInterfaceDefaults() {
        val calls = mutableMapOf<String, Int>()
        val arguments = mutableMapOf<String, List<Any?>>()
        val delegate = proxy(NativePlaybackSession::class.java) { name, args ->
            calls[name] = (calls[name] ?: 0) + 1
            arguments[name] = args
            when (name) {
                "supportsHandover" -> true
                "revision" -> 37L
                "released" -> false
                else -> null
            }
        }
        val playback = OfficialCheckedPlayback(delegate)
        assertTrue(playback.supportsHandover())
        // Java platform 返回值可为 null；转发不能新增 Kotlin 非空断言改变该行为。
        assertNull(playback.snapshot())
        val ready = NativePage.Ready {}
        playback.restore(null, ready)
        assertSame(ready, arguments["restore"]!![1])
        playback.activate()
        playback.deactivate()
        assertEquals(37L, playback.revision())
        assertFalse(playback.released())
        for (name in listOf("supportsHandover", "snapshot", "restore", "activate", "deactivate", "revision", "released")) {
            assertEquals("播放协议未转发：$name", 1, calls[name])
        }
    }

    private fun <T> proxy(type: Class<T>, handler: (String, List<Any?>) -> Any?): T =
        type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, arguments ->
            handler(method.name, arguments?.toList() ?: emptyList())
        })
}
