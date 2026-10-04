package app.luoxianlv.business.ui

import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.util.SparseArray
import android.view.AbsSavedState
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotMutableState
import org.json.JSONArray

/** 仅导出版本化基础值；新代际用自己的 Compose 类型重建状态，不接收旧类的序列化数据。 */
internal object PageSavedState {
    private const val MAX_TEXT = 30000
    private const val MAX_ITEMS = 2048
    private const val MAX_DEPTH = 32

    fun encode(state: Bundle): String =
        JSONArray().put(1).put(Codec().encode(state, 0)).toString().also {
            require(it.length <= MAX_TEXT) { "页面保存状态过大" }
        }

    fun decode(text: String): Bundle {
        require(text.length <= MAX_TEXT) { "页面保存状态过大" }
        // 在平台 JSON 解析器递归之前限制深度，避免异常状态耗尽调用栈。
        var quoted = false
        var escaped = false
        var depth = 0
        text.forEach { char ->
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else
                when (char) {
                    '"' -> quoted = true
                    '[',
                    '{' -> require(++depth <= MAX_DEPTH * 3 + 2) { "页面状态嵌套过深" }
                    ']',
                    '}' -> require(--depth >= 0) { "页面状态结构无效" }
                }
        }
        require(!quoted && depth == 0) { "页面状态结构无效" }
        val envelope = JSONArray(text)
        require(envelope.length() == 2 && envelope.get(0) == 1) { "页面状态版本不支持" }
        return Codec().decode(envelope.getJSONArray(1), 0) as? Bundle ?: error("页面状态根节点无效")
    }

    private class Codec {
        private var items = 0
        private var characters = 0

        private fun visit(depth: Int) {
            require(depth < MAX_DEPTH && ++items <= MAX_ITEMS) { "页面状态过深或项目过多" }
        }

        private fun text(value: String): String {
            require(value.length <= MAX_TEXT - characters) { "页面保存状态过大" }
            characters += value.length
            return value
        }

        private fun count(size: Int) {
            require(size <= MAX_ITEMS - items) { "页面状态项目过多" }
            items += size
        }

        fun encode(value: Any?, depth: Int): JSONArray {
            visit(depth)
            fun node(type: String, vararg values: Any?) =
                JSONArray().put(type).apply { values.forEach(::put) }
            fun collection(type: String, values: Iterable<Any?>) =
                node(type, JSONArray().apply { values.forEach { put(encode(it, depth + 1)) } })
            return when (value) {
                null -> node("null")
                is String -> node("string", text(value))
                is Boolean -> node("boolean", value)
                is Byte -> node("byte", value.toInt())
                is Short -> node("short", value.toInt())
                is Char -> node("char", value.code)
                is Int -> node("int", value)
                is Long -> node("long", value.toString())
                is Float -> {
                    require(value.isFinite())
                    node("float", value.toString())
                }
                is Double -> {
                    require(value.isFinite())
                    node("double", value.toString())
                }
                is Uri -> node("uri", text(value.toString()))
                is MutableIntState -> node("int-state", value.intValue)
                is MutableLongState -> node("long-state", value.longValue.toString())
                is MutableFloatState -> {
                    require(value.floatValue.isFinite())
                    node("float-state", value.floatValue.toString())
                }
                is MutableDoubleState -> {
                    require(value.doubleValue.isFinite())
                    node("double-state", value.doubleValue.toString())
                }
                is SnapshotMutableState<*> -> {
                    val policy =
                        when (value.policy) {
                            structuralEqualityPolicy<Any?>() -> "structural"
                            referentialEqualityPolicy<Any?>() -> "referential"
                            neverEqualPolicy<Any?>() -> "never"
                            else -> error("页面状态包含未声明的状态策略")
                        }
                    node("state", policy, encode(value.value, depth + 1))
                }
                is Bundle ->
                    node(
                        "bundle",
                        JSONArray().apply {
                            count(value.size())
                            value.keySet().forEach { key ->
                                put(
                                    JSONArray()
                                        .put(text(key))
                                        .put(encode(value.get(key), depth + 1))
                                )
                            }
                        },
                    )
                is List<*> -> collection("list", value)
                is Map<*, *> ->
                    node(
                        "map",
                        JSONArray().apply {
                            value.forEach { (key, item) ->
                                put(
                                    JSONArray()
                                        .put(encode(key, depth + 1))
                                        .put(encode(item, depth + 1))
                                )
                            }
                        },
                    )
                is SparseArray<*> ->
                    node(
                        "sparse",
                        JSONArray().apply {
                            count(value.size())
                            repeat(value.size()) { index ->
                                put(
                                    JSONArray()
                                        .put(value.keyAt(index))
                                        .put(encode(value.valueAt(index), depth + 1))
                                )
                            }
                        },
                    )
                is AbsSavedState -> {
                    // 原生预览控件通常只有空状态；有自定义 Parcelable 的控件仍须提供基础值 Saver。
                    require(value === AbsSavedState.EMPTY_STATE) { "原生控件状态需要基础值 Saver" }
                    node("view-empty")
                }
                is ByteArray -> collection("bytes", value.asIterable())
                is ShortArray -> collection("shorts", value.asIterable())
                is CharArray -> collection("chars", value.asIterable())
                is IntArray -> collection("ints", value.asIterable())
                is LongArray -> collection("longs", value.asIterable())
                is FloatArray -> collection("floats", value.asIterable())
                is DoubleArray -> collection("doubles", value.asIterable())
                is BooleanArray -> collection("booleans", value.asIterable())
                is Array<*> -> {
                    val kind =
                        when (value.javaClass.componentType) {
                            String::class.java -> "strings"
                            Bundle::class.java -> "bundles"
                            Uri::class.java -> "uris"
                            Any::class.java -> "array"
                            else -> error("页面状态数组需要基础值 Saver")
                        }
                    collection(kind, value.asIterable())
                }
                else -> error("页面状态需要基础值 Saver：${value.javaClass.name}")
            }
        }

