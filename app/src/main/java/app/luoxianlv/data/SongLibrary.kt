package app.luoxianlv.data

/** 内置校准曲；其他内置乐曲从原始 MIDI 资源加载。 */
object SongLibrary {
    private const val DEMO = "1:1 2:1 3:1 4:1 5:1 6:1 7:1 i:1 7:1 6:1 5:1 4:1 3:1 2:1 1:1"
    val builtIns = listOf(Song("scale", "测试音阶", DEMO, 84, "校准", true))
}
