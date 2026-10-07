package app.luoxianlv.practice

import java.util.concurrent.ArrayBlockingQueue

/** UI 事件按序交给音频线程；每个起音至少渲染一块，快速松键不会覆盖它。 */
internal class HarmonicaCommands {
    private data class Command(val midi: Int?)

    private val commands = ArrayBlockingQueue<Command>(32)

    @Synchronized
    fun submit(midi: Int?) {
        val command = Command(midi)
        if (!commands.offer(command)) {
            // 输入异常突发时保留最新意图，松键始终能入队，不积压过时演奏。
            commands.clear()
            commands.offer(command)
        }
    }

    fun apply(voice: HarmonicaVoice) {
        while (true) {
            val command = commands.poll() ?: return
            if (command.midi == null) {
                voice.noteOff()
            } else {
                voice.noteOn(command.midi)
                return
            }
        }
    }
}
