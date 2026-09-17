package app.luoxianlv.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.luoxianlv.core.score.ScoreParser
import app.luoxianlv.data.Song
import app.luoxianlv.data.SongRepository
import app.luoxianlv.service.MusicAccessibilityService
import app.luoxianlv.ui.AppEvents
import app.luoxianlv.ui.syncSelectionToService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 曲库筛选。
 *
 * 归类规则（[Song.isMidi]）收在数据层，界面不再用魔法数字判断：
 * 原实现是 `filter == 0 || (filter == 1) == song.source.startsWith("MIDI")`。
 */
enum class SongFilter(
    val label: String,
) {
    All("全部"),
    Midi("MIDI"),
    Notation("简谱"),
    ;

    fun matches(song: Song): Boolean =
        when (this) {
            All -> true
            Midi -> song.isMidi
            Notation -> !song.isMidi
        }
}

/**
 * 无障碍服务的实时状态。
 *
 * 与曲库数据分开取：原来两者挤在同一个状态对象里，
 * 导致每 500ms 的轮询会把用户刚点的选中项改写成服务里的曲目。
 */
data class ServiceStatus(
    val connected: Boolean = false,
    val error: String? = null,
    val activeSongId: String = "",
)

data class LibraryUiState(
    val songs: List<Song> = emptyList(),
    /** 持久化的“上次选择”，服务未连接时用它决定高亮 */
    val persistedSelectedId: String = "",
    val filter: SongFilter = SongFilter.All,
    val floatingEnabled: Boolean = true,
    val service: ServiceStatus = ServiceStatus(),
    val showAccessibilityPrompt: Boolean = false,
    val error: String? = null,
    /** 一次性提示（删除 / 另存为结果），消费后置空 */
    val notice: String? = null,
) {
    /** 当前筛选下的曲目：筛选逻辑属于状态，界面只负责渲染。 */
    val visibleSongs: List<Song> get() = songs.filter(filter::matches)

    /**
     * 列表高亮项：以服务实际载入的曲目为准，服务未连接时退回持久化的选择。
     * 原实现用 `service?.song?.id ?: it.selectedId`，服务断开后会把上一次的
     * 服务曲目一直留在高亮位上，与持久化选择不一致。
     */
    val highlightedSongId: String get() = service.activeSongId.ifEmpty { persistedSelectedId }

    /** 是否存在任何谱面：用于区分「一首都没有」和「这一类没有」两种空状态。 */
    val hasAnySong: Boolean get() = songs.isNotEmpty()
}

/** 服务状态轮询间隔：只在页面订阅期间运行。 */
private const val SERVICE_POLL_INTERVAL_MS = 500L

class LibraryViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val repository = SongRepository(app)

    /**
     * 曲库数据与界面状态。
     *
     * 构造时就读取一次：如果等 `init` 里再刷新，[stateIn] 的初始值会是空列表，
     * 首帧会闪一下「还没有谱面」。
     */
    private val _local =
        MutableStateFlow(
            LibraryUiState(
                songs = repository.songs(),
                persistedSelectedId = repository.selectedId,
                floatingEnabled = repository.floatingEnabled,
            ),
        )

    /**
     * 无障碍服务状态轮询。
     *
     * 写成冷流 + [SharingStarted.WhileSubscribed]：页面不可见时自动停止，
     * 不再像原来那样无条件每 500ms 唤醒一次主线程。
     */
    private val serviceStatus: Flow<ServiceStatus> =
        flow {
            while (true) {
                emit(readServiceStatus())
                delay(SERVICE_POLL_INTERVAL_MS)
            }
        }

    val state: StateFlow<LibraryUiState> =
        combine(_local, serviceStatus) { local, service -> local.copy(service = service) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), _local.value)

    init {
        // 导入 / 平台下载会改变歌单，HorizontalPager 又不会重建离屏页面，
        // 所以由事件通知曲库重读，而不是依赖页面重组。
        viewModelScope.launch { AppEvents.libraryRefresh.collect { refresh() } }
    }

    private fun readServiceStatus(): ServiceStatus {
        // song 在 onServiceConnected 里先于 instance 赋值，因此 instance 非空时 song 一定已初始化
        val service = MusicAccessibilityService.instance ?: return ServiceStatus()
        return ServiceStatus(
            connected = true,
            error = service.error,
            activeSongId = service.song.id,
        )
    }

    fun refresh() =
        _local.update {
            it.copy(
                songs = repository.songs(),
                persistedSelectedId = repository.selectedId,
                floatingEnabled = repository.floatingEnabled,
            )
        }

    fun setFilter(filter: SongFilter) = _local.update { it.copy(filter = filter) }

    /** 切换当前曲目：只改选中项，不再为了一次点按重读整个歌单。 */
    fun select(song: Song) {
        repository.selectedId = song.id
        syncSelectionToService(song)
        _local.update { it.copy(persistedSelectedId = song.id) }
    }

    /**
     * 「启动」按钮：开启悬浮窗。
     *
     * 两个关键点：**先判权限**、而且**只做「开启」**。
     *
     * 之前的实现是 `setFloatingEnabled(!floatingEnabled)`，而 floatingEnabled 的持久化
     * 默认值就是 true，于是第一次点击实际执行的是「关闭」：既不弹引导也没有可见变化，
     * 必须点第二次才弹对话框。按钮写的是「启动」，就不应该取反。
     *
     * 无权限时仍然把偏好置为开启：用户授权返回后 MainActivity.onResume 会按这个偏好
     * 重新对齐悬浮窗，不需要再点一次。
     */
    fun startFloating() {
        repository.floatingEnabled = true
        val granted = MusicAccessibilityService.isEnabled(getApplication())
        if (granted) MusicAccessibilityService.instance?.showFloating(true)
        _local.update {
            it.copy(
                floatingEnabled = true,
                showAccessibilityPrompt = !granted,
            )
        }
    }

    /** 悬浮窗开关。现在只由首页的状态胶囊调用（「启动」按钮走 [startFloating]）。 */
    fun setFloatingEnabled(enabled: Boolean) {
        repository.floatingEnabled = enabled
        MusicAccessibilityService.instance?.showFloating(enabled)
        _local.update {
            it.copy(
                floatingEnabled = enabled,
                // 打开时才检查权限：未开启无障碍则弹引导
                showAccessibilityPrompt = enabled && !MusicAccessibilityService.isEnabled(getApplication()),
            )
        }
    }

    fun removeSong(song: Song) {
        repository.remove(song.id)
        val remaining = repository.songs()
        // 删掉的正好是当前曲目时，把选择顺延到下一首。
        // 原来写的是 remaining.first()，删掉最后一首会抛 NoSuchElementException 崩溃。
        if (repository.selectedId == song.id) {
            remaining.firstOrNull()?.let(::select)
        }
        _local.update { it.copy(songs = remaining, notice = "已删除《${song.title}》") }
    }

    /** 「另存为」：校验失败时把错误交给对话框展示，返回是否保存成功。 */
    fun saveAs(
        original: Song,
        title: String,
        score: String,
    ): Boolean =
        runCatching {
            repository.add(
                title.ifBlank { original.title },
                score,
                ScoreParser.tempo(score, original.bpm),
                "简谱",
            )
        }.onSuccess { song ->
            // 新增会改变歌单，本页需要重读；其它页面由事件通知
            refresh()
            syncSelectionToService(song)
            _local.update { it.copy(notice = "已另存为《${song.title}》") }
        }.onFailure { e ->
            _local.update { it.copy(error = e.message ?: "谱面格式无效") }
        }.isSuccess

    fun dismissAccessibilityPrompt() = _local.update { it.copy(showAccessibilityPrompt = false) }

    fun dismissError() = _local.update { it.copy(error = null) }

    fun consumeNotice() = _local.update { it.copy(notice = null) }
}
