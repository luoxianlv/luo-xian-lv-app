package app.luoxianlv.service
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import app.luoxianlv.BuildConfig
import app.luoxianlv.core.Analytics
import app.luoxianlv.core.playback.PlaybackTimeline
import app.luoxianlv.core.score.NoteEvent
import app.luoxianlv.core.score.PlayMode
import app.luoxianlv.data.ConfigStore
import app.luoxianlv.data.KeyLayout
import app.luoxianlv.data.Song
import app.luoxianlv.data.SongRepository
import app.luoxianlv.debug.PlaybackDebugLog
import app.luoxianlv.profile.ScreenRecognizer
import app.luoxianlv.update.MidiCoreFixer

class MusicAccessibilityService : AccessibilityService() {
    data class Diagnostics(
        val serviceEnabled: Boolean,
        val playing: Boolean,
        val preparing: Boolean,
        val songTitle: String,
        val display: Triple<Int, Int, Int>?,
        val error: String?,
        val gestureFailure: String?,
        val playbackDisplay: Triple<Int, Int, Int>?,
        val lastCoordinates: String?,
    )

    companion object {
        var instance: MusicAccessibilityService? = null
            private set
        const val TAG = "落弦律Gesture"

        /** 系统无障碍设置中本服务是否已开启（instance 只在服务运行期间非空，不能用于判断）。
         * 走 AccessibilityManager 已启用服务列表按包名匹配：
         * Settings.Secure 字符串存在全类名/短类名两种格式，逐字比对在部分 ROM 上会误判。 */
        fun isEnabled(context: Context): Boolean {
            val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
            return manager
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var repository: SongRepository
    private lateinit var keys: KeyLayout
    private lateinit var floating: FloatingControls
    lateinit var song: Song
        private set
    private lateinit var timeline: PlaybackTimeline
    var playing = false
        private set

    /** True while the pre-play screenshot recognition is in flight. */
    var preparing = false
        private set
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
    private var playbackDisplay: Triple<Int, Int, Int>? = null
    private var recoveringDisplay = false
    private var recoveryAttempts = 0
    private var recoveryWasPlaying = false
    private val displayStability = DisplayStability()
    private val monitorDisplay =
        object : Runnable {
            override fun run() {
                if ((playing || preparing) && !recoveringDisplay && playbackDisplay != displayState()) beginDisplayRecovery()
                handler.postDelayed(this, 150)
            }
        }
    private val displayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY && (playing || preparing) && playbackDisplay != displayState()) {
                    beginDisplayRecovery()
                }
            }
        }

    private fun beginDisplayRecovery() {
        PlaybackDebugLog.log(
            "display recovery begin playback=$playbackDisplay current=" + displayState() + " wasPlaying=" + playing + " preparing=" +
                preparing,
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
                // The logical display and screenshot producer settle at different times.
                if (!displayStability.ready(current, SystemClock.uptimeMillis())) {
                    handler.postDelayed(this, 120)
                    return
                }
                playbackDisplay = current
                val token = ++generation
                var completed = false
                val timeout =
                    Runnable {
                        if (token == generation && recoveringDisplay && !completed) {
                            completed = true
                            generation++
                            handler.postDelayed(this, 500)
                        }
                    }
                handler.postDelayed(timeout, 2000)
                syncWithScreen(token) { recognized ->
                    if (token != generation || !recoveringDisplay || completed) return@syncWithScreen
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
        PlaybackDebugLog.log("display recovery finish resume=$resume")
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

    private fun displayState(): Triple<Int, Int, Int> {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val size = Point()
        @Suppress("DEPRECATION")
        display.getRealSize(size)
        return Triple(size.x, size.y, display.rotation)
    }

    val durationMs get() = timeline.durationMs
    val positionMs get() = (baseMs + if (playing) ((SystemClock.uptimeMillis() - anchor) * speed).toLong() else 0).coerceIn(0, durationMs)
    val modeLabel get() = pitchMode.label + if (halfToneOn) " · 半音" else ""
    private val next = Runnable { drive() }

    override fun onServiceConnected() {
        PlaybackDebugLog.init(this)
        PlaybackDebugLog.log(
            "service connected ${Build.MANUFACTURER}/${Build.MODEL} sdk=${Build.VERSION.SDK_INT} ${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) display=${displayState()}",
        )
        repository = SongRepository(this)
        keys = ConfigStore.load(this)
        song = repository.selected()
        timeline = PlaybackTimeline(song.events, song.bpm)
        speed = repository.speed
        floating = FloatingControls(this)
        instance = this
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, handler)
        handler.post(monitorDisplay)
        if (repository.floatingEnabled) handler.postDelayed({ if (repository.floatingEnabled) showFloating(true) }, 250)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = pause()

    override fun onDestroy() {
        stopService(Intent(this, PlaybackForegroundService::class.java))
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        playing = false
        generation++
        handler.removeCallbacksAndMessages(null)
        recoveringDisplay = false
        if (::floating.isInitialized) floating.destroy()
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun select(selected: Song) {
        pause()
        song = selected
        fixAttemptedForSong = false
        repository.selectedId = selected.id
        timeline = PlaybackTimeline(selected.events, selected.bpm)
        baseMs = 0
        error = null
        floating.refresh()
    }

    fun toggle() {
        when {
            // 埋点：用户手动暂停演奏（seek/变速等内部调用 pause() 的路径不计）
            playing -> {
                Analytics.logEvent(this, "play_stop")
                pause()
            }
            preparing -> pause()
            else -> play()
        }
    }

    fun play() {
        if (playing || preparing || recoveringDisplay || timeline.events.isEmpty()) return
        if (baseMs >= durationMs) baseMs = 0
        // 标记 needsFix 的歌：播放前先自动尝试一次远端重编（每选中一次只试一次），
        // 失败给出提示，引导回 App 内曲库手动修复；没有 remoteId 的只能靠播放兜底修剪。
        if (song.needsFix && song.remoteId.isNotBlank() && !fixAttemptedForSong) {
            fixAttemptedForSong = true
            attemptRemoteFixThenPlay()
            return
        }
        error = null
        // Sync with the real screen once before the first note: locate the
        // keyboard by image recognition (stored ratios break on tablets and
        // other aspect ratios) and read back the pitch state in case the user
        // toggled 半音/升降调 directly in the game. Screenshot needs API 30;
        // below that the stored layout is used as before.
        playbackDisplay = displayState()
        PlaybackDebugLog.log("play() display=$playbackDisplay baseMs=$baseMs speed=$speed")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            preparing = true
            val token = ++generation
            floating.refresh()
            val timeout =
                Runnable {
                    if (token == generation && preparing) {
                        generation++
                        preparing = false
                        error = "截图识别超时，请重试"
                        PlaybackDebugLog.log("initial recognition timeout")
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
                    PlaybackDebugLog.log("play blocked: recognition failed display=" + displayState())
                    floating.refresh()
                    return@syncWithScreen
                }
                if (token == generation) startPlaying() else floating.refresh()
            }
        } else {
            startPlaying()
        }
    }

    /**
     * needsFix 歌曲的播放前自动修复：远端重编成功就换新谱面继续播放；
     * 失败留在当前页并提示回 App 内修复。全程在后台线程跑，不阻塞手势主循环。
     */
    private fun attemptRemoteFixThenPlay() {
        preparing = true
        error = "正在修复谱面…"
        floating.refresh()
        MidiCoreFixer.fixSong(this, song) { ok ->
            handler.post {
                preparing = false
                if (!ok) {
                    error = "谱面修复失败，请到 App 内曲库中修复该谱子"
                    floating.refresh()
                    return@post
                }
                // 重新载入该曲（updateCompiled 已覆盖缓存并清除 needsFix），再正常起播。
                runCatching {
                    SongRepository(this).songs().first { it.id == song.id }
                }.getOrNull()?.let { updated ->
                    if (updated.id == song.id && updated.score != song.score) select(updated)
                }
                play()
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

    /** Takes a screenshot, recognizes the keyboard, persists the layout and
     * syncs the pitch state. The callback runs on the main thread. */
    private fun syncWithScreen(
        token: Int,
        done: (Boolean) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            done(false)
            return
        }
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val currentDisplay = displayState()
                        if (token != generation || playbackDisplay != currentDisplay ||
                            screenshot.hardwareBuffer.width != currentDisplay.first ||
                            screenshot.hardwareBuffer.height != currentDisplay.second
                        ) {
                            PlaybackDebugLog.log(
                                "screenshot mismatch tokenAlive=${token == generation} playback=$playbackDisplay current=$currentDisplay shot=${screenshot.hardwareBuffer.width}x${screenshot.hardwareBuffer.height}",
                            )
                            screenshot.hardwareBuffer.close()
                            done(false)
                            return
                        }
                        val started = SystemClock.uptimeMillis()
                        val result =
                            runCatching {
                                val hardware =
                                    try {
                                        Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                                    } finally {
                                        screenshot.hardwareBuffer.close()
                                    }
                                val bitmap =
                                    try {
                                        hardware?.copy(Bitmap.Config.ARGB_8888, false)
                                    } finally {
                                        hardware?.recycle()
                                    }
                                bitmap?.let(PlaybackDebugLog::saveScreenshot)
                                PlaybackDebugLog.log("screenshot ${bitmap?.width}x${bitmap?.height} analyze start")
                                try {
                                    bitmap?.let(ScreenRecognizer::fromBitmap)
                                } finally {
                                    bitmap?.recycle()
                                }
                            }.onFailure {
                                Log.w(TAG, "截图识别失败", it)
                                PlaybackDebugLog.log("recognize failure: ${it.message}")
                            }.getOrNull()
                        PlaybackDebugLog.log("recognition elapsedMs=${SystemClock.uptimeMillis() - started}")
                        PlaybackDebugLog.log(
                            result?.let { r ->
                                "recognized noteX=" + r.layout.noteX.joinToString(",") { v -> "%.3f".format(v) } +
                                    " noteY=" + "%.3f".format(r.layout.noteY) + " mode=" + r.mode + " half=" + r.halfTone
                            } ?: "recognize returned null",
                        )
                        if (token != generation || playbackDisplay != displayState()) {
                            done(false)
                            return
                        }
                        if (result != null && PlaybackCoordinates.validLayout(result.layout)) {
                            keys = result.layout
                            ConfigStore.save(this@MusicAccessibilityService, result.layout)
                            result.mode?.let { pitchMode = it }
                            result.halfTone?.let { halfToneOn = it }
                            Log.i(TAG, "按键识别成功 mode=${result.mode} half=${result.halfTone}")
                            floating.refresh()
                        }
                        done(result != null && PlaybackCoordinates.validLayout(result.layout))
                    }

                    override fun onFailure(errorCode: Int) {
                        PlaybackDebugLog.log("screenshot failure errorCode=$errorCode")
                        Log.w(TAG, "截图失败 errorCode=$errorCode")
                        done(false)
                    }
                },
            )
        } catch (failure: Exception) {
            Log.w(TAG, "无法请求截图", failure)
            done(false)
        }
    }

    fun pause() {
        PlaybackDebugLog.log("pause() playing=$playing preparing=$preparing")
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
        // 埋点：通知栏「停止并关闭悬浮窗」等显式停止（播放中才计，避免与 toggle 暂停重复）
        if (playing) Analytics.logEvent(this, "play_stop")
        pause()
        baseMs = 0
        floating.refresh()
    }

    fun seek(milliseconds: Long) {
        val resume = playing
        pause()
        baseMs = milliseconds.coerceIn(0, durationMs)
        if (resume) play() else floating.refresh()
    }

    fun setSpeed(value: Float) {
        val resume = playing
        pause()
        speed = value.coerceIn(.5f, 2f)
        repository.speed = speed
        if (resume) play()
    }

    /**
     * 悬浮窗此刻是否真的显示着。
     *
     * 界面用它（而不是持久化偏好）判断「运行中」：服务没连上时窗口一定不存在，
     * 而 `SongRepository.floatingEnabled` 只是用户意图，可能和现实不一致。
     */
    val floatingVisible: Boolean
        get() = ::floating.isInitialized && floating.isVisible

    /**
     * 深色模式切换后让悬浮窗重绘一次。
     *
     * 悬浮窗是本服务里的独立系统窗口，不跟着 Activity 重组，
     * 所以主题变更得显式通知它（见 `FloatingControls.refreshTheme`）。
     */
    fun refreshFloatingTheme() {
        if (::floating.isInitialized) floating.refreshTheme()
    }

    fun showFloating(enabled: Boolean) {
        repository.floatingEnabled = enabled
        if (enabled) {
            floating.show()
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(Intent(this, PlaybackForegroundService::class.java))
                } else {
                    startService(Intent(this, PlaybackForegroundService::class.java))
                }
            }.onFailure { Log.w(TAG, "启动播放前台服务失败", it) }
        } else {
            floating.hide()
            stopService(Intent(this, PlaybackForegroundService::class.java))
        }
    }

    fun reloadConfig() {
        keys = ConfigStore.load(this)
    }

    fun screenBounds(): Rect {
        val (width, height) = displayState()
        return Rect(0, 0, width, height)
    }

    fun diagnostics(): Diagnostics =
        Diagnostics(
            serviceEnabled = isEnabled(this),
            playing = playing,
            preparing = preparing,
            songTitle = if (::song.isInitialized) song.title else "未选择",
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
                press(keys.noteX[note.keyIndex], keys.noteY, remaining, { playing && token == generation }, ::finishGesture)
            }
        }
    }

    private fun finishGesture(success: Boolean) {
        busy = false
        if (!success) {
            pause()
            error = gestureFailure ?: "手势未完成"
            Log.w(TAG, error ?: "手势未完成")
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
        // Pitch controls are toggle taps. Keep the configured 1 ms click so
        // switching remains instantaneous and never turns into a hold.
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
        val bounds = Rect(0, 0, currentDisplay.first, currentDisplay.second)
        // Clamp normalized coordinates against the active landscape display.
        // This prevents a stale calibration value or a cutout inset from
        // producing an out-of-bounds gesture that Android cancels.
        if (!PlaybackCoordinates.validPoint(x, y)) {
            gestureFailure = "按键坐标无效，请重新识别"
            PlaybackDebugLog.log("invalid coordinate x=$x y=$y display=$currentDisplay")
            done(false)
            return
        }
        val px = x * (bounds.width() - 1).coerceAtLeast(1)
        val py = y * (bounds.height() - 1).coerceAtLeast(1)
        gestureFailure = null
        lastCoordinates = "ratio=($x,$y) px=($px,$py) display=$currentDisplay"
        PlaybackDebugLog.log(
            "press ratio=($x,$y) -> ${px.toInt()},${py.toInt()} bounds=${bounds.width()}x${bounds.height()} display=$currentDisplay duration=$duration",
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
        // Dispatch one complete static stroke per note. Splitting a long note
        // across several dispatchGesture calls is not supported consistently
        // by Android/OEM accessibility implementations and causes cancellation.
        val length = duration.coerceIn(1, GestureDescription.getMaxGestureDuration())
        try {
            val stroke = GestureDescription.StrokeDescription(path, 0, length, false)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val accepted =
                dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription) {
                            if (token != generation) return
                            PlaybackDebugLog.log("gesture completed at ${px.toInt()},${py.toInt()}")
                            floating.mark(px, py)
                            finish(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription) {
                            if (token != generation) return
                            PlaybackDebugLog.log("gesture cancelled at ${px.toInt()},${py.toInt()}")
                            gestureFailure = "手势被系统取消 (${px.toInt()},${py.toInt()} / ${bounds.width()}x${bounds.height()})"
                            finish(false)
                        }
                    },
                    handler,
                )
            if (!accepted) {
                PlaybackDebugLog.log("gesture rejected by system at ${px.toInt()},${py.toInt()}")
                gestureFailure = "系统拒绝手势 (${px.toInt()},${py.toInt()} / ${bounds.width()}x${bounds.height()})"
                finish(false)
            }
        } catch (failure: Exception) {
            Log.w(TAG, "无法发送播放手势", failure)
            PlaybackDebugLog.log("dispatch exception: ${failure.message}")
            gestureFailure = "无法发送播放手势，请重新开启无障碍后重试"
            finish(false)
        }
    }
}
