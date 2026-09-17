package app.luoxianlv.ui.discover

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import app.luoxianlv.data.SessionStore
import app.luoxianlv.data.SongRepository
import app.luoxianlv.ui.AppEvents
import app.luoxianlv.ui.syncSelectionToService
import app.luoxianlv.update.PlatformScore
import app.luoxianlv.update.UpdateManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class RemoteUiState(
    val loading: Boolean = false,
    val status: String = "",
    val scores: List<PlatformScore> = emptyList(),
    val downloading: Set<String> = emptySet(),
    val error: String? = null,
    /** 下载成功的曲名：触发跳转曲库 + Snackbar，消费后置空 */
    val downloaded: String? = null,
)

/** 发现 / 搜索 / 平台三页共用：平台乐谱的加载与下载。UpdateManager 回调在工作线程，StateFlow 线程安全。 */
class DiscoverViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val updater = UpdateManager(app)
    private val repository = SongRepository(app)
    private val sessionStore = SessionStore(app)
    private val _state = MutableStateFlow(RemoteUiState())
    val state = _state.asStateFlow()

    private fun token() = sessionStore.current()?.accessToken

    fun loadPopular() {
        _state.update { it.copy(loading = true, status = "正在加载…") }
        updater.fetchPopularScores(token()) { result ->
            result
                .onSuccess { scores ->
                    _state.update {
                        it.copy(
                            loading = false,
                            scores = scores.take(12),
                            status = if (scores.isEmpty()) "暂无公开谱子" else "按热度更新",
                        )
                    }
                }.onFailure { e ->
                    _state.update { it.copy(loading = false, status = e.message ?: "曲库暂时无法连接") }
                }
        }
    }

    fun search(query: String) {
        if (query.isBlank()) return
        _state.update { it.copy(loading = true, status = "搜索中…", scores = emptyList()) }
        updater.fetchPublicScores(query.trim(), token()) { result ->
            result
                .onSuccess { scores ->
                    _state.update {
                        it.copy(
                            loading = false,
                            scores = scores,
                            status = if (scores.isEmpty()) "没有找到相关谱子" else "找到 ${scores.size} 首",
                        )
                    }
                }.onFailure { e ->
                    _state.update { it.copy(loading = false, status = e.message ?: "搜索失败") }
                }
        }
    }

    fun loadPublic(query: String = "") {
        _state.update { it.copy(loading = true, status = "正在加载公开谱子…") }
        updater.fetchPublicScores(query, token()) { result ->
            result
                .onSuccess { scores ->
                    _state.update {
                        it.copy(
                            loading = false,
                            scores = scores,
                            status = if (scores.isEmpty()) "暂无公开谱子" else "公开谱子 · ${scores.size} 首",
                        )
                    }
                }.onFailure { e ->
                    _state.update { it.copy(loading = false, status = "平台暂时无法连接：${e.message ?: "网络错误"}") }
                }
        }
    }

    fun download(remote: PlatformScore) {
        _state.update { it.copy(downloading = it.downloading + remote.id) }
        updater.downloadPublicScore(remote.id) { result ->
            _state.update { it.copy(downloading = it.downloading - remote.id) }
            result
                .onSuccess { notation ->
                    runCatching { repository.add(remote.title, notation, remote.bpm, "平台下载") }
                        .onSuccess { song ->
                            syncSelectionToService(song)
                            // 下载会改变歌单，曲库页需要重新读取
                            AppEvents.notifyLibraryChanged()
                            _state.update { it.copy(downloaded = song.title) }
                        }.onFailure { e -> _state.update { it.copy(error = e.message ?: "未能完成") } }
                }.onFailure { e -> _state.update { it.copy(error = e.message ?: "未能完成") } }
        }
    }

    fun ackDownloaded() = _state.update { it.copy(downloaded = null) }

    fun dismissError() = _state.update { it.copy(error = null) }
}
