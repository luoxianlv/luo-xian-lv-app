package app.luoxianlv.playback

import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import app.luoxianlv.BuildConfig
import app.luoxianlv.core.Analytics
import app.luoxianlv.diagnostics.AppLog
import app.luoxianlv.hot.contract.AccessibilityBinding
import app.luoxianlv.hot.contract.NativePage
import app.luoxianlv.hot.contract.NativePlaybackSession
import app.luoxianlv.hot.contract.PlaybackBridge
import app.luoxianlv.library.NoteEvent
import app.luoxianlv.library.PlayMode
import app.luoxianlv.library.ScoreWork
import app.luoxianlv.library.Song
import app.luoxianlv.library.SongRepository
import app.luoxianlv.practice.PracticeGeometry
import app.luoxianlv.practice.PracticePlaybackGate
import app.luoxianlv.recognition.ConfigStore
import app.luoxianlv.recognition.KeyLayout
import app.luoxianlv.settings.ExperimentalOptions
import app.luoxianlv.update.MidiCoreFixer
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 可替换的播放、识别和浮窗业务，不继承系统 Service。 */
class PlaybackSession : ContextWrapper(null), NativePlaybackSession {
    companion object {
        const val TAG = "无障碍手势"
    }

    private lateinit var binding: AccessibilityBinding
    @Volatile private var closed = false
    private var active = false
    private var monitoring = false
    private var changeRevision = 0L
    private var listenerGeometry: FloatingDisplayGeometry? = null
    private var restoredFloating = false
    private var handoverPrepared = false
    private val current
        get() = active && !closed && binding.current()

    private val remoteFixJobs = AtomicInteger()
    private val handler = Handler(Looper.getMainLooper())
    private val screenCapture = PlaybackScreenCapture(handler) { closed }
    private lateinit var repository: SongRepository
    private lateinit var keys: KeyLayout
    private lateinit var floating: FloatingControls
    var song = Song("", "正在加载曲目…", "", 120, "简谱")
        private set

    private var timeline = PlaybackTimeline(emptyList(), 120)
    private val scoreScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val songLoad = SongLoadGate(::reportUsage)
    private var songLoadJob: Job? = null
    val loadingSong
        get() = songLoad.loading

    val waitingToPlay
        get() = songLoad.playWhenReady

    var playing = false
        private set(value) {
            if (field == value) return
            field = value
            reportUsage()
        }

    /** 播放前的截图识别尚未完成时为 true。 */
    var preparing = false
        private set(value) {
            if (field == value) return
            field = value
            reportUsage()
        }

