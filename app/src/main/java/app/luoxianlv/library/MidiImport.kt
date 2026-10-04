package app.luoxianlv.library

import java.io.InputStream

internal const val MAX_MIDI_BYTES = 4 * 1024 * 1024

internal fun validateMidiName(name: String) {
    require(name.endsWith(".mid", true) || name.endsWith(".midi", true)) {
        "仅支持 MIDI 文件（.mid / .midi），不支持音频、视频或图片简谱"
    }
}

internal fun readMidi(name: String, stream: InputStream): ByteArray {
    validateMidiName(name)
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = stream.read(buffer)
        if (count < 0) break
        require(count <= MAX_MIDI_BYTES - output.size()) { "文件不能超过 4 MB" }
        output.write(buffer, 0, count)
    }
    val bytes = output.toByteArray()
    require(
        bytes.size >= 14 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(77, 84, 104, 100))
    ) {
        "MIDI 文件头无效，请选择原始 .mid / .midi 文件"
    }
    return bytes
}
