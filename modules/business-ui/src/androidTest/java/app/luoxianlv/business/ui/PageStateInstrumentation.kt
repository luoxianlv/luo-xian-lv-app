package app.luoxianlv.business.ui

import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.util.SparseArray
import android.view.AbsSavedState
import androidx.compose.foundation.lazy.layout.getDefaultLazyLayoutKey
import androidx.compose.runtime.*

/** 在 Android 实际 Bundle/Compose 上验证类型、边界和状态重建，不依赖 JVM 空壳。 */
class PageStateInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val report = Bundle()
        try {
            primitiveArrays()
            composeState()
            uuidState()
            lazyKeys()
            viewState()
            LazyPageStateChecks.run(this)
            DialogVisibilityChecks.run(this)
            rejects {
                PageSavedState.encode(Bundle().apply { putString("large", "中".repeat(30001)) })
            }
            rejects {
                PageSavedState.encode(Bundle().apply { putIntArray("many", IntArray(2049)) })
            }
            rejects { PageSavedState.encode(Bundle().apply { putBundle("cycle", this) }) }
            rejects { PageSavedState.encode(Bundle().apply { putParcelable("foreign", Intent()) }) }
            rejects { PageSavedState.encode(Bundle().apply { putDouble("bad", Double.NaN) }) }
            rejects { PageSavedState.decode("[2,[\"bundle\",[]]]") }
            rejects { PageSavedState.decode("[".repeat(4000) + "]".repeat(4000)) }
            rejects { PageSavedState.decode("[1,[\"bundle\",[[\"a\",[\"float\",\"NaN\"]]]]]") }
            rejects { PageSavedState.decode("[1,[\"bundle\",[[\"a\",[\"uuid\",\"1-1-1-1-1\"]]]]]") }
            rejects {
                PageSavedState.decode("[1,[\"bundle\",[[\"a\",[\"int\",1]],[\"a\",[\"int\",2]]]]]")
            }
            report.putString(
                "stream",
                "页面状态通过：基础值、默认列表键与实际列表输入恢复、Compose及原生控件状态、深度/体积限制和未知对象拒绝；候选预热及后台分页不显示弹窗。\n",
            )
            finish(-1, report)
        } catch (error: Throwable) {
            report.putString("stream", "页面状态失败：${error.stackTraceToString()}")
            finish(0, report)
        }
    }

    private fun primitiveArrays() {
        val source =
            Bundle().apply {
                putStringArray("strings", arrayOf("中文🎵", null, "[\\\"]"))
                putByteArray("bytes", byteArrayOf(-128, 0, 127))
                putShortArray("shorts", shortArrayOf(-32768, 32767))
                putCharArray("chars", charArrayOf('中', '\u0000', '\uffff'))
                putIntArray("ints", intArrayOf(Int.MIN_VALUE, Int.MAX_VALUE))
                putLongArray("longs", longArrayOf(Long.MIN_VALUE, Long.MAX_VALUE))
                putFloatArray("floats", floatArrayOf(-0f, Float.MAX_VALUE))
                putDoubleArray("doubles", doubleArrayOf(Double.MIN_VALUE, Double.MAX_VALUE))
                putBooleanArray("booleans", booleanArrayOf(false, true))
                putParcelableArray("bundles", arrayOf(Bundle().apply { putString("a", "值") }, null))
                putParcelableArray("uris", arrayOf<Uri?>(Uri.parse("content://test/谱子"), null))
            }
        val restored = PageSavedState.decode(PageSavedState.encode(source))
        check(restored.getStringArray("strings")!!.contentEquals(source.getStringArray("strings")))
        check(restored.getByteArray("bytes")!!.contentEquals(source.getByteArray("bytes")))
        check(restored.getShortArray("shorts")!!.contentEquals(source.getShortArray("shorts")))
        check(restored.getCharArray("chars")!!.contentEquals(source.getCharArray("chars")))
        check(restored.getIntArray("ints")!!.contentEquals(source.getIntArray("ints")))
        check(restored.getLongArray("longs")!!.contentEquals(source.getLongArray("longs")))
        check(restored.getFloatArray("floats")!!.contentEquals(source.getFloatArray("floats")))
        check(restored.getDoubleArray("doubles")!!.contentEquals(source.getDoubleArray("doubles")))
        check(
            restored.getBooleanArray("booleans")!!.contentEquals(source.getBooleanArray("booleans"))
        )
        check((restored.getParcelableArray("bundles")!![0] as Bundle).getString("a") == "值")
        check(restored.getParcelableArray("uris")!![1] == null)
    }

    private fun uuidState() {
        val id = java.util.UUID.fromString("5be8f746-8d84-4691-a2d3-af5f95ecc3e2")
        val source = Bundle().apply {
            putSerializable("navigation-id", id)
            putSerializable("nested", arrayListOf(id, linkedMapOf(id to "页面")))
        }
        val restored = PageSavedState.decode(PageSavedState.encode(source))
        check(restored.getSerializable("navigation-id") == id)
        check(restored.getSerializable("nested") == source.getSerializable("nested"))
    }

    private fun composeState() {
        val state =
            Bundle().apply {
                putSerializable(
                    "saved",
                    arrayListOf(
                        mutableStateOf("正在输入", neverEqualPolicy()),
                        mutableIntStateOf(17),
                        mutableLongStateOf(Long.MAX_VALUE),
                        mutableFloatStateOf(.5f),
                        mutableDoubleStateOf(.25),
                        linkedMapOf("items" to arrayListOf(1, 2, null)),
                    ),
                )
            }
        val restored = PageSavedState.decode(PageSavedState.encode(state)).get("saved") as List<*>
        val text = restored[0] as androidx.compose.runtime.snapshots.SnapshotMutableState<*>
        check(text.value == "正在输入" && text.policy === neverEqualPolicy<Any?>())
        check((restored[1] as MutableIntState).intValue == 17)
        check((restored[2] as MutableLongState).longValue == Long.MAX_VALUE)
        check((restored[3] as MutableFloatState).floatValue == .5f)
        check((restored[4] as MutableDoubleState).doubleValue == .25)
        check((restored[5] as Map<*, *>)["items"] == listOf(1, 2, null))
    }

    private fun viewState() {
        val views =
            SparseArray<Parcelable?>().apply {
                put(Int.MIN_VALUE, AbsSavedState.EMPTY_STATE)
                put(7, Bundle().apply { putString("text", "原生预览") })
                put(42, null)
            }
        val state =
            Bundle().apply {
                putSparseParcelableArray("direct", views)
                putSerializable("compose", arrayListOf(linkedMapOf("view" to views)))
            }
        val restored = PageSavedState.decode(PageSavedState.encode(state))
        val direct = restored.getSparseParcelableArray<Parcelable>("direct")!!
        check(direct.size() == 3 && direct[Int.MIN_VALUE] === AbsSavedState.EMPTY_STATE)
        check((direct[7] as Bundle).getString("text") == "原生预览" && direct[42] == null)
        val nested =
            ((restored.get("compose") as List<*>)[0] as Map<*, *>)["view"] as SparseArray<*>
        check(nested[Int.MIN_VALUE] === AbsSavedState.EMPTY_STATE)
        rejects {
            PageSavedState.encode(
                Bundle().apply {
                    putSparseParcelableArray(
                        "foreign",
                        SparseArray<Parcelable>().apply { put(1, Intent()) },
                    )
                }
            )
        }
        rejects {
            PageSavedState.decode(
                """[1,["bundle",[["a",["sparse",[[1,["null"]],[1,["null"]]]]]]]]"""
            )
        }
        rejects {
            PageSavedState.decode("""[1,["bundle",[["a",["sparse",[[2147483648,["null"]]]]]]]]""")
        }
    }

    private fun lazyKeys() {
        val generated = getDefaultLazyLayoutKey(7)
        val original =
            Bundle().apply {
                putParcelable("direct", generated as Parcelable)
                putSerializable(
                    "nested",
                    arrayListOf(linkedMapOf(generated to "列表输入", 7 to "显式整数键")),
                )
            }
        val encoded = PageSavedState.encode(original)
        check(!encoded.contains("DefaultLazyKey")) { "默认列表键泄露了实现类" }
        val restored = PageSavedState.decode(encoded)
        check(restored.get("direct") == getDefaultLazyLayoutKey(7))
        val map = (restored.get("nested") as List<*>)[0] as Map<*, *>
        check(map[getDefaultLazyLayoutKey(7)] == "列表输入" && map[7] == "显式整数键")
        rejects {
            PageSavedState.encode(
                Bundle().apply { putParcelable("bad", getDefaultLazyLayoutKey(-1) as Parcelable) }
            )
        }
        rejects { PageSavedState.decode("""[1,["bundle",[["a",["lazy-key",-1]]]]]""") }
        rejects { PageSavedState.decode("""[1,["bundle",[["a",["lazy-key",2147483648]]]]]""") }
        rejects { PageSavedState.encode(Bundle().apply { putParcelable("foreign", Intent()) }) }
    }

    private fun rejects(block: () -> Unit) {
        check(runCatching(block).exceptionOrNull() is Exception) { "无效状态未安全拒绝" }
    }
}