    /** 准备与播放只在实际边沿通知；查询、浮窗刷新和计时循环不会重复触发调度。 */
    private fun reportUsage() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "播放状态必须在主线程通知" }
        if (::binding.isInitialized) binding.usage(playing && active && !closed)
    }

    var error: String? = null
        private set

    var speed = 1f
        private set

    private var baseMs = 0L
    private var anchor = 0L
    private var generation = 0
    private var busy = false
    private var gestureFailure: String? = null
    private var lastCoordinates: String? = null
    private var halfToneOn = false
    private var pitchMode = PlayMode.NATURAL
    /** 当前选中曲目是否已经尝试过播放前自动修复（每次选中重置，避免内部 resume 反复重试）。 */
    private var fixAttemptedForSong = false
    private var playbackDisplay: DisplayState? = null
    // 自动模式使用截图像素空间；固定模式使用无障碍手势所在的完整显示空间。
    private var coordinateFrame: PlaybackCoordinates.Frame? = null
    private var fixedKeys = false
    private val interruptionGuard = PlaybackInterruptionGuard()

    internal fun canStartPlayback() = interruptionGuard.canStart(SystemClock.uptimeMillis())

    private var recoveringDisplay = false
    private var recoveryAttempts = 0
    private var recoveryWasPlaying = false
    private val displayStability = DisplayStability()
    private val monitorDisplay =
        object : Runnable {
            override fun run() {
                if (!current) return
                if (
                    (playing || preparing) &&
                        !recoveringDisplay &&
                        playbackDisplay != displayState()
                )
                    beginDisplayRecovery()
                handler.postDelayed(this, 150)
            }
        }
    private val displayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) {
                if (!current || displayId != Display.DEFAULT_DISPLAY) return
                val geometry = floatingGeometry()
                if (geometry == listenerGeometry) return
                listenerGeometry = geometry
                changeRevision++
                if (::floating.isInitialized) floating.reposition()
                if ((playing || preparing) && playbackDisplay != displayState()) {
                    beginDisplayRecovery()
                }
            }
        }

    private fun beginDisplayRecovery() {
        AppLog.log(
            "开始恢复显示：播放显示=$playbackDisplay 当前显示=" +
                displayState() +
                " 原播放状态=" +
                playing +
                " 准备中=" +
                preparing
        )
        if (recoveringDisplay) return
        recoveryWasPlaying = playing || preparing
        baseMs = positionMs
        playing = false
        preparing = true
        busy = false
        generation++
        handler.removeCallbacks(next)
        recoveringDisplay = true
        recoveryAttempts = 0
        displayStability.reset()
        error = "屏幕方向变化，正在重新识别…"
        floating.refresh()
        handler.post(recoverDisplay)
    }

    private val recoverDisplay =
        object : Runnable {
            override fun run() {
                if (!recoveringDisplay) return
                val current = displayState()
                recoveryAttempts++
                if (recoveryAttempts > 30) {
                    finishDisplayRecovery(false)
                    error = "屏幕或琴键识别未稳定，请保持游戏界面可见后重试"
                    floating.refresh()
                    return
                }
                // 逻辑显示状态和截图生产端可能在不同时刻稳定。
                if (!displayStability.ready(current, SystemClock.uptimeMillis())) {
                    handler.postDelayed(this, 120)
                    return
                }
                playbackDisplay = current
                val token = ++generation
                var completed = false
                val timeout = Runnable {
                    if (token == generation && recoveringDisplay && !completed) {
                        completed = true
                        generation++
                        handler.postDelayed(this, 500)
                    }
                }
                handler.postDelayed(timeout, 2000)
                syncWithScreen(token) { recognized ->
                    if (token != generation || !recoveringDisplay || completed)
                        return@syncWithScreen
                    completed = true
                    handler.removeCallbacks(timeout)
                    if (recognized) {
                        finishDisplayRecovery(recoveryWasPlaying)
                    } else {
                        handler.postDelayed(this, if (recoveryAttempts < 10) 350 else 1000)
                    }
                }
            }
        }

    private fun finishDisplayRecovery(resume: Boolean) {
        AppLog.log("显示恢复完成：恢复播放=$resume")
        recoveringDisplay = false
        preparing = false
        handler.removeCallbacks(recoverDisplay)
        if (resume && timeline.events.isNotEmpty()) {
            error = null
            playing = true
            anchor = SystemClock.uptimeMillis()
            drive()
        } else {
            playing = false
            error = if (resume) "屏幕识别失败，请重试" else null
        }
        floating.refresh()
    }

    private fun displayState(): DisplayState {
        val display =
            getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val size = Point()
        @Suppress("DEPRECATION") display.getRealSize(size)
        return DisplayState(size.x, size.y, display.rotation)
    }

    val durationMs
        get() = timeline.durationMs

    val positionMs
        get() =
            (baseMs + if (playing) ((SystemClock.uptimeMillis() - anchor) * speed).toLong() else 0)
                .coerceIn(0, durationMs)

    val modeLabel
        get() = pitchMode.label + if (halfToneOn) " · 半音" else ""

    private val next = Runnable { drive() }

    override fun connect(context: Context, binding: AccessibilityBinding) {
        initialize(context, binding)
        active = true
        prepareSong()
        monitor()
        if (repository.floatingEnabled)
            handler.postDelayed(
                { if (current && repository.floatingEnabled) showFloating(true) },
                250,
            )
    }

    private fun initialize(context: Context, binding: AccessibilityBinding) {
        check(!closed && !::repository.isInitialized)
        attachBaseContext(context)
        this.binding = binding
        AppLog.init(this)
        AppLog.log(
            "无障碍服务已连接：${Build.MANUFACTURER}/${Build.MODEL} 系统 API=${Build.VERSION.SDK_INT} ${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) 显示=${displayState()}"
        )
        repository = SongRepository(this)
        keys = ConfigStore.load(this)
        speed = repository.speed
        floating = FloatingControls(this)
    }

    private fun monitor() {
        if (monitoring) return
        listenerGeometry = floatingGeometry()
        getSystemService(DisplayManager::class.java)
            .registerDisplayListener(displayListener, handler)
        monitoring = true
        handler.post(monitorDisplay)
    }

    private fun stopMonitoring() {
        handler.removeCallbacks(monitorDisplay)
        if (!monitoring) return
        monitoring = false
        runCatching {
            getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        }
            .onFailure { AppLog.w(TAG, "释放显示监听失败", it) }
    }

    override fun supportsHandover() = true

    override fun revision() =
        changeRevision + if (::floating.isInitialized) floating.revision else 0L

    override fun prepare(
        context: Context,
        binding: AccessibilityBinding,
        state: Bundle,
        ready: NativePage.Ready,
    ) {
        check(!binding.current()) { "候选播放会话不能提前获得系统输入" }
        initialize(context, binding)
        restoreState(state, ready, background = true)
    }

    override fun snapshot(): Bundle {
        check(!closed && ::repository.isInitialized)
        val selected = PlaybackWire.song(song)
        return PlaybackSnapshot.encode(
            song = selected,
            position = positionMs,
            speed = speed,
            mode = pitchMode,
            halfToneOn = halfToneOn,
            fixedKeys = fixedKeys,
            fixAttempted = fixAttemptedForSong,
            floatingVisible = if (active) floatingVisible else restoredFloating,
            floatingState = floating.snapshot(),
            error = error,
        )
    }

    override fun restore(state: Bundle?, ready: NativePage.Ready) =
        restoreState(state, ready, background = false)

    override fun prepareRecovery(
        context: Context,
        binding: AccessibilityBinding,
        state: Bundle?,
        ready: NativePage.Ready,
    ) {
        check(!binding.current()) { "恢复准备期间不能获得系统输入" }
        initialize(context, binding)
        restoreState(state, ready, background = false)
    }

    private fun restoreState(state: Bundle?, ready: NativePage.Ready, background: Boolean) {
        check(!closed && !active) { "必须先停用播放会话再恢复状态" }
        handoverPrepared = false
        songLoadJob?.cancel()
        val snapshot = PlaybackSnapshot(state)
        songLoadJob = scoreScope.launch {
            try {
                val prepared =
                    withContext(if (background) ScoreWork.preview else ScoreWork.playback) {
                        val selected = snapshot.selectedSong(repository::selected)
                        selected to PlaybackTimeline(selected.events, selected.bpm)
                    }
                val restoredSpeed = snapshot.speed { repository.speed }
                val mode = snapshot.mode
                song = prepared.first
                timeline = prepared.second
                baseMs = snapshot.position.coerceIn(0, durationMs)
                speed = restoredSpeed
                pitchMode = mode
                halfToneOn = snapshot.halfToneOn
                fixedKeys = snapshot.fixedKeys {
                    ExperimentalOptions.fixedHarmonicaKeys(this@PlaybackSession)
                }
                fixAttemptedForSong = snapshot.fixAttempted
                restoredFloating = snapshot.floatingVisible { repository.floatingEnabled }
                floating.restore(snapshot.floatingState)
                error = snapshot.error
                coordinateFrame = null
                playing = false
                preparing = false
                handoverPrepared = true
                ready.ready()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                ready.failed(failure)
            }
        }
    }

    override fun activate() {
        check(!closed && !active && handoverPrepared && binding.current())
        active = true
        monitor()
        // 恢复可见性不改用户持久化偏好，也不自动恢复刚才暂停的播放。
        if (restoredFloating) floating.show()
        binding.foreground(restoredFloating)
    }

    override fun deactivate() {
        if (!active || closed) return
        restoredFloating = floatingVisible
        pauseNow()
        active = false
        stopMonitoring()
        floating.hide()
    }

    override fun interrupt() = pause()

    override fun close() {
        if (closed) return
        closed = true
        active = false
        scoreScope.cancel()
        stopMonitoring()
        playing = false
        generation++
        handler.removeCallbacksAndMessages(null)
        screenCapture.close()
        recoveringDisplay = false
        if (::floating.isInitialized) floating.destroy()
    }

    override fun canReplace() =
        current &&
            !playing &&
            !preparing &&
            !recoveringDisplay &&
            !loadingSong &&
            !waitingToPlay &&
            screenCapture.idle &&
            remoteFixJobs.get() == 0 &&
            (!::floating.isInitialized || !floating.interacting)

    override fun released() =
        closed &&
            screenCapture.released &&
            remoteFixJobs.get() == 0 &&
            scoreScope.coroutineContext[Job]?.isCompleted == true &&
            (!::floating.isInitialized || floating.released)

    override fun query(kind: String): Bundle =
        when (kind) {
            "song" -> PlaybackWire.song(song)
            "diagnostics" -> PlaybackWire.diagnostics(diagnostics())
            "bounds" ->
                screenBounds().let {
                    Bundle().apply {
                        putInt("width", it.width())
                        putInt("height", it.height())
                    }
                }
            "state" ->
                Bundle().apply {
                    putString("songId", song.id)
                    putBoolean("playing", playing)
                    putBoolean("preparing", preparing)
                    putBoolean("loadingSong", loadingSong)
                    putBoolean("waitingToPlay", waitingToPlay)
                    putBoolean("floatingVisible", floatingVisible)
                    putBoolean("floatingEnabled", repository.floatingEnabled)
                    putString("error", error)
                    putFloat("speed", speed)
                    putLong("positionMs", positionMs)
                    putLong("durationMs", durationMs)
                    putString("modeLabel", modeLabel)
                }
            else -> throw IllegalArgumentException("未知的播放查询：$kind")
        }

    override fun command(action: String, arguments: Bundle) {
        if (!current) return
        when (action) {
            "select" -> select(PlaybackWire.song(arguments))
            "play" -> play()
            "pause" -> pause()
            "stop" -> stop()
            "toggle" -> toggle()
            "seek" -> seek(arguments.getLong("position"))
            "setSpeed" -> setSpeed(arguments.getFloat("speed", 1f).also { require(it.isFinite()) })
            "showFloating" -> showFloating(arguments.getBoolean("enabled"))
            "reloadConfig" -> reloadConfig()
            "reloadExperimentalOptions" -> reloadExperimentalOptions()
            "refreshFloatingTheme" -> refreshFloatingTheme()
            else -> throw IllegalArgumentException("未知的播放命令：$action")
        }
    }

    fun select(selected: Song) {
        if (!current) return
        changeRevision++
        pause()
        song = selected
        fixAttemptedForSong = false
        repository.selectedId = selected.id
        prepareSong(selected)
    }

    private fun prepareSong(selected: Song? = null) {
        songLoadJob?.cancel()
        val token = songLoad.begin()
        timeline = PlaybackTimeline(emptyList(), 120)
        baseMs = 0
        error = null
        floating.refresh()
        songLoadJob = scoreScope.launch {
            try {
                val (readySong, readyTimeline) =
                    withContext(ScoreWork.playback) {
                        val nextSong = selected ?: repository.selected()
                        nextSong to PlaybackTimeline(nextSong.events, nextSong.bpm)
                    }
                val start = songLoad.finish(token) ?: return@launch
                song = readySong
                timeline = readyTimeline
                if (timeline.events.isEmpty()) error = "乐谱没有可播放的音符"
                floating.refresh()
                if (start && timeline.events.isNotEmpty()) play()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (songLoad.finish(token) == null) return@launch
                error = "谱面加载失败，请重新选择曲目"
                AppLog.w(TAG, error!!, failure)
                floating.refresh()
            }
        }
    }

    fun toggle() {
        if (!current) return
        changeRevision++
        when {
            // 埋点：用户手动暂停演奏（seek/变速等内部调用 pause() 的路径不计）
            playing -> {
                Analytics.logEvent(this, "play_stop")
                pause()
            }
            preparing || waitingToPlay -> pause()
            else -> play()
        }
    }

    fun play() {
        if (!current) return
        changeRevision++
        if (!canStartPlayback()) {
            AppLog.log("忽略播放：距离手势中断不足 300 毫秒")
            return
        }
        if (songLoad.requestPlay()) {
            floating.refresh()
            return
        }
        val useFixedKeys = ExperimentalOptions.fixedHarmonicaKeys(this)
        if (
            app.luoxianlv.practice.PracticePlaybackGate.active &&
                (!app.luoxianlv.practice.PracticePlaybackGate.ready ||
                    (Build.VERSION.SDK_INT < 30 && !useFixedKeys))
        ) {
            error =
                if (Build.VERSION.SDK_INT < 30 && !useFixedKeys) "演练场自动定位需要 Android 11 或更高版本"
                else "请等待演练场开场完成"
            floating.refresh()
            return
        }
        if (playing || preparing || recoveringDisplay || timeline.events.isEmpty()) return
        if (fixedKeys != useFixedKeys) {
            fixedKeys = useFixedKeys
            keys = ConfigStore.load(this)
            pitchMode = PlayMode.NATURAL
            halfToneOn = false
        }
        if (baseMs >= durationMs) baseMs = 0
        // 标记 needsFix 的歌：播放前先自动尝试一次远端重编（每选中一次只试一次），
        // 失败给出提示，引导回 App 内曲库手动修复；没有 remoteId 的只能靠播放兜底修剪。
        if (song.needsFix && song.remoteId.isNotBlank() && !fixAttemptedForSong) {
            fixAttemptedForSong = true
            attemptRemoteFixThenPlay()
            return
        }
        error = null
        // 首音前按本次模式准备布局；固定模式不发起截图，也不覆盖识别缓存。
        playbackDisplay = displayState()
        coordinateFrame = null
        AppLog.log("开始播放：显示=$playbackDisplay 起点毫秒=$baseMs 速度=$speed")
        if (fixedKeys) {
            syncFixedLayout()
            startPlaying()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            preparing = true
            val token = ++generation
            floating.refresh()
            val timeout = Runnable {
                if (token == generation && preparing) {
                    generation++
                    preparing = false
                    error = "截图识别超时，请重试"
                    AppLog.log("首次识别超时")
                    floating.refresh()
                }
            }
            handler.postDelayed(timeout, 2500)
            syncWithScreen(token) { recognized ->
                handler.removeCallbacks(timeout)
                if (token != generation) return@syncWithScreen
                preparing = false
                if (!recognized && playbackDisplay != displayState()) {
                    preparing = true
                    beginDisplayRecovery()
                    return@syncWithScreen
                }
                if (!recognized) {
                    error = "未能确认琴键位置，请保持游戏琴键界面可见后重试"
                    AppLog.log("识别失败，阻止播放：显示=" + displayState())
                    floating.refresh()
                    return@syncWithScreen
                }
                if (token == generation) startPlaying() else floating.refresh()
            }
        } else {
            startPlaying()
        }
    }

    /** needsFix 歌曲的播放前自动修复：远端重编成功就换新谱面继续播放； 失败留在当前页并提示回 App 内修复。全程在后台线程跑，不阻塞手势主循环。 */
    private fun attemptRemoteFixThenPlay() {
        val token = ++generation
        val requestedSongId = song.id
        preparing = true
        error = "正在修复谱面…"
        floating.refresh()
        remoteFixJobs.incrementAndGet()
        MidiCoreFixer.fixSong(this, song) { ok ->
            try {
                if (closed) return@fixSong
                val updated =
                    if (ok)
                        runCatching {
                            SongRepository(this).songs().firstOrNull { it.id == requestedSongId }
                        }
                            .getOrNull()
                    else null
                handler.post {
                    if (closed || token != generation || !preparing || song.id != requestedSongId)
                        return@post
                    preparing = false
                    if (!ok) {
                        error = "谱面修复失败，请到 App 内曲库中修复该谱子"
                        floating.refresh()
                        return@post
                    }
                    // 重新载入该曲（updateCompiled 已覆盖缓存并清除 needsFix），再正常起播。
                    updated?.let { updated ->
                        if (updated.id == song.id && updated.score != song.score) select(updated)
                    }
                    play()
                }
            } finally {
                remoteFixJobs.decrementAndGet()
            }
        }
    }

    private fun startPlaying() {
        if (playing || timeline.events.isEmpty()) return
        Analytics.logEvent(this, "play_start") // 埋点：开始演奏（识别/准备完成后真正起播）
        playing = true
        generation++
        anchor = SystemClock.uptimeMillis()
        drive()
        floating.refresh()
    }

    private fun syncFixedLayout() {
        val display = checkNotNull(playbackDisplay)
        coordinateFrame = PlaybackCoordinates.Frame(display.width, display.height)
        keys = PracticeGeometry.keyLayout(display.width, display.height)
        PracticePlaybackGate.pitchState()?.let { (mode, half) ->
            pitchMode = mode
            halfToneOn = half
        }
        AppLog.log("固定口琴布局：显示=$display 音区=$pitchMode 半音=$halfToneOn，不请求截图")
    }

    /** 设置切换时停止旧手势序列，下一次播放再采用新布局。 */
    fun reloadExperimentalOptions() {
        if (!current) return
        changeRevision++
        pause()
        fixedKeys = ExperimentalOptions.fixedHarmonicaKeys(this)
        coordinateFrame = null
        keys = ConfigStore.load(this)
        pitchMode = PlayMode.NATURAL
        halfToneOn = false
        error = null
        floating.refresh()
    }

    /** 截图识别后保存布局并同步音区；回调在主线程执行。 */
    private fun syncWithScreen(
        token: Int,
        done: (Boolean) -> Unit,
    ) {
        if (fixedKeys) {
            if (token != generation || playbackDisplay != displayState()) done(false)
            else {
                syncFixedLayout()
                done(true)
            }
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            done(false)
            return
        }
        screenCapture.recognize(
            binding = binding,
            accept = { screenshot ->
                val currentDisplay = displayState()
                val valid = token == generation && playbackDisplay == currentDisplay
                if (!valid) {
                    AppLog.log(
                        "截图请求已过期：令牌有效=${token == generation} 播放显示=$playbackDisplay 当前显示=$currentDisplay 截图尺寸=${screenshot.buffer.width}x${screenshot.buffer.height}"
                    )
                } else {
                    AppLog.log(
                        "无障碍截图尺寸=${screenshot.buffer.width}x${screenshot.buffer.height} 显示=$currentDisplay"
                    )
                }
                valid
            },
            done = { frame, result ->
                if (token != generation || playbackDisplay != displayState()) {
                    done(false)
                } else {
                    val valid = result != null && PlaybackCoordinates.validLayout(result.layout)
                    if (valid) {
                        coordinateFrame = frame
                        keys = result.layout
                        ConfigStore.save(this@PlaybackSession, result.layout)
                        result.mode?.let { pitchMode = it }
                        result.halfTone?.let { halfToneOn = it }
                        AppLog.i(
                            TAG,
                            "按键识别成功 音区=${result.mode} 半音=${result.halfTone}",
                        )
                        floating.refresh()
                    }
                    done(valid)
                }
            },
            failed = { done(false) },
        )
    }

    fun pause() {
        if (!current) return
        pauseNow()
    }

    private fun pauseNow() {
        changeRevision++
        songLoad.pause()
        AppLog.log("暂停播放：播放中=$playing 准备中=$preparing")
        if (recoveringDisplay) {
            generation++
            busy = false
            recoveryWasPlaying = false
            finishDisplayRecovery(false)
            return
        }
        preparing = false
        baseMs = positionMs
        playing = false
        busy = false
        generation++
        handler.removeCallbacks(next)
        if (::floating.isInitialized) floating.refresh()
    }

    fun stop() {
        if (!current) return
        // 埋点：通知栏「停止并关闭悬浮窗」等显式停止（播放中才计，避免与 toggle 暂停重复）
        if (playing) Analytics.logEvent(this, "play_stop")
        pause()
        baseMs = 0
        floating.refresh()
    }

    fun seek(milliseconds: Long) {
        if (!current) return
        val resume = playing
        pause()
        baseMs = milliseconds.coerceIn(0, durationMs)
        if (resume) play() else floating.refresh()
    }

    fun setSpeed(value: Float) {
        if (!current) return
        val resume = playing
        pause()
        speed = value.coerceIn(.5f, 2f)
        repository.speed = speed
        if (resume) play()
    }

    /**
     * 悬浮窗此刻是否真的显示着。
     *
     * 界面用它（而不是持久化偏好）判断「运行中」：服务没连上时窗口一定不存在， 而 `SongRepository.floatingEnabled` 只是用户意图，可能和现实不一致。
     */
    val floatingVisible: Boolean
        get() = ::floating.isInitialized && floating.isVisible

    /**
     * 深色模式切换后让悬浮窗重绘一次。
     *
     * 悬浮窗是本服务里的独立系统窗口，不跟着 Activity 重组， 所以主题变更得显式通知它（见 `FloatingControls.refreshTheme`）。
     */
    fun refreshFloatingTheme() {
        if (!current) return
        changeRevision++
        if (::floating.isInitialized) floating.refreshTheme()
    }

    fun showFloating(enabled: Boolean) {
        if (!current) return
        changeRevision++
        repository.floatingEnabled = enabled
        if (enabled) {
            floating.show()
            binding.foreground(true)
        } else {
            floating.hide()
            binding.foreground(false)
        }
    }

    fun reloadConfig() {
        if (!current) return
        changeRevision++
        if (!fixedKeys) keys = ConfigStore.load(this)
    }

    fun screenBounds(): Rect {
        val display = displayState()
        val frame = coordinateFrame?.takeIf { playbackDisplay == display }
        return Rect(0, 0, frame?.width ?: display.width, frame?.height ?: display.height)
    }

    internal fun floatingGeometry(): FloatingDisplayGeometry {
        val display = displayState()
        val frame = coordinateFrame?.takeIf { playbackDisplay == display }
        return FloatingDisplayGeometry(
            frame?.width ?: display.width,
            frame?.height ?: display.height,
            display.rotation,
            resources.displayMetrics.densityDpi,
            resources.configuration.fontScale,
        )
    }

    fun diagnostics(): PlaybackConnection.Diagnostics =
        PlaybackConnection.Diagnostics(
            serviceEnabled = PlaybackBridge.isEnabled(this),
            playing = playing,
            preparing = preparing,
            songTitle = song.title,
            display = runCatching { displayState() }.getOrNull(),
            error = error,
            gestureFailure = gestureFailure,
            playbackDisplay = playbackDisplay,
            lastCoordinates = lastCoordinates,
        )

    private fun drive() {
        handler.removeCallbacks(next)
        if (!playing || busy) return
        if (playbackDisplay != displayState()) {
            beginDisplayRecovery()
            return
        }
        val at = positionMs
        val index = timeline.indexAt(at)
        if (index >= timeline.events.size || at >= durationMs) {
            pause()
            baseMs = durationMs
            return
        }
        val note = timeline.events[index]
        val end = timeline.offsets[index + 1]
        if (note.rest) {
            handler.postDelayed(next, ((end - at) / speed).toLong().coerceAtLeast(1))
            return
        }
        busy = true
        val token = generation
        ensurePitch(note) { success ->
            if (token != generation) return@ensurePitch
            if (!success) {
                finishGesture(false)
                return@ensurePitch
            }
            if (!playing || token != generation) {
                finishGesture(true)
                return@ensurePitch
            }
            val remaining = ((end - positionMs) / speed).toLong()
            if (remaining <= 0) {
                finishGesture(true)
            } else {
                press(
                    keys.noteX[note.keyIndex],
                    keys.noteY,
                    remaining,
                    { playing && token == generation },
                    ::finishGesture,
                )
            }
        }
    }

    private fun finishGesture(success: Boolean) {
        busy = false
        if (!success) {
            pause()
            error = gestureFailure ?: "手势未完成"
            AppLog.w(TAG, error ?: "手势未完成")
        } else if (playing) {
            handler.post(next)
        }
    }

    private fun ensurePitch(
        note: NoteEvent,
        done: (Boolean) -> Unit,
    ) {
        val token = generation

        fun half() {
            if (token != generation) return
            if (halfToneOn == note.halfTone) {
                done(true)
            } else {
                control(PlayMode.SEMITONE) { success ->
                    if (token != generation) return@control
                    if (success) halfToneOn = note.halfTone
                    done(success)
                }
            }
        }
        if (pitchMode == note.mode) {
            half()
        } else {
            control(note.mode) { success ->
                if (token != generation) return@control
                if (success) {
                    pitchMode = note.mode
                    half()
                } else {
                    done(false)
                }
            }
        }
    }

    private fun control(
        mode: PlayMode,
        done: (Boolean) -> Unit,
    ) {
        val point = keys.modes.getValue(mode)
        // 音区按钮是切换点击；保留 1 毫秒手势，保证立即切换且不会成为长按。
        press(point[0], point[1], 1, { true }, done)
    }

    private fun press(
        x: Float,
        y: Float,
        duration: Long,
        active: () -> Boolean,
        done: (Boolean) -> Unit,
    ) {
        val currentDisplay = displayState()
        if (playing && playbackDisplay != currentDisplay) {
            beginDisplayRecovery()
            return
        }
        if (!playing || !active() || playbackDisplay != currentDisplay) {
            gestureFailure = "播放已停止或屏幕方向已变化，请重新点击播放"
            done(false)
            return
        }
        val frame =
            coordinateFrame
                ?: if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    PlaybackCoordinates.Frame(currentDisplay.width, currentDisplay.height)
                } else {
                    gestureFailure = "缺少无障碍截图坐标，请重新识别"
                    done(false)
                    return
                }
        val bounds = Rect(0, 0, frame.width, frame.height)
        // 使用生成当前布局时的同一像素空间，避免截图、显示尺寸混用。
        if (!PlaybackCoordinates.validPoint(x, y)) {
            gestureFailure = "按键坐标无效，请重新识别"
            AppLog.log("坐标无效：x=$x y=$y 显示=$currentDisplay")
            done(false)
            return
        }
        val (px, py) = frame.point(x, y)
        gestureFailure = null
        lastCoordinates = "ratio=($x,$y) px=($px,$py) 显示=$currentDisplay"
        AppLog.log(
            "按下：归一化坐标=($x,$y) -> ${px.toInt()},${py.toInt()} 边界=${bounds.width()}x${bounds.height()} 显示=$currentDisplay 时长毫秒=$duration"
        )
        val path = Path().apply { moveTo(px, py) }
        var completed = false
        val token = generation

        fun finish(value: Boolean) {
            if (token != generation) return
            if (playbackDisplay != displayState()) {
                beginDisplayRecovery()
                return
            }
            if (!completed) {
                completed = true
                done(value)
            }
        }
        // 每个音符发送一条完整静态手势；将长音拆成多次 dispatchGesture 在部分系统上会被取消。
        val length = duration.coerceIn(1, GestureDescription.getMaxGestureDuration())
        try {
            val stroke = GestureDescription.StrokeDescription(path, 0, length, false)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val accepted =
                binding.gesture(
                    gesture,
                    AccessibilityBinding.GestureCallback { success ->
                        if (token != generation) return@GestureCallback
                        if (success) {
                            AppLog.log("手势完成：坐标=${px.toInt()},${py.toInt()}")
                            floating.mark(px, py)
                            finish(true)
                        } else {
                            interruptionGuard.interrupted(SystemClock.uptimeMillis())
                            AppLog.log("手势被取消：坐标=${px.toInt()},${py.toInt()}")
                            gestureFailure =
                                "手势被系统取消 (${px.toInt()},${py.toInt()} / ${bounds.width()}x${bounds.height()})"
                            finish(false)
                        }
                    },
                )
            if (!accepted) {
                AppLog.log("系统拒绝手势：坐标=${px.toInt()},${py.toInt()}")
                gestureFailure =
                    "系统拒绝手势 (${px.toInt()},${py.toInt()} / ${bounds.width()}x${bounds.height()})"
                finish(false)
            }
        } catch (failure: Exception) {
            AppLog.w(TAG, "无法发送播放手势", failure)
            gestureFailure = "无法发送播放手势，请重新开启无障碍后重试"
            finish(false)
        }
    }
}
