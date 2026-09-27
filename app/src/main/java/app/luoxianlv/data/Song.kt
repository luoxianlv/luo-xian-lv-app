package app.luoxianlv.data

import app.luoxianlv.core.score.NoteEvent
import app.luoxianlv.core.score.ScoreParser

data class Song(
    val id: String,
    val title: String,
    val score: String,
    val bpm: Int,
    val source: String,
    val builtIn: Boolean = false,
    val contentVersion: Long = 0L,
    val synced: Boolean = false,
    /** 平台曲库 id（/api/scores/latest 里的 id）：批量重编靠它回源取 MIDI。 */
    val remoteId: String = "",
    /** 编译该曲时服务端 MIDI 核心的版本号（如 "1.0.0"）；非 MIDI 来源为空串。 */
    val coreVersion: String = "",
    /** 远端重编连续失败（已达重试上限）：列表显示「需要修复」，播放前会先尝试自动修复。 */
    val needsFix: Boolean = false,
) {
    /**
     * 谱面事件：解析失败退化为空谱面，**绝不向上抛**。
     *
     * 解析是惰性的，而列表行渲染要读 [durationMs]（→ 这里），[ScoreParser] 对不合法 谱面抛
     * IllegalArgumentException；一旦抛在组合期，整个曲库页直接闪退。 坏谱面（导入时未校验的 MIDI 编译结果、热更下发、旧版本写入的旧格式）
     * 以后只表现为「0:00、播放不起来」，不再把 App 带崩。
     *
     * 空事件在播放侧是安全的：[PlaybackTimeline] 的 offsets 恒有 events.size + 1 个元素（durationMs 为
     * 0），MusicAccessibilityService.play() 也会在 events 为空时早退。
     */
    val events: List<NoteEvent> by lazy {
        runCatching { trimLeadIn(ScoreParser.parse(score), bpm) }.getOrDefault(emptyList())
    }
    val durationMs: Long
        get() = (events.sumOf { it.beats } * 60000 / bpm).toLong()

    val noteCount: Int
        get() = events.count { !it.rest }

    /**
     * 是否由 MIDI 编译而来。
     *
     * 同时决定列表图标与「MIDI / 简谱」筛选归类： source 的取值（"MIDI · Rust 编译" / "MIDI · 引擎编译" / "简谱" / "平台下载" …）
     * 都由本文件写入，所以判断规则也放在这里，界面不再自己拼字符串。
     */
    val isMidi: Boolean
        get() = source.startsWith("MIDI")
}

data class SyncApplyResult(
    val applied: Int,
    val skipped: Int,
    val failed: Int,
)

/**
 * 老缓存播放兜底：旧版服务端编译不剪 MIDI 开头空白，坏结果已经以「前导休止符」 固化在本地缓存的谱面文本里。播放/显示时长前把超过 [MAX_LEAD_IN_MS] 的前导 休止整段剪掉（与
 * [app.luoxianlv.core.harmonica.MAX_LEAD_IN_US] 同一阈值）， 重编完成前的过渡期用户不必干等几十秒空白。
 */
private const val MAX_LEAD_IN_MS = 5_000L

internal fun trimLeadIn(
    events: List<NoteEvent>,
    bpm: Int,
): List<NoteEvent> {
    var leadMs = 0L
    var index = 0
    while (index < events.size && events[index].rest) {
        leadMs += (events[index].beats * 60000 / bpm.coerceAtLeast(1)).toLong()
        index++
    }
    return if (index > 0 && leadMs > MAX_LEAD_IN_MS) events.drop(index) else events
}

fun timeLabel(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
