package app.luoxianlv.wallpaper.render

/** 只锁缓存引用；读取期间清理不会等待磁盘，也不会被旧任务重新填满。 */
internal class PreviewByteCache {
    private data class Entry(val key: String, val bytes: ByteArray)

    private val lock = Any()
    private var generation = 0L
    private var entry: Entry? = null

    fun load(key: () -> String, read: () -> ByteArray): ByteArray {
        val started = synchronized(lock) { generation }
        val requested = key()
        synchronized(lock) {
            if (generation == started)
                entry
                    ?.takeIf { it.key == requested }
                    ?.let {
                        return it.bytes
                    }
        }
        val bytes = read()
        synchronized(lock) {
            if (generation == started) entry = Entry(requested, bytes)
        }
        return bytes
    }

    fun clear() {
        synchronized(lock) {
            generation++
            entry = null
        }
    }
}
