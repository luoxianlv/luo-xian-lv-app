package app.luoxianlv.library

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.luoxianlv.app.BusinessJobs
import app.luoxianlv.app.PermissionSettings
import app.luoxianlv.hot.contract.SharedInput
import app.luoxianlv.playback.PlaybackConnection
import app.luoxianlv.playback.syncSelectionToService
import app.luoxianlv.update.MidiCoreFixer
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 曲库筛选。
 *
 * 归类规则（[Song.isMidi]）收在数据层，界面不再用魔法数字判断： 原实现是 `filter == 0 || (filter == 1) ==
 * song.source.startsWith("MIDI")`。
 */
enum class SongFilter(val label: String) {
    All("全部"),
    Midi("MIDI"),
    Notation("简谱");

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
 * 与曲库数据分开取：原来两者挤在同一个状态对象里， 导致每 500ms 的轮询会把用户刚点的选中项改写成服务里的曲目。
 */
data class ServiceStatus(
    val connected: Boolean = false,
    /** 无障碍服务在系统设置里是否开着。服务被 ROM 回收时它会短暂为 true（见 [connected]）。 */
    val accessibilityEnabled: Boolean = false,
    /** 悬浮窗此刻是否真的显示着（不是持久化偏好）。 */
    val floatingVisible: Boolean = false,
    val error: String? = null,
    val activeSongId: String = "",
    val overlayGranted: Boolean = false,
    val inputMode: String = SharedInput.ACCESSIBILITY,
    val inputReady: Boolean = false,
)

data class LibraryUiState(
    val songs: List<Song> = emptyList(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    /** 持久化的“上次选择”，服务未连接时用它决定高亮 */
    val persistedSelectedId: String = "",
    val filter: SongFilter = SongFilter.All,
    val floatingEnabled: Boolean = true,
    val service: ServiceStatus = ServiceStatus(),
    val showAccessibilityPrompt: Boolean = false,
    val showOverlayPrompt: Boolean = false,
    val showInputModePrompt: Boolean = false,
    val startingFloating: Boolean = false,
    val error: String? = null,
    /** 一次性提示（删除 / 另存为结果），消费后置空 */
    val notice: String? = null,
    /** 已被删除的内置示例谱面数量：> 0 时曲库显示「恢复内置示例」入口。 */
    val hiddenBuiltInCount: Int = 0,
    /** 正在执行手动修复的曲目 id：按钮据此显示「修复中…」并防重复点击。 */
    val fixingIds: Set<String> = emptySet(),
) {
    /** 当前筛选下的曲目：筛选逻辑属于状态，界面只负责渲染。 */
    val visibleSongs: List<Song>
        get() = songs.filter(filter::matches)

    /**
     * 列表高亮项：以服务实际载入的曲目为准，服务未连接时退回持久化的选择。 原实现用 `service?.song?.id ?: it.selectedId`，服务断开后会把上一次的
     * 服务曲目一直留在高亮位上，与持久化选择不一致。
     */
    val highlightedSongId: String
        get() = service.activeSongId.ifEmpty { persistedSelectedId }

    /** 是否存在任何谱面：用于区分「一首都没有」和「这一类没有」两种空状态。 */
    val hasAnySong: Boolean
        get() = songs.isNotEmpty()

    /**
     * 悬浮窗是否真的在运行：服务在线 **且** 窗口可见。
     *
     * 不能拿 [floatingEnabled] 当运行状态：那只是持久化的用户意图。 服务被 ROM 回收后偏好仍是 true，界面却会报「运行中」，
     * 用户于是既看不到窗口消失、也没法用同一个按钮把它关掉。
     */
    val floatingRunning: Boolean
        get() = service.connected && service.floatingVisible
}

/** 服务状态轮询间隔：只在页面订阅期间运行。 */
private const val SERVICE_POLL_INTERVAL_MS = 500L

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = SongRepository(app)
    private val main = Handler(Looper.getMainLooper())

    // 首次读取包含资源文件和 JSON 解析，不放在主线程构造函数中。
    private val _local = MutableStateFlow(LibraryUiState())
    private var refreshJob: Job? = null
    private val libraryMutex = Mutex()
    private var floatingStartJob: Job? = null
    private var floatingStartGeneration = 0L

    /**
     * 无障碍服务状态轮询。
     *
     * 写成冷流 + [SharingStarted.WhileSubscribed]：页面不可见时自动停止， 不再像原来那样无条件每 500ms 唤醒一次主线程。
     */
    private val serviceStatus: Flow<ServiceStatus> = flow {
        while (true) {
            emit(readServiceStatus())
            delay(SERVICE_POLL_INTERVAL_MS)
        }
    }

    val state: StateFlow<LibraryUiState> =
        combine(_local, serviceStatus) { local, service -> local.copy(service = service) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), _local.value)

