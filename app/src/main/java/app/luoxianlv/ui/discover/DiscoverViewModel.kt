package app.luoxianlv.ui.discover

import android.app.Application
import android.os.SystemClock
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
    /** 已加载页（含预取）；visibleCount 之后的记录暂不展示。 */
    val scores: List<PlatformScore> = emptyList(),
    val visibleCount: Int = 0,
    val nextPage: Int? = null,
    val loadingMore: Boolean = false,
    val downloading: Set<String> = emptySet(),
    val error: String? = null,
    /** 下载成功只提示，不重载列表或改变导航；曲库通过 AppEvents 单独刷新。 */
    val downloaded: String? = null,
) {
    /** 当前实际渲染到列表的条目 */
    val visibleScores: List<PlatformScore>
        get() = scores.take(visibleCount)

    /** 内存里还有没渲染出来的条目 */
    val hasMore: Boolean
        get() = visibleCount < scores.size || nextPage != null
}

/** 发现 / 搜索 / 平台三页共用：平台乐谱的加载与下载。UpdateManager 回调在工作线程，StateFlow 线程安全。 */
class DiscoverViewModel(
    app: Application,
) : AndroidViewModel(app) {
    companion object {
        /** 列表每批渲染的条数，滑近底部再追加下一批 */
        const val PAGE_SIZE = 30

        /** 发现页数据缓存有效期：TTL 内切回发现页直接复用缓存，不再发请求 */
        const val SCORES_CACHE_TTL_MS = 5 * 60 * 1000L
    }

    private val updater = UpdateManager(app)
    private val repository = SongRepository(app)
    private val sessionStore = SessionStore(app)
    private val _state = MutableStateFlow(RemoteUiState())
    val state = _state.asStateFlow()

    // 发现页数据缓存（随 ViewModel/Activity 生命周期，不跨进程）。
    // 必须独立于 state.scores 单独存：搜索/平台页与发现页共用 state，
    // 搜索后切回发现页时 state.scores 已是搜索结果，要靠快照恢复发现页列表。
    private var scoresLoadedAtMs = 0L
    private var discoverFeedCache: List<PlatformScore>? = null
    private var cachedNextPage: Int? = null
    private var cachedTotal = 0
    private var requestGeneration = 0
    private var discoverActive = false
    private var revealOnArrival = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun token() = sessionStore.current()?.accessToken

    /** 首屏展示一页，后台最多提前准备两页。 */
    private fun revealFirstPage(total: List<PlatformScore>): Int = minOf(PAGE_SIZE, total.size)

    /** 滚动先消费缓存，再补齐两页预取窗口。 */
    fun loadMore() {
        val s = _state.value
        if (s.loading || !s.hasMore) return
        if (s.visibleCount < s.scores.size) {
            _state.update { it.copy(visibleCount = (it.visibleCount + PAGE_SIZE).coerceAtMost(it.scores.size)) }
        } else {
            revealOnArrival = true
        }
        _state.update { it.copy(error = null) }
        prefetch()
    }

    private fun prefetch() {
        val s = _state.value
        if (!discoverActive || s.loading || s.loadingMore || s.error != null ||
            s.scores.size - s.visibleCount >= PAGE_SIZE * 2) return
        val next = s.nextPage ?: return
        val generation = requestGeneration
        _state.update { it.copy(loadingMore = true) }
        updater.fetchLatestScores(token(), next) { result -> main.post {
            if (generation != requestGeneration) return@post
            result.onSuccess { page ->
                val current = _state.value
                val merged = (current.scores + page.items).distinctBy { it.id }
                discoverFeedCache = merged
                cachedNextPage = page.nextPage.takeIf { merged.size > current.scores.size }
                val visible = if (revealOnArrival) (current.visibleCount + PAGE_SIZE).coerceAtMost(merged.size) else current.visibleCount
                revealOnArrival = false
                _state.update { it.copy(scores = merged, visibleCount = visible, nextPage = cachedNextPage, loadingMore = false) }
                prefetch()
            }.onFailure { e ->
                _state.update { it.copy(loadingMore = false, error = e.message ?: "加载失败，请重试") }
            }
        } }
    }

    /**
     * 发现页分页加载，首屏后预取两页。
     * 带生命周期缓存：TTL 内重复调用（切 Tab 重建页面导致的 LaunchedEffect 重跑）
     * 直接恢复缓存快照，不重复请求；过期或 [force] 才重新拉取。
     * 拉取失败保留旧列表，下次进入再试。
     */
    fun loadScores(force: Boolean = false) {
        if (discoverActive && _state.value.loading && !force) return
        discoverActive = true
        revealOnArrival = false
        val generation = ++requestGeneration
        val cache = discoverFeedCache
        val fresh =
            cache != null &&
                SystemClock.elapsedRealtime() - scoresLoadedAtMs < SCORES_CACHE_TTL_MS
        if (!force && fresh) {
            _state.update {
                it.copy(
                    loading = false,
                    scores = cache,
                    visibleCount = revealFirstPage(cache),
                    status = "共 $cachedTotal 首公开谱子",
                    nextPage = cachedNextPage,
                    loadingMore = false,
                    error = null,
                )
            }
            prefetch()
            return
        }
        _state.update { it.copy(loading = true, loadingMore = false, nextPage = null, error = null, status = "正在加载…") }
        updater.fetchLatestScores(token()) { result -> main.post {
            if (generation != requestGeneration) return@post
            result
                .onSuccess { page ->
                    val scores = page.items
                    cachedNextPage = page.nextPage
                    cachedTotal = page.total
                    scoresLoadedAtMs = SystemClock.elapsedRealtime()
                    discoverFeedCache = scores
                    _state.update {
                        it.copy(
                            loading = false,
                            scores = scores,
                            visibleCount = revealFirstPage(scores),
                            status = if (scores.isEmpty()) "暂无公开谱子" else "共 ${page.total} 首公开谱子",
                            nextPage = page.nextPage,
                        )
                    }
                    prefetch()
                }.onFailure { e ->
                    _state.update { it.copy(loading = false, status = e.message ?: "曲库暂时无法连接") }
                }
        } }
    }

    fun search(query: String) {
        if (query.isBlank()) return
        discoverActive = false
        val generation = ++requestGeneration
        _state.update { it.copy(loading = true, status = "搜索中…", scores = emptyList(), visibleCount = 0) }
        _state.update { it.copy(nextPage = null, loadingMore = false) }
        updater.fetchPublicScores(query.trim(), token()) { result -> main.post {
            if (generation != requestGeneration) return@post
            result
                .onSuccess { scores ->
                    _state.update {
                        it.copy(
                            loading = false,
                            scores = scores,
                            visibleCount = revealFirstPage(scores),
                            status = if (scores.isEmpty()) "没有找到相关谱子" else "找到 ${scores.size} 首",
                        )
                    }
                }.onFailure { e ->
                    _state.update { it.copy(loading = false, status = e.message ?: "搜索失败") }
                }
        } }
    }

    fun loadPublic(query: String = "") {
        discoverActive = false
        val generation = ++requestGeneration
        _state.update { it.copy(nextPage = null, loadingMore = false) }
        _state.update { it.copy(loading = true, status = "正在加载公开谱子…") }
        updater.fetchPublicScores(query, token()) { result -> main.post {
            if (generation != requestGeneration) return@post
            result
                .onSuccess { scores ->
                    _state.update {
                        it.copy(
                            loading = false,
                            scores = scores,
                            visibleCount = revealFirstPage(scores),
                            status = if (scores.isEmpty()) "暂无公开谱子" else "公开谱子 · ${scores.size} 首",
                        )
                    }
                }.onFailure { e ->
                    _state.update { it.copy(loading = false, status = "平台暂时无法连接：${e.message ?: "网络错误"}") }
                }
        } }
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
