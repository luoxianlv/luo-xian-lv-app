package app.luoxianlv

import app.luoxianlv.library.MAX_MIDI_BYTES
import app.luoxianlv.library.readMidi
import app.luoxianlv.library.validateMidiName
import org.junit.Assert.*
import org.junit.Test

class MidiImportTest {
    private val midi = byteArrayOf(77, 84, 104, 100, 0, 0, 0, 6, 0, 0, 0, 1, 1, 64)

    @Test
    fun acceptsCaseInsensitiveMidiNames() {
        assertArrayEquals(midi, readMidi("song.MIDI", midi.inputStream()))
    }

    @Test
    fun rejectsUnsupportedFilesBeforeReading() {
        for (name in listOf("song.mp3", "video.mp4", "score.png", "score.txt", "song.mid.exe")) {
            assertThrows(IllegalArgumentException::class.java) { validateMidiName(name) }
        }
    }

    @Test
    fun rejectsRenamedAndTruncatedFiles() {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(77, 84, 104, 100), ByteArray(14))) {
            assertThrows(IllegalArgumentException::class.java) {
                readMidi("song.mid", bytes.inputStream())
            }
        }
    }

    @Test
    fun rejectsOversizedMidi() {
        assertThrows(IllegalArgumentException::class.java) {
            readMidi("song.mid", ByteArray(MAX_MIDI_BYTES + 1).inputStream())
        }
    }
}
