package app.luoxianlv.library

import java.math.BigDecimal
import java.util.ArrayDeque

object ScoreParser {
    private val tokens =
        Regex(
                "tempo=\\d+(?:\\.\\d+)?|unit=\\d+(?:\\.\\d+)?|:[0-9]+(?:\\.[0-9]+)?|rest|[0-8iIrR休]|[\\[\\]()#+'bB,~|.·-]",
                RegexOption.IGNORE_CASE,
            )
            .toPattern()

    fun tempo(
        text: String,
        fallback: Int = 84,
    ): Int =
        Regex("tempo=(\\d+(?:\\.\\d+)?)")
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.toDouble()
            ?.toInt()
            ?.coerceIn(20, 400) ?: fallback

    fun parse(text: String?): List<NoteEvent> {
        if (text.isNullOrBlank()) return emptyList()
        val source =
            text
                .lineSequence()
                .joinToString(" ") { it.substringBefore("//") }
                .replace("&#x20;", " ")
                .replace("\\", "")
                .replace('【', '[')
                .replace('】', ']')
                .replace('（', '(')
                .replace('）', ')')
        val out = mutableListOf<NoteEvent>()
        val groups = ArrayDeque<PlayMode>()
        var unit = 1.0
        var half = false
        var pending: PlayMode? = null
        // Android 的 MatchResult.next() 会重建 Matcher 并复制完整输入；单 Matcher 顺序扫描长谱。
        val matcher = tokens.matcher(source)
        var end = 0
        while (matcher.find()) {
            require(source.substring(end, matcher.start()).isBlank()) {
                "无法识别谱面：${source.substring(end, matcher.start()).take(20)}"
            }
            end = matcher.end()
            val t = matcher.group().lowercase()
            when {
                t.startsWith("tempo=") -> {
                    Unit
                }

                t.startsWith("unit=") -> {
                    unit = t.substringAfter('=').toDouble()
                    require(unit > 0 && unit.isFinite()) { "默认拍数必须大于 0" }
                }

                t.startsWith(':') -> {
                    require(out.isNotEmpty()) { "拍数前缺少音符" }
                    out[out.lastIndex] = out.last().copy(beats = t.drop(1).toDouble())
                }

                t == "[" -> {
                    groups.push(PlayMode.RAISE)
                }

                t == "(" -> {
                    groups.push(PlayMode.LOWER)
                }

                t == "]" || t == ")" -> {
                    val expected = if (t == "]") PlayMode.RAISE else PlayMode.LOWER
                    require(groups.peek() == expected) { "升降调括号不匹配" }
                    groups.pop()
                }

                t == "#" -> {
                    half = true
                }

                t == "+" -> {
                    pending = PlayMode.RAISE
                }

                t == "b" -> {
                    pending = PlayMode.LOWER
                }

                t == "'" || t == "," -> {
                    require(out.isNotEmpty()) { "高低音标记前缺少音符" }
                    out[out.lastIndex] =
                        out.last().copy(mode = if (t == "'") PlayMode.RAISE else PlayMode.LOWER)
                }

                t == "~" || t == "-" -> {
                    if (t == "-" && source.getOrNull(end)?.let { it in "12345678iI#" } == true) {
                        pending = PlayMode.LOWER
                    } else {
                        require(out.isNotEmpty()) { "延音前缺少音符" }
                        out[out.lastIndex] = out.last().copy(beats = out.last().beats + unit)
                    }
                }

                t in listOf("|", ".", "·") -> {
                    Unit
                }

                else -> {
                    val rest = t in listOf("0", "r", "rest", "休")
                    val key =
                        if (rest) {
                            -1
                        } else if (t == "i" || t == "8") {
                            7
                        } else {
                            t.toInt() - 1
                        }
                    out +=
                        NoteEvent(
                            key,
                            pending ?: groups.peek() ?: PlayMode.NATURAL,
                            unit,
                            rest,
                            !rest && half,
                        )
                    pending = null
                    half = false
                }
            }
            require(out.size <= 100000) { "谱面音符过多" }
        }
        require(source.substring(end).isBlank()) { "谱面末尾有无法识别的文字" }
        require(groups.isEmpty() && !half && pending == null) { "谱面标记不完整" }
        return out
    }

    fun format(
        events: List<NoteEvent>,
        bpm: Int,
    ): String =
        "tempo=$bpm unit=1\n" +
            events.chunked(12).joinToString("\n") { line ->
                line.joinToString(" ") { e ->
                    val value = BigDecimal.valueOf(e.beats).stripTrailingZeros().toPlainString()
                    val note =
                        (if (e.halfTone) "#" else "") +
                            (if (e.keyIndex == 7) "i" else (e.keyIndex + 1).toString()) +
                            ":$value"
                    when {
                        e.rest -> "0:$value"
                        e.mode == PlayMode.RAISE -> "[$note]"
                        e.mode == PlayMode.LOWER -> "($note)"
                        else -> note
                    }
                }
            }
}
