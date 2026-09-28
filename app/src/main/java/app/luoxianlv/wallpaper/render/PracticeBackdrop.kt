package app.luoxianlv.wallpaper.render

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.util.Log
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import app.luoxianlv.wallpaper.data.WallpaperProjectStore
import kotlinx.coroutines.*

/** Offline scene renderer; originals have no native bridge, app-file access or network access. */
@SuppressLint("SetJavaScriptEnabled")
class PracticeBackdrop(context: Context, deferRendering: Boolean = false) : FrameLayout(context) {
    var renderState = "default"
        private set

    private var web: WebView? = null
    private var closed = false
    private var suspended = false
    var onPrepared: (() -> Unit)? = null
    val prepared
        get() = renderState == "ready" || renderState == "static" || renderState == "error"

    private val previewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val posterView =
        ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }

    private val project = WallpaperProjectStore.root(context)

    init {
        addView(posterView, LayoutParams(-1, -1))
        previewScope.launch {
            val preview = withContext(Dispatchers.IO) { WallpaperPreview.load(context, project) }
            if (!closed && renderState != "ready") {
                posterView.setImageDrawable(preview)
                if (!suspended) (preview as? Animatable)?.start()
                if (project == null && !WallpaperProjectStore.hasBundled(context)) {
                    renderState = "static"
                    onPrepared?.invoke()
                }
            }
        }
        if (!deferRendering) startRendering()
    }

    /** Heavy WebView/GPU startup is postponed until the opening animation has finished. */
    fun startRendering() {
        if (closed || web != null || renderState == "error") return
        if (project != null || WallpaperProjectStore.hasBundled(context)) {
            renderState = "loading"
            val browser = WebView(context)
            web = browser
            // Alpha zero can suspend WebView's compositor and prevent its first video frame.
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
                        fail()
                        return true
                    }
                }
            browser.webChromeClient =
                object : WebChromeClient() {
                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        if (title == "wallpaper:ready" && !closed && renderState == "loading") {
                            renderState = "ready"
                            (posterView.drawable as? Animatable)?.stop()
                            posterView.setImageDrawable(null)
                            browser.alpha = 1f
                            onPrepared?.invoke()
                            if (suspended) suspendRendering()
                        }
                        if (title == "wallpaper:error") fail()
                    }

                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        Log.d("PracticeWallpaper", message.message().take(1800))
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
                if (!closed && renderState == "loading") fail()
            }
        }
    }

    private fun fail() {
        if (closed || renderState == "error") return
        renderState = "error"
        releaseWeb()
        previewScope.launch {
            val preview = withContext(Dispatchers.IO) { WallpaperPreview.load(context, project) }
            if (!closed && renderState == "error") {
                posterView.setImageDrawable(preview)
                if (!suspended) (preview as? Animatable)?.start()
            }
        }
        onPrepared?.invoke()
        Toast.makeText(context, "此场景暂不兼容，已使用默认背景", Toast.LENGTH_LONG).show()
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
        web?.let { browser ->
            // onPause alone does not stop JavaScript or the scene's video decoders.
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
}
