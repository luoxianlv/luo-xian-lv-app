package app.luoxianlv.ui.importer

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.luoxianlv.business.BusinessJobs
import app.luoxianlv.data.SongRepository
import app.luoxianlv.ui.AppEvents
import app.luoxianlv.ui.syncSelectionToService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ImportUiState(
    val importing: Boolean = false,
    val error: String? = null,
    /** 导入成功的曲名：触发跳转曲库 + Snackbar，消费后置空 */
    val importedTitle: String? = null,
)

class ImportViewModel(private val app: Application) : AndroidViewModel(app) {
    private val repository = SongRepository(app)
    private val _state = MutableStateFlow(ImportUiState())
    val state = _state.asStateFlow()

    fun import(uri: Uri) {
        if (_state.value.importing) return
        _state.update { it.copy(importing = true, error = null) }
        viewModelScope.launch {
            BusinessJobs.io {
                val read = runCatching {
                    val resolver = app.contentResolver
                    val name =
                        resolver
                            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "导入乐曲"
                    validateMidiName(name)
                    val bytes =
                        resolver.openInputStream(uri)?.use { stream ->
                            readMidi(name, stream)
                        } ?: error("无法读取文件")
                    name to bytes
                }
                read
                    .onSuccess { (name, bytes) ->
                        val imported = runCatching {
                            repository.addMidi(name.substringBeforeLast('.'), bytes)
                        }
                        imported
                            .onSuccess { song ->
                                withContext(Dispatchers.Main) { syncSelectionToService(song) }
                                // 导入会改变歌单，曲库页需要重新读取
                                AppEvents.notifyLibraryChanged()
                                _state.update {
                                    it.copy(importing = false, importedTitle = song.title)
                                }
                            }
                            .onFailure { e ->
                                if (e is CancellationException) throw e
                                _state.update {
                                    it.copy(importing = false, error = e.message ?: "文件格式无效")
                                }
                            }
                    }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        _state.update { it.copy(importing = false, error = e.message ?: "无法读取文件") }
                    }
            }
        }
    }

    fun ackImported() = _state.update { it.copy(importedTitle = null) }

    fun dismissError() = _state.update { it.copy(error = null) }
}
