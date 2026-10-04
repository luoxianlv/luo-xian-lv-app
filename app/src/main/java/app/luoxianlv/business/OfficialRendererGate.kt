package app.luoxianlv.business

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.*
import android.widget.FrameLayout
import app.luoxianlv.business.ui.findActivity
import app.luoxianlv.hot.contract.OfficialAssets
import app.luoxianlv.hot.contract.OfficialResources
import java.io.ByteArrayInputStream
import app.luoxianlv.wallpaper.render.WebViewStartup
import kotlinx.coroutines.*

/** 一个业务加载器至多持有一个验证WebView；窗口共享结果，退役取消并释放所有回调。 */
internal object OfficialRendererGate {
    private var source: OfficialResources? = null
    private var job: Check? = null
    private var startup: Job? = null
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var retired = false
    private var jsPassed = false
    private var visualPassed = false
    private var failure: Throwable? = null
    private data class Waiter(val visual: Boolean, val parent: ViewGroup?, val activity: Activity?, val complete: (Throwable?) -> Unit)
    private val waiters = linkedMapOf<Any, Waiter>()
    private val failures = linkedMapOf<Any, (Throwable?) -> Unit>()

    fun bind(resources: OfficialResources) {
        check(!retired)
        require(source == null || source === resources) { "官方资源不能跨代重绑定" }
        source = resources
    }
    fun required() = !retired && source?.mounted("wallpaperengine") == true
    fun resourcesReady() = !retired && (!required() || (visualPassed && failure == null))
    fun released() = job == null && startup?.isActive != true && waiters.isEmpty() && failures.isEmpty()