        fun decode(node: JSONArray, depth: Int): Any? {
            visit(depth)
            val type = node.getString(0)
            require(
                node.length() ==
                    when (type) {
                        "null",
                        "view-empty" -> 1
                        "state" -> 3
                        else -> 2
                    }
            )
            fun string() = text(node.get(1) as? String ?: error("页面状态文本无效"))
            fun integer(
                min: Long = Int.MIN_VALUE.toLong(),
                max: Long = Int.MAX_VALUE.toLong(),
            ): Int {
                val number = node.get(1)
                require(number is Int || number is Long)
                val result = (number as Number).toLong()
                require(result in min..max)
                return result.toInt()
            }
            fun float() = string().toFloat().also { require(it.isFinite()) }
            fun double() = string().toDouble().also { require(it.isFinite()) }
            fun list(): List<Any?> {
                val array = node.getJSONArray(1)
                require(array.length() <= MAX_ITEMS - items)
                return List(array.length()) { decode(array.getJSONArray(it), depth + 1) }
            }
            return when (type) {
                "null" -> null
                "view-empty" -> AbsSavedState.EMPTY_STATE
                "string" -> string()
                "boolean" -> node.get(1).also { require(it is Boolean) }
                "byte" -> integer(Byte.MIN_VALUE.toLong(), Byte.MAX_VALUE.toLong()).toByte()
                "short" -> integer(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong()).toShort()
                "char" -> integer(0, Char.MAX_VALUE.code.toLong()).toChar()
                "int" -> integer()
                "long" -> string().toLong()
                "float" -> float()
                "double" -> double()
                "uri" -> Uri.parse(string())
                "int-state" -> mutableIntStateOf(integer())
                "long-state" -> mutableLongStateOf(string().toLong())
                "float-state" -> mutableFloatStateOf(float())
                "double-state" -> mutableDoubleStateOf(double())
                "state" ->
                    mutableStateOf(
                        decode(node.getJSONArray(2), depth + 1),
                        when (node.getString(1)) {
                            "structural" -> structuralEqualityPolicy()
                            "referential" -> referentialEqualityPolicy()
                            "never" -> neverEqualPolicy()
                            else -> error("页面状态策略无效")
                        },
                    )
                "list" -> ArrayList(list())
                "array" -> list().toTypedArray()
                "strings" -> list().map { it as String? }.toTypedArray()
                "bundles" -> list().map { it as Bundle? }.toTypedArray()
                "uris" -> list().map { it as Uri? }.toTypedArray()
                "bytes" -> list().map { it as Byte }.toByteArray()
                "shorts" -> list().map { it as Short }.toShortArray()
                "chars" -> list().map { it as Char }.toCharArray()
                "ints" -> list().map { it as Int }.toIntArray()
                "longs" -> list().map { it as Long }.toLongArray()
                "floats" -> list().map { it as Float }.toFloatArray()
                "doubles" -> list().map { it as Double }.toDoubleArray()
                "booleans" -> list().map { it as Boolean }.toBooleanArray()
                "bundle" ->
                    Bundle().apply {
                        val entries = node.getJSONArray(1)
                        count(entries.length())
                        repeat(entries.length()) {
                            val pair = entries.getJSONArray(it)
                            require(pair.length() == 2)
                            val key = text(pair.getString(0))
                            require(!containsKey(key)) { "页面状态键重复" }
                            putValue(this, key, decode(pair.getJSONArray(1), depth + 1))
                        }
                    }
                "map" ->
                    linkedMapOf<Any?, Any?>().apply {
                        val entries = node.getJSONArray(1)
                        require(entries.length() <= (MAX_ITEMS - items) / 2)
                        repeat(entries.length()) {
                            val pair = entries.getJSONArray(it)
                            require(pair.length() == 2)
                            val key = decode(pair.getJSONArray(0), depth + 1)
                            require(!containsKey(key)) { "页面状态键重复" }
                            put(key, decode(pair.getJSONArray(1), depth + 1))
                        }
                    }
                "sparse" ->
                    SparseArray<Any?>().apply {
                        val entries = node.getJSONArray(1)
                        count(entries.length())
                        repeat(entries.length()) {
                            val pair = entries.getJSONArray(it)
                            require(pair.length() == 2)
                            val key = pair.get(0)
                            require(key is Int) { "原生控件状态键无效" }
                            require(indexOfKey(key) < 0) { "原生控件状态键重复" }
                            put(key, decode(pair.getJSONArray(1), depth + 1))
                        }
                    }
                else -> error("页面状态类型不支持")
            }
        }
    }