    init {
        refresh()
        // 导入 / 平台下载会改变歌单，HorizontalPager 又不会重建离屏页面，
        // 所以由事件通知曲库重读，而不是依赖页面重组。
        viewModelScope.launch { AppEvents.libraryRefresh.collect { refresh() } }
    }

    /**
     * 采样一次服务状态。
     *
     * 每个可能抛的点各自兜住（而不是整段包一个 runCatching）：这个方法跑在 500ms 轮询里， 抛一次异常就会让 combine 上游结束，界面永远停在最后一次状态上——
     * 表现就是“点什么都没反应”，所以宁可漏掉一个字段也不能让整条状态流断掉。
     */
    private suspend fun readServiceStatus(): ServiceStatus {
        val service = PlaybackConnection.instance
        val input = runCatching { SharedInput.current()?.state() }.getOrNull()
        val mode = input?.getString("mode", SharedInput.ACCESSIBILITY) ?: SharedInput.ACCESSIBILITY
        val (overlay, accessibility) =
            BusinessJobs.io {
                PermissionSettings.overlayGranted(getApplication()) to accessibilityIsEnabled()
            }
        return ServiceStatus(
            connected = service != null,
            // 播放宿主独立于无障碍，连接在线不能冒充无障碍已经授权。
            accessibilityEnabled = accessibility,
            floatingVisible = service?.floatingVisible == true,
            error = service?.error,
            // 连接在业务初始化后发布；这里只取曲目 ID，不传递或解析谱面对象。
            activeSongId = runCatching { service?.songId.orEmpty() }.getOrDefault(""),
            overlayGranted = overlay,
            inputMode = mode,
            inputReady =
                if (mode == SharedInput.ACCESSIBILITY) accessibility
                else input?.getBoolean("connected") == true && input.getBoolean("touchReady"),
        )
    }