    fun verify(context: Context, parent: ViewGroup?, visual: Boolean, complete: (Throwable?) -> Unit): AutoCloseable {
        check(Looper.myLooper() == Looper.getMainLooper() && !retired)
        if (!required()) {
            complete(null); return AutoCloseable {}
        }
        failure?.let { complete(it); return AutoCloseable {} }
        val activity = context.findActivity()
        require(!visual || (activity != null && parent === activity.window.decorView)) { "官方渲染可见检查缺少实际Activity Decor" }
        val token = Any(); failures[token] = complete
        if ((visual && visualPassed) || (!visual && jsPassed)) complete(null)
        else {
            waiters[token] = Waiter(visual, parent, activity, complete)
            if (job != null) job?.refreshWindow()
            else if (startup?.isActive != true) startup = startupScope.launch {
                try {
                    WebViewStartup.await(context)
                    if (!retired && waiters.isNotEmpty()) {
                        job = Check(context)
                        job?.start()
                        job?.refreshWindow()
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Throwable) { if (!retired && waiters.isNotEmpty()) finish(false, false, error) }
                finally { if (startup === coroutineContext[Job]) startup = null }
            }
        }
        return AutoCloseable {
            waiters.remove(token); failures.remove(token)
            if (waiters.isEmpty()) { startup?.cancel(); job?.close(); job = null }
            else job?.refreshWindow()
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
            if (waiters.isEmpty()) { job?.close(); job = null } else job?.refreshWindow()
            notify.forEach { it.value.complete(null) }
        }
    }
    fun retire() {
        retired = true; startupScope.cancel(); waiters.clear(); failures.clear(); job?.close(); job = null
    }
    fun reject(error: Throwable) { visualPassed = false; finish(false, false, error) }

    @Suppress("SetJavaScriptEnabled")
    private class Check(private val context: Context) : AutoCloseable {
        private val handler = Handler(Looper.getMainLooper())
        private val lease = checkNotNull(BusinessJobs.gate.retain()) { "官方资源检查代际已退役" }
        private val browser = try {
            CheckWebView(context).also { value ->
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
        private var lastDiagnostic = "尚无脚本诊断"
        private val deadline = RendererValidationBudget(12000)
        private var refreshing = false
        private val parents = linkedMapOf<ViewGroup, ParentWatch>()
        private val stopped = java.util.WeakHashMap<Activity, Boolean>()
        private var lifecycleApplication: Application? = null
        private val lifecycle = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityStarted(activity: Activity) { stopped[activity] = false; refreshWindow() }
            override fun onActivityResumed(activity: Activity) { stopped[activity] = false; refreshWindow() }
            override fun onActivityPaused(activity: Activity) { refreshWindow() }
            override fun onActivityStopped(activity: Activity) { stopped[activity] = true; refreshWindow() }
            override fun onActivityDestroyed(activity: Activity) { stopped[activity] = true; refreshWindow() }
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
        }
        private val timeout = Runnable { if (!closed) updateDeadline() }
        init {
            browser.stateChanged = { refreshWindow() }
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
                    if (!closed) finish(false, false, IllegalStateException("官方渲染检查进程退出：崩溃=${detail.didCrash()}，脚本就绪=$jsReady"))
                    return true
                }
            }
            browser.webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView?, title: String?) {
                    if (closed) return
                    if (title == "wallpaper:error") finish(false, false, IllegalStateException("官方渲染器固定项目运行失败"))
                    if (title == "wallpaper:ready") { jsReady = true; refreshWindow(); if (!closed) finish(true, false) }
                }
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    // 只记录固定检查页面的阶段，不保存项目内容、路径或任意脚本文本。
                    if (message.message().startsWith("壁纸引擎诊断")) lastDiagnostic = "引擎已输出诊断"
                    if (!closed && message.messageLevel() == ConsoleMessage.MessageLevel.ERROR &&
                        (message.message().startsWith("Uncaught") || message.message().contains("SyntaxError") || message.message().startsWith("壁纸加载失败")))
                        finish(false, false, IllegalStateException("官方渲染器固定项目脚本失败"))
                    return true
                }
            }
        }
        fun start() {
            if (closed || started) return
            started = true
            browser.loadUrl("https://practice.invalid/index.html")
            refreshWindow()
        }
        private fun displayable(parent: ViewGroup): Boolean {
            val activity = waiters.values.firstOrNull { it.visual && it.parent === parent }?.activity ?: return false
            return parent.isAttachedToWindow && parent.windowVisibility == View.VISIBLE && parent.isShown &&
                !activity.isFinishing && !activity.isDestroyed && stopped[activity] != true
        }
        private fun displayableBrowser(): Boolean = (browser.parent as? ViewGroup)?.let {
            displayable(it) && browser.isAttachedToWindow && browser.windowVisibility == View.VISIBLE && browser.isShown
        } == true

        // 只观察当前等待者的实际 Decor；单个临时 lifecycle observer 在 close/无visual等待时注销。
        fun refreshWindow() {
            if (closed || refreshing) return
            refreshing = true
            try {
                val requested = waiters.values.filter { it.visual }.mapNotNull { it.parent }.toSet()
                parents.keys.filter { it !in requested }.toList().forEach { parents.remove(it)?.close() }
                requested.forEach { parent ->
                    if (parent !in parents) parents[parent] = ParentWatch(parent) { refreshWindow() }
                    if (lifecycleApplication == null) waiters.values.firstOrNull { it.parent === parent }?.activity?.application?.let {
                        lifecycleApplication = it; it.registerActivityLifecycleCallbacks(lifecycle)
                    }
                }
                if (requested.isEmpty()) {
                    lifecycleApplication?.unregisterActivityLifecycleCallbacks(lifecycle); lifecycleApplication = null
                    stopped.clear()
                    if (browser.parent != null) {
                        (browser.parent as? ViewGroup)?.removeView(browser)
                        visualEpoch++; visualRequested = false
                        browser.onResume() // 仅此实例的JS-only检查；不调用影响全进程WebView的resumeTimers。
                    }
                } else {
                    val current = browser.parent as? ViewGroup
                    val parent = requested.firstOrNull { displayable(it) } ?: current?.takeIf { it in requested } ?: requested.first()
                    if (browser.parent !== parent) {
                        (browser.parent as? ViewGroup)?.removeView(browser); parent.addView(browser)
                        visualEpoch++; visualRequested = false
                    }
                    if (!displayableBrowser() && visualRequested) { visualEpoch++; visualRequested = false }
                }
                updateDeadline()
                requestVisual()
            } finally { refreshing = false }
        }
        private fun updateDeadline() {
            handler.removeCallbacks(timeout)
            if (closed || !started) return
            val visual = waiters.values.any { it.visual }
            val remaining = deadline.update(SystemClock.uptimeMillis(), !visual || displayableBrowser()) ?: return
            if (remaining == 0L) finish(false, false, IllegalStateException(
                "官方渲染器有效验证时间超时：脚本就绪=$jsReady，首帧请求=$visualRequested，" +
                    "页面进度=${browser.progress}，可绘制=${displayableBrowser()}，$lastDiagnostic"))
            else handler.postDelayed(timeout, remaining)
        }
        private fun requestVisual() {
            if (closed || !jsReady || !displayableBrowser() || visualRequested) return
            visualRequested = true
            val epoch = visualEpoch
            browser.postVisualStateCallback(1, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    if (closed || visualEpoch != epoch) return
                    updateDeadline()
                    if (closed) return
                    if (displayableBrowser()) finish(true, true)
                    else { visualEpoch++; visualRequested = false; refreshWindow() }
                }
            })
        }
        override fun close() {
            if (closed) return; closed = true
            deadline.close(); browser.stateChanged = null
            handler.removeCallbacksAndMessages(null)
            try {
                val application = lifecycleApplication; lifecycleApplication = null
                runCatching { application?.unregisterActivityLifecycleCallbacks(lifecycle) }
                parents.values.forEach { watch -> runCatching { watch.close() } }; parents.clear(); stopped.clear()
                runCatching { browser.stopLoading() }
                runCatching { (browser.parent as? ViewGroup)?.removeView(browser) }
                try { browser.webChromeClient = null; browser.webViewClient = WebViewClient() } finally { browser.destroy() }
            } finally { lease.close() }
        }
    }

    private class CheckWebView(context: Context) : WebView(context) {
        var stateChanged: (() -> Unit)? = null
        override fun onAttachedToWindow() { super.onAttachedToWindow(); stateChanged?.invoke() }
        override fun onDetachedFromWindow() { super.onDetachedFromWindow(); stateChanged?.invoke() }
        override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); stateChanged?.invoke() }
        override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); stateChanged?.invoke() }
    }

    private class ParentWatch(private val parent: ViewGroup, changed: () -> Unit) : AutoCloseable {
        private val tree = parent.viewTreeObserver
        private val layout = ViewTreeObserver.OnGlobalLayoutListener { changed() }
        private val focus = ViewTreeObserver.OnWindowFocusChangeListener { changed() }
        private val attach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { changed() }
            override fun onViewDetachedFromWindow(view: View) { changed() }
        }
        init { parent.addOnAttachStateChangeListener(attach); tree.addOnGlobalLayoutListener(layout); tree.addOnWindowFocusChangeListener(focus) }
        override fun close() {
            parent.removeOnAttachStateChangeListener(attach)
            listOf(tree, parent.viewTreeObserver).distinct().filter { it.isAlive }.forEach {
                it.removeOnGlobalLayoutListener(layout); it.removeOnWindowFocusChangeListener(focus)
            }
        }
    }
}

// RENDERER_BUDGET_BEGIN：独立测试直接提取本段实际预算状态，不依赖Android或伪造ready。
internal class RendererValidationBudget(private val limitMs: Long) {
    private var spent = 0L
    private var activeAt: Long? = null
    private var closed = false
    init { require(limitMs > 0) }
    fun update(now: Long, eligible: Boolean): Long? {
        if (closed) return null
        require(now >= 0)
        activeAt?.let { at -> if (now >= at) spent += minOf(limitMs - spent, now - at) }
        activeAt = if (eligible) maxOf(now, activeAt ?: now) else null
        return if (eligible) limitMs - spent else null
    }
    fun close() { closed = true; activeAt = null }
}
// RENDERER_BUDGET_END
