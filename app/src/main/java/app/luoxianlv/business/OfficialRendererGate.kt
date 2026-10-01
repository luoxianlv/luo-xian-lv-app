package app.luoxianlv.business

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import app.luoxianlv.hot.contract.OfficialAssets
import app.luoxianlv.hot.contract.OfficialResources
import java.io.ByteArrayInputStream

/** 一个业务加载器至多持有一个验证WebView；窗口共享结果，退役取消并释放所有回调。 */
internal object OfficialRendererGate {
    private var source: OfficialResources? = null
    private var job: Check? = null
    private var retired = false
    private var jsPassed = false
    private var visualPassed = false
    private var failure: Throwable? = null
    private data class Waiter(val visual: Boolean, val parent: ViewGroup?, val complete: (Throwable?) -> Unit)
    private val waiters = linkedMapOf<Any, Waiter>()
    private val failures = linkedMapOf<Any, (Throwable?) -> Unit>()

    fun bind(resources: OfficialResources) {
        check(!retired)
        require(source == null || source === resources) { "官方资源不能跨代重绑定" }
        source = resources
    }
    fun required() = !retired && source?.mounted("wallpaperengine") == true
    fun resourcesReady() = !retired && (!required() || (visualPassed && failure == null))
    fun released() = job == null && waiters.isEmpty() && failures.isEmpty()

    fun verify(context: Context, parent: ViewGroup?, visual: Boolean, complete: (Throwable?) -> Unit): AutoCloseable {
        check(Looper.myLooper() == Looper.getMainLooper() && !retired)
        if (!required()) {
            complete(null); return AutoCloseable {}
        }
        failure?.let { complete(it); return AutoCloseable {} }
        val token = Any(); failures[token] = complete
        if ((visual && visualPassed) || (!visual && jsPassed)) complete(null)
        else {
            waiters[token] = Waiter(visual, parent, complete)
            try {
                if (job == null) job = Check(context)
                if (parent != null) job?.attach(parent)
                job?.start()
            } catch (error: Throwable) { finish(false, false, error) }
        }
        return AutoCloseable {
            waiters.remove(token); failures.remove(token)
            if (waiters.isEmpty()) { job?.close(); job = null }
            else waiters.values.firstOrNull { it.visual && it.parent != null }?.parent?.let { job?.attach(it) }
        }
    }
    private fun finish(js: Boolean, visual: Boolean, error: Throwable? = null) {
        if (error != null) {
            failure = error; jsPassed = false; visualPassed = false
            val notify = failures.values.toList()
            waiters.clear(); failures.clear(); job?.close(); job = null
            notify.forEach { it(error) }
        } else {
            jsPassed = jsPassed || js; visualPassed = visualPassed || visual
            val notify = waiters.entries.filter { if (it.value.visual) visualPassed else jsPassed }
            notify.forEach { waiters.remove(it.key) }
            if (waiters.isEmpty()) { job?.close(); job = null }
            notify.forEach { it.value.complete(null) }
        }
    }
    fun retire() {
        retired = true; waiters.clear(); failures.clear(); job?.close(); job = null
    }
    fun reject(error: Throwable) { visualPassed = false; finish(false, false, error) }

    @Suppress("SetJavaScriptEnabled")
    private class Check(private val context: Context) : AutoCloseable {
        private val handler = Handler(Looper.getMainLooper())
        private val lease = checkNotNull(BusinessJobs.gate.retain()) { "官方资源检查代际已退役" }
        private val browser = try {
            WebView(context).also { value ->
                try { value.apply {
                    alpha = .01f; setBackgroundColor(Color.TRANSPARENT)
                    isFocusable = false; isClickable = false
                    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    layoutParams = FrameLayout.LayoutParams(16, 16)
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false; settings.allowContentAccess = false
                    settings.domStorageEnabled = false; settings.databaseEnabled = false
                    settings.blockNetworkLoads = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                } } catch (error: Throwable) { value.destroy(); throw error }
            }
        } catch (error: Throwable) { lease.close(); throw error }
        private var closed = false
        private var started = false
        private var jsReady = false
        private var visualRequested = false
        private var visualEpoch = 0L
        private val timeout = Runnable { if (!closed) finish(false, false, IllegalStateException("官方渲染器首帧检查超时")) }
        init {
            browser.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse {
                    fun response(mime: String, text: String) = WebResourceResponse(mime, "UTF-8", ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
                    fun denied() = WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                    val url = request?.url
                    if (url?.scheme != "https" || url.host != "practice.invalid" || request.method != "GET") return denied()
                    return try {
                        when (val path = url.path.orEmpty()) {
                            "/project/project.json" -> response("application/json", "{\"type\":\"image\",\"file\":\"pixel.svg\"}")
                            "/project/pixel.svg" -> response("image/svg+xml", "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"4\" height=\"4\"><rect width=\"4\" height=\"4\" fill=\"#789abc\"/></svg>")
                            else -> {
                                val relative = path.removePrefix("/")
                                if (relative !in setOf("index.html", "host.mjs", "webwallgl.mjs", "compat.mjs", "clock.mjs", "scene-video.mjs", "lifecycle.mjs", "audio.mjs")) return denied()
                                WebResourceResponse(if (relative.endsWith("html")) "text/html" else "application/javascript", "UTF-8",
                                    OfficialAssets.open(context, "wallpaperengine", relative, "wallpaperengine/$relative"))
                            }
                        }
                    } catch (error: Throwable) {
                        handler.post { if (!closed) finish(false, false, error) }
                        response("text/plain", "")
                    }
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    if (!closed) finish(false, false, IllegalStateException("官方渲染检查进程退出"))
                    return true
                }
            }
            browser.webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    if (closed) return
                    if (title == "wallpaper:error") finish(false, false, IllegalStateException("官方渲染器固定项目运行失败"))
                    if (title == "wallpaper:ready") { jsReady = true; requestVisual(); finish(true, false) }
                }
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    if (!closed && message.messageLevel() == ConsoleMessage.MessageLevel.ERROR &&
                        (message.message().startsWith("Uncaught") || message.message().contains("SyntaxError") || message.message().startsWith("壁纸加载失败")))
                        finish(false, false, IllegalStateException("官方渲染器固定项目脚本失败"))
                    return true
                }
            }
        }
        fun attach(parent: ViewGroup) {
            if (closed) return
            if (browser.parent !== parent) {
                (browser.parent as? ViewGroup)?.removeView(browser); parent.addView(browser)
                visualEpoch++; visualRequested = false
            }
            requestVisual()
        }
        fun start() {
            if (closed || started) return
            started = true; handler.postDelayed(timeout, 12000)
            browser.loadUrl("https://practice.invalid/index.html")
        }
        private fun requestVisual() {
            if (closed || !jsReady || browser.parent == null || visualRequested) return
            visualRequested = true
            val epoch = visualEpoch
            browser.postVisualStateCallback(1, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { if (!closed && visualEpoch == epoch) finish(true, true) }
            })
        }
        override fun close() {
            if (closed) return; closed = true
            handler.removeCallbacksAndMessages(null)
            try {
                browser.stopLoading(); (browser.parent as? ViewGroup)?.removeView(browser)
                browser.webChromeClient = null; browser.webViewClient = WebViewClient(); browser.destroy()
            } finally { lease.close() }
        }
    }
}
