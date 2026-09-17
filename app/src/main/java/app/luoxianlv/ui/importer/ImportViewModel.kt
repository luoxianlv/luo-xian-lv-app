package app.luoxianlv.ui.importer

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.luoxianlv.core.score.ScoreParser
import app.luoxianlv.data.SongRepository
import app.luoxianlv.ui.AppEvents
import app.luoxianlv.ui.syncSelectionToService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ImportUiState(
    val importing: Boolean = false,
    val error: String? = null,
    /** 导入成功的曲名：触发跳转曲库 + Snackbar，消费后置空 */
    val importedTitle: String? = null,
)

class ImportViewModel(
    private val app: Application,
) : AndroidViewModel(app) {
    private val repository = SongRepository(app)
    private val _state = MutableStateFlow(ImportUiState())
    val state = _state.asStateFlow()

    fun import(uri: Uri) {
        _state.update { it.copy(importing = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val read =
                runCatching {
                    val resolver = app.contentResolver
                    val name =
                        resolver
                            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { if (it.moveToFirst()) it.getString(0) else null }
                            ?: "导入乐曲"
                    val bytes =
                        resolver.openInputStream(uri)?.use { stream ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val size = stream.read(buffer)
                                if (size < 0) break
                                require(output.size() + size <= 4 * 1024 * 1024) { "文件不能超过 4 MB" }
                                output.write(buffer, 0, size)
                            }
                            output.toByteArray()
                        } ?: error("无法读取文件")
                    name to bytes
                }
            read
                .onSuccess { (name, bytes) ->
                    val imported =
                        runCatching {
                            if (bytes.take(4) == listOf<Byte>(77, 84, 104, 100)) {
                                repository.addMidi(name.substringBeforeLast('.'), bytes)
                            } else {
                                require(!name.endsWith(".mid", true) && !name.endsWith(".midi", true)) { "MIDI 文件头无效" }
                                val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
                                repository.add(name.substringBeforeLast('.'), text, ScoreParser.tempo(text), "简谱")
                            }
                        }
                    imported
                        .onSuccess { song ->
                            syncSelectionToService(song)
                            // 导入会改变歌单，曲库页需要重新读取
                            AppEvents.notifyLibraryChanged()
                            _state.update { it.copy(importing = false, importedTitle = song.title) }
                        }.onFailure { e ->
                            _state.update { it.copy(importing = false, error = e.message ?: "文件格式无效") }
                        }
                }.onFailure { e ->
                    _state.update { it.copy(importing = false, error = e.message ?: "无法读取文件") }
                }
        }
    }

    fun ackImported() = _state.update { it.copy(importedTitle = null) }

    fun dismissError() = _state.update { it.copy(error = null) }
}
