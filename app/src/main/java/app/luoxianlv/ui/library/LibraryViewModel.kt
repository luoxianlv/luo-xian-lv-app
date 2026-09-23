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
    /** 无障碍服务在系统设置里是否开着。服务被 ROM 回收时它会短暂为 true（见 [connected]）。 */
    val accessibilityEnabled: Boolean = false,
    /** 悬浮窗此刻是否真的显示着（不是持久化偏好）。 */
    val floatingVisible: Boolean = false,
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
    /** 已被删除的内置示例谱面数量：> 0 时曲库显示「恢复内置示例」入口。 */
    val hiddenBuiltInCount: Int = 0,
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

    /**
     * 悬浮窗是否真的在运行：服务在线 **且** 窗口可见。
     *
     * 不能拿 [floatingEnabled] 当运行状态：那只是持久化的用户意图。
     * 服务被 ROM 回收后偏好仍是 true，界面却会报「运行中」，
     * 用户于是既看不到窗口消失、也没法用同一个按钮把它关掉。
     */
    val floatingRunning: Boolean get() = service.connected && service.floatingVisible

    /**
     * 首页状态胶囊文案。
     *
     * 四种情况都要能被用户分辨，且都能给出下一步动作：
     * 无障碍没开就去设置、服务没绑上就再试一次、运行中/已关闭都可以点胶囊切换。
     * 只把 [ServiceStatus.error] 留在「窗口没运行」时显示：窗口在跑时它的状态行
     * 本来就会把错误写出来，胶囊这时候更该回答「关掉它要点哪里」。
     */
    val statusText: String
        get() =
            when {
                !service.accessibilityEnabled -> "无障碍未开启 · 点击去开启"
                !service.connected -> "无障碍服务未就绪 · 点击重试"
                floatingRunning -> "悬浮窗运行中 · 点击关闭"
                else -> service.error ?: "悬浮窗已关闭 · 点击开启"
            }
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
                hiddenBuiltInCount = repository.hiddenBuiltInCount(),
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

    /**
     * 采样一次服务状态。
     *
     * 每个可能抛的点各自兜住（而不是整段包一个 runCatching）：这个方法跑在 500ms 轮询里，
     * 抛一次异常就会让 combine 上游结束，界面永远停在最后一次状态上——
     * 表现就是“点什么都没反应”，所以宁可漏掉一个字段也不能让整条状态流断掉。
     */
    private fun readServiceStatus(): ServiceStatus {
        val service = MusicAccessibilityService.instance
        return ServiceStatus(
            connected = service != null,
            // 服务已经在跑就必然已开启，省掉一次系统查询
            accessibilityEnabled = service != null || accessibilityIsEnabled(),
            floatingVisible = service?.floatingVisible == true,
            error = service?.error,
            // song 在 onServiceConnected 里先于 instance 赋值，因此 instance 非空时 song 一定已初始化
            activeSongId = runCatching { service?.song?.id.orEmpty() }.getOrDefault(""),
        )
    }

    /** 查系统无障碍开关（binder 调用，失败按未开启处理，不让轮询挂掉）。 */
    private fun accessibilityIsEnabled(): Boolean =
        runCatching { MusicAccessibilityService.isEnabled(getApplication()) }.getOrDefault(false)

    fun refresh() =
        _local.update {
            it.copy(
                songs = repository.songs(),
                persistedSelectedId = repository.selectedId,
                floatingEnabled = repository.floatingEnabled,
                hiddenBuiltInCount = repository.hiddenBuiltInCount(),
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
     * 「启动 / 关闭悬浮窗」按钮与状态胶囊的统一入口：按悬浮窗**真实状态**开或关。
     *
     * 直接问服务要窗口状态（而不是读界面里可能滞后的快照）：窗口开着就关、关着就开，
     * 连点两下不会因为状态没刷新而变成「开两次」。
     *
     * 老实现是单向的 startFloating()：窗口已经跑着时再点只会重复 show()，
     * 同一个按钮没有任何办法把它关掉。
     */
    fun toggleFloating() {
        setFloatingEnabled(MusicAccessibilityService.instance?.floatingVisible != true)
    }

    /**
     * 打开 / 关闭悬浮窗，并记住用户意图。
     *
     * 打开时服务不在线（无障碍没开，或系统还没把服务绑起来）也照样把偏好置为开启：
     * 用户去设置里打开后，onServiceConnected 会按这个偏好自动显示；回到前台时
     * MainActivity.onResume 也会再对齐一次。
     * 但**必须弹引导**：否则这种情况下点「启动」会完全没有反应，
     * 用户只能看着「无障碍未开启」的文字反复点同一个按钮。
     */
    fun setFloatingEnabled(enabled: Boolean) {
        repository.floatingEnabled = enabled
        val service = MusicAccessibilityService.instance
        service?.showFloating(enabled)
        _local.update {
            it.copy(
                floatingEnabled = enabled,
                showAccessibilityPrompt = enabled && service == null,
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
        _local.update {
            it.copy(
                songs = remaining,
                notice = "已删除《${song.title}》",
                hiddenBuiltInCount = repository.hiddenBuiltInCount(),
            )
        }
    }

    /** 恢复被删除的内置示例谱面。 */
    fun restoreBuiltIns() {
        repository.restoreBuiltIns()
        _local.update {
            it.copy(
                songs = repository.songs(),
                hiddenBuiltInCount = repository.hiddenBuiltInCount(),
                notice = "已恢复内置示例谱面",
            )
        }
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