    /** 查系统无障碍开关（binder 调用，失败按未开启处理，不让轮询挂掉）。 */
    private fun accessibilityIsEnabled(): Boolean = runCatching {
        PlaybackConnection.isEnabled(getApplication())
    }
        .getOrDefault(false)

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            try {
                val (songs, hidden) =
                    BusinessJobs.io {
                        libraryMutex.withLock {
                            repository.songs() to repository.hiddenBuiltInCount()
                        }
                    }
                _local.update {
                    it.copy(
                        songs = songs,
                        loading = false,
                        persistedSelectedId = repository.selectedId,
                        floatingEnabled = repository.floatingEnabled,
                        hiddenBuiltInCount = hidden,
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _local.update { it.copy(loading = false, error = error.message ?: "曲库读取失败") }
            }
        }
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
     * 直接问服务要窗口状态（而不是读界面里可能滞后的快照）：窗口开着就关、关着就开， 连点两下不会因为状态没刷新而变成「开两次」。
     *
     * 老实现是单向的 startFloating()：窗口已经跑着时再点只会重复 show()， 同一个按钮没有任何办法把它关掉。
     */
    fun toggleFloating() {
        if (_local.value.startingFloating) {
            setFloatingEnabled(false)
            return
        }
        setFloatingEnabled(PlaybackConnection.instance?.floatingVisible != true)
    }

    /**
     * 打开 / 关闭悬浮窗，并记住用户意图。
     *
     * 先检查悬浮窗权限与所选输入模式，再等待独立播放宿主就绪；初始化尚未完成不等同于无障碍未授权。
     */
    fun setFloatingEnabled(enabled: Boolean) {
        val generation = ++floatingStartGeneration
        floatingStartJob?.cancel()
        if (!enabled) {
            repository.floatingEnabled = false
            PlaybackConnection.instance?.apply {
                stop()
                showFloating(false)
            }
            _local.update { it.copy(floatingEnabled = false, startingFloating = false) }
            return
        }
        _local.update { it.copy(startingFloating = true) }
        floatingStartJob = viewModelScope.launch {
            try {
                val status = readServiceStatus()
                if (!status.overlayGranted || !status.inputReady) {
                    repository.floatingEnabled = false
                    _local.update {
                        it.copy(
                            floatingEnabled = false,
                            showOverlayPrompt = !status.overlayGranted,
                            showAccessibilityPrompt = false,
                            showInputModePrompt = status.overlayGranted && !status.inputReady,
                        )
                    }
                    return@launch
                }
                if (!awaitOverlaySnapshot(status.overlayGranted)) {
                    repository.floatingEnabled = false
                    _local.update {
                        it.copy(floatingEnabled = false, notice = "权限检查尚未完成，请稍后重试")
                    }
                    return@launch
                }
                if (generation != floatingStartGeneration) return@launch
                val latest = runCatching { SharedInput.current()?.state() }.getOrNull()
                val sameMode =
                    latest?.getString("mode", SharedInput.ACCESSIBILITY) == status.inputMode
                val ready =
                    latest != null &&
                        if (status.inputMode == SharedInput.ACCESSIBILITY)
                            latest.getBoolean("accessibilityEnabled")
                        else latest.getBoolean("connected") && latest.getBoolean("touchReady")
                if (!sameMode || !ready || latest?.getBoolean("overlayGranted", false) != true) {
                    repository.floatingEnabled = false
                    _local.update {
                        it.copy(
                            floatingEnabled = false,
                            showOverlayPrompt =
                                latest != null && !latest.getBoolean("overlayGranted", false),
                            showInputModePrompt =
                                latest?.getBoolean("overlayGranted", false) == true &&
                                    sameMode &&
                                    !ready,
                            notice = if (!sameMode) "输入模式已变化，请重新点击启动" else null,
                        )
                    }
                    return@launch
                }
                repository.floatingEnabled = true
                _local.update {
                    it.copy(
                        floatingEnabled = true,
                        showOverlayPrompt = false,
                        showAccessibilityPrompt = false,
                        showInputModePrompt = false,
                    )
                }
                PlaybackConnection.startFloating(getApplication())
                // 授权和后台初始化是两回事；等真实桥接建立，不误弹无障碍引导。
                val until = android.os.SystemClock.elapsedRealtime() + 10000
                while (PlaybackConnection.instance?.floatingVisible != true) {
                    PlaybackConnection.instance?.floatingError?.let { reason ->
                        repository.floatingEnabled = false
                        _local.update { it.copy(floatingEnabled = false, notice = reason) }
                        PlaybackConnection.instance?.showFloating(false)
                        return@launch
                    }
                    if (android.os.SystemClock.elapsedRealtime() >= until) {
                        _local.update { it.copy(notice = "启动暂未完成，请稍后重试") }
                        break
                    }
                    delay(100)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                repository.floatingEnabled = false
                _local.update {
                    it.copy(
                        floatingEnabled = false,
                        error = "启动失败，请检查输入连接后重试",
                    )
                }
                app.luoxianlv.diagnostics.AppLog.w("播放", "启动播放窗口失败", failure)
            } finally {
                if (generation == floatingStartGeneration)
                    _local.update { it.copy(startingFloating = false) }
            }
        }
    }

    /** 正式启动前等待宿主发布授权快照，避免刚授权回来时服务读取旧状态后立即停止。 */
    private suspend fun awaitOverlaySnapshot(granted: Boolean): Boolean {
        val bridge = runCatching { SharedInput.current() }.getOrNull() ?: return false
        bridge.command("refresh", android.os.Bundle())
        return kotlinx.coroutines.withTimeoutOrNull(5_000) {
            while (bridge.state().getBoolean("overlayGranted", false) != granted) delay(50)
            true
        } == true
    }

    fun removeSong(song: Song) = changeLibrary {
        repository.remove(song.id)
        val remaining = repository.songs()
        if (repository.selectedId == song.id) {
            repository.selectedId = remaining.firstOrNull()?.id.orEmpty()
            remaining.firstOrNull()?.let(::syncSelectionToService)
        }
        "已删除《${song.title}》"
    }

    fun restoreBuiltIns() = changeLibrary {
        repository.restoreBuiltIns()
        "已恢复内置示例谱面"
    }

    /** JSON 读写与谱面校验串行放在后台，主线程只更新界面。 */
    private fun changeLibrary(change: () -> String) = viewModelScope.launch {
        try {
            val notice = BusinessJobs.io { libraryMutex.withLock { change() } }
            refresh()
            _local.update { it.copy(notice = notice) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _local.update { it.copy(error = error.message ?: "曲库更新失败") }
        }
    }

    /** 曲库「修复」按钮：重置计数，完整重试一遍远端重编流程。 后台线程执行，结果回到主线程更新列表与提示。 */
    fun fixSong(song: Song) {
        if (song.id in _local.value.fixingIds) return
        _local.update { it.copy(fixingIds = it.fixingIds + song.id) }
        MidiCoreFixer.fixSong(getApplication(), song) { ok ->
            val songs = repository.songs()
            // 回调在后台线程：服务 select 会碰悬浮窗视图，回主线程执行。
            BusinessJobs.post(main) {
                // 服务可能持有旧 Song 实例，修好后让它也重新载入
                if (ok) {
                    PlaybackConnection.instance?.let { service ->
                        runCatching {
                            service.select(songs.first { it.id == song.id })
                        }
                    }
                }
                _local.update {
                    it.copy(
                        fixingIds = it.fixingIds - song.id,
                        songs = songs,
                        notice = if (ok) "已修复《${song.title}》" else "《${song.title}》修复失败，请稍后重试",
                    )
                }
            }
        }
    }

    /** 保存期间防止重复提交；成功后回主线程关闭编辑框。 */
    fun saveAs(original: Song, title: String, score: String, onSaved: () -> Unit) {
        if (_local.value.saving) return
        _local.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val song = BusinessJobs.io {
                    libraryMutex.withLock {
                        repository.add(
                            title.ifBlank { original.title },
                            score,
                            ScoreParser.tempo(score, original.bpm),
                            "简谱",
                        )
                    }
                }
                refresh()
                syncSelectionToService(song)
                _local.update { it.copy(notice = "已另存为《${song.title}》") }
                onSaved()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _local.update { it.copy(error = error.message ?: "谱面格式无效") }
            } finally {
                _local.update { it.copy(saving = false) }
            }
        }
    }

    fun dismissAccessibilityPrompt() = _local.update { it.copy(showAccessibilityPrompt = false) }

    fun dismissOverlayPrompt() = _local.update { it.copy(showOverlayPrompt = false) }

    fun dismissInputModePrompt() = _local.update { it.copy(showInputModePrompt = false) }

    fun dismissError() = _local.update { it.copy(error = null) }

    fun consumeNotice() = _local.update { it.copy(notice = null) }
}