    private fun putValue(bundle: Bundle, key: String, value: Any?) {
        when (value) {
            null -> bundle.putString(key, null)
            is String -> bundle.putString(key, value)
            is Boolean -> bundle.putBoolean(key, value)
            is Byte -> bundle.putByte(key, value)
            is Short -> bundle.putShort(key, value)
            is Char -> bundle.putChar(key, value)
            is Int -> bundle.putInt(key, value)
            is Long -> bundle.putLong(key, value)
            is Float -> bundle.putFloat(key, value)
            is Double -> bundle.putDouble(key, value)
            is Bundle -> bundle.putBundle(key, value)
            is Uri -> bundle.putParcelable(key, value)
            is AbsSavedState -> bundle.putParcelable(key, value)
            is SparseArray<*> -> {
                require(
                    (0 until value.size()).all {
                        value.valueAt(it) == null || value.valueAt(it) is Parcelable
                    }
                )
                @Suppress("UNCHECKED_CAST")
                bundle.putSparseParcelableArray(key, value as SparseArray<Parcelable?>)
            }
            is ByteArray -> bundle.putByteArray(key, value)
            is ShortArray -> bundle.putShortArray(key, value)
            is CharArray -> bundle.putCharArray(key, value)
            is IntArray -> bundle.putIntArray(key, value)
            is LongArray -> bundle.putLongArray(key, value)
            is FloatArray -> bundle.putFloatArray(key, value)
            is DoubleArray -> bundle.putDoubleArray(key, value)
            is BooleanArray -> bundle.putBooleanArray(key, value)
            // 只在当前业务内部交还 SavedStateRegistry；对外始终是一段有界 JSON。
            is ArrayList<*> -> bundle.putSerializable(key, value)
            is Map<*, *> -> bundle.putSerializable(key, LinkedHashMap(value))
            is Array<*> ->
                when (value.javaClass.componentType) {
                    String::class.java -> {
                        @Suppress("UNCHECKED_CAST")
                        bundle.putStringArray(key, value as Array<String?>)
                    }
                    Bundle::class.java,
                    Uri::class.java -> {
                        @Suppress("UNCHECKED_CAST")
                        bundle.putParcelableArray(key, value as Array<android.os.Parcelable?>)
                    }
                    else -> bundle.putSerializable(key, value)
                }
            else -> error("保存状态顶层不是基础值")
        }
    }
}
