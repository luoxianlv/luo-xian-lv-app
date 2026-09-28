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
     * 惰性解析谱面，失败返回空事件，避免 [durationMs] 在组合期间触发崩溃。空谱时长为 0；PlaybackTimeline 仍保留 events.size + 1
     * 个偏移，播放端直接返回。
     */
    val events: List<NoteEvent> by lazy {
        runCatching { trimLeadIn(ScoreParser.parse(score), bpm) }.getOrDefault(emptyList())
    }
    val durationMs: Long
        get() = (events.sumOf { it.beats } * 60000 / bpm).toLong()

    val noteCount: Int
        get() = events.count { !it.rest }

    /** 按 source 的 MIDI 前缀统一判定来源，供列表图标和筛选使用。 */
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
