package app.luoxianlv.wallpaper.render

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import app.luoxianlv.business.BusinessJobs
import app.luoxianlv.debug.AppLog
import app.luoxianlv.wallpaper.data.WallpaperProjectStore
import kotlinx.coroutines.*

/** 离线场景渲染器；原始项目无法访问原生桥、应用文件或网络。 */
@SuppressLint("SetJavaScriptEnabled")
class PracticeBackdrop(context: Context, deferRendering: Boolean = false) : FrameLayout(context) {
    var renderState = "default"
        private set

    private var web: WebView? = null
    private var closed = false
    private var suspended = false
    private var soundEnabled = false
    private var initialized = false
    private var renderingRequested = !deferRendering
    var onPrepared: (() -> Unit)? = null
    val prepared
        get() = renderState == "ready" || renderState == "static" || renderState == "error"

    private val previewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val posterView =
        ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }

    private var project: java.io.File? = null

    init {
        addView(posterView, LayoutParams(-1, -1))
        previewScope.launch {
            project = BusinessJobs.io {
                WallpaperProjectStore.root(context)
            }
            if (closed) return@launch
            initialized = true
            if (renderingRequested) startRendering()
            val preview = BusinessJobs.io { WallpaperPreview.load(context, project) }
            if (!closed && renderState != "ready") {
                posterView.setImageDrawable(preview)
                if (!suspended) (preview as? Animatable)?.start()
                if (project == null && !WallpaperProjectStore.hasLegacyBundled(context)) {
                    renderState = "static"
                    onPrepared?.invoke()
                }
            }
        }
    }

    /** 按需启动渲染；首页可提前调用，等待真实首帧后移交演练场。 */
    fun startRendering() {
        renderingRequested = true
        if (!initialized) return
        if (closed || web != null || renderState == "error") return
        if (project != null || WallpaperProjectStore.hasLegacyBundled(context)) {
            renderState = "loading"
            AppLog.i("壁纸", "开始加载：${project?.absolutePath ?: "内置项目"}")
            val browser = WebView(context)
            web = browser
            // 透明度为零可能停止 WebView 合成，导致首帧视频无法就绪。
            browser.alpha = .01f
            browser.setBackgroundColor(Color.TRANSPARENT)
            browser.isFocusable = false
            browser.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            browser.settings.apply {
                javaScriptEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                domStorageEnabled = false
                databaseEnabled = false
                setGeolocationEnabled(false)
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                mediaPlaybackRequiresUserGesture = false
                blockNetworkLoads = true
            }
            browser.webViewClient =
                object : WebViewClient() {
                    private val resources = WallpaperResources(context, project)

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        if (request == null || request.isForMainFrame) return true
                        return request.url.scheme != "blob" &&
                            !(request.url.scheme == "https" &&
                                request.url.host == "practice.invalid")
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? = request?.let(resources::response)

                    override fun onRenderProcessGone(
                        view: WebView,
                        detail: RenderProcessGoneDetail,
                    ): Boolean {
                        fail(
                            "渲染进程退出：崩溃=${detail.didCrash()}，优先级=${detail.rendererPriorityAtExit()}"
                        )
                        return true
                    }
                }
            browser.webChromeClient =
                object : WebChromeClient() {
                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        if (title == "wallpaper:ready" && !closed && renderState == "loading") {
                            renderState = "ready"
                            AppLog.i("壁纸", "首帧已就绪")
                            (posterView.drawable as? Animatable)?.stop()
                            posterView.setImageDrawable(null)
                            browser.alpha = 1f
                            onPrepared?.invoke()
                            if (suspended) suspendRendering() else applySound()
                        }
                        if (title == "wallpaper:error") fail("引擎报错，详细原因见前一条引擎日志")
                    }

                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        val text =
                            "引擎诊断（${message.sourceId()}:${message.lineNumber()}）：${message.message()}"
                        when (message.messageLevel()) {
                            ConsoleMessage.MessageLevel.ERROR -> AppLog.e("壁纸", text)
                            ConsoleMessage.MessageLevel.WARNING -> AppLog.w("壁纸", text)
                            else -> AppLog.d("壁纸", text)
                        }
                        return true
                    }
                }
            addView(browser, LayoutParams(-1, -1))
            val time = WallpaperProjectStore.minute(context)?.let { "?minute=$it" }.orEmpty()
            browser.loadUrl("https://practice.invalid/index.html$time")
            previewScope.launch {
                var activeWait = 0
                while (!closed && renderState == "loading" && activeWait < 60) {
                    delay(1000)
                    if (!suspended) activeWait++
                }
                if (!closed && renderState == "loading") fail("首帧等待超过 60 秒")
            }
        }
    }

    private fun fail(reason: String) {
        if (closed || renderState == "error") return
        val wasPlaying = renderState == "ready"
        renderState = "error"
        AppLog.w("壁纸", "渲染失败，切换到预览背景：$reason")
        releaseWeb()
        previewScope.launch {
            val preview = BusinessJobs.io { WallpaperPreview.load(context, project) }
            if (!closed && renderState == "error") {
                posterView.setImageDrawable(preview)
                if (!suspended) (preview as? Animatable)?.start()
            }
        }
        onPrepared?.invoke()
        Toast.makeText(
                context,
                if (wasPlaying) "壁纸播放中断，已切换为预览" else "壁纸暂时无法加载，已使用预览",
                Toast.LENGTH_LONG,
            )
            .show()
    }

    private fun releaseWeb() {
        web?.let {
            removeView(it)
            it.stopLoading()
            it.onPause()
            it.destroy()
        }
        web = null
    }

    fun suspendRendering() {
        suspended = true
        applySound()
        web?.let { browser ->
            // 仅调用 onPause 不会停止 JavaScript 和场景视频解码器。
            browser.evaluateJavascript(
                "window.wallpaperSuspended=true;window.setWallpaperSuspended?.(true)"
            ) {
                if (!closed && suspended && web === browser) browser.onPause()
            }
        }
        (posterView.drawable as? Animatable)?.stop()
    }

    fun resumeRendering() {
        if (closed) return
        suspended = false
        web?.let {
            it.onResume()
            it.evaluateJavascript(
                "window.wallpaperSuspended=false;window.setWallpaperSuspended?.(false)",
                null,
            )
        }
        applySound()
        if (renderState != "ready") (posterView.drawable as? Animatable)?.start()
    }

    fun close() {
        onPrepared = null
        closed = true
        previewScope.cancel()
        (posterView.drawable as? Animatable)?.stop()
        posterView.setImageDrawable(null)
        releaseWeb()
    }

    /** 只有可见演练场能请求声音；预加载实例始终保持关闭。 */
    fun setSoundEnabled(enabled: Boolean) {
        soundEnabled = enabled
        applySound()
    }

    private fun applySound() {
        val audible = soundEnabled && !suspended && !closed
        web?.evaluateJavascript("window.setWallpaperSoundEnabled?.($audible)", null)
    }
}
