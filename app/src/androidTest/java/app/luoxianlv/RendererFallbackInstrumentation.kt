package app.luoxianlv

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import app.luoxianlv.ui.practice.PracticeActivity
import app.luoxianlv.wallpaper.PracticeBackdrop
import app.luoxianlv.wallpaper.WallpaperProjectStore
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 只在测试设备导入临时离线项目；测试真实 WebView 生命周期与可控厂商异常。 */
class RendererFallbackInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    private fun main(action: () -> Unit) {
        val failure = AtomicReference<Throwable>()
        runOnMainSync {
            try {
                action()
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        failure.get()?.let { throw AssertionError("renderer fallback regression", it) }
    }

    private fun find(view: View): PracticeBackdrop? =
        when (view) {
            is PracticeBackdrop -> view
            is ViewGroup ->
                (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
            else -> null
        }

    private fun browser(backdrop: PracticeBackdrop): WebView? =
        backdrop.javaClass.getDeclaredField("web").apply { isAccessible = true }.get(backdrop)
            as? WebView

    override fun onStart() {
        val result = Bundle()
        var activity: Activity? = null
        var failedBackdrop: PracticeBackdrop? = null
        var success = false
        try {
            val archive = File(targetContext.cacheDir, "renderer-fallback-fixture.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                fun put(name: String, text: String) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                put(
                    "project.json",
                    """{"title":"Renderer regression","type":"web","file":"index.html"}""",
                )
                put(
                    "index.html",
                    """<!doctype html><html><body style="margin:0;background:#145dcc;height:100vh">offline fixture</body></html>""",
                )
            }
            WallpaperProjectStore.import(targetContext, android.net.Uri.fromFile(archive), false)
            activity =
                startActivitySync(
                    Intent(targetContext, PracticeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            val stage = checkNotNull(activity)
            var backdrop: PracticeBackdrop? = null
            val deadline = SystemClock.uptimeMillis() + 30000
            while (true) {
                var ready = false
                main {
                    backdrop = find(stage.window.decorView)
                    ready = backdrop?.renderState == "ready"
                }
                if (ready) break
                check(SystemClock.uptimeMillis() < deadline) { "Offline fixture never rendered" }
                SystemClock.sleep(100)
            }
            main {
                val live = checkNotNull(backdrop)
                val web = checkNotNull(browser(live))
                val chrome = checkNotNull(web.webChromeClient)
                val client = web.webViewClient
                live.suspendRendering()
                live.resumeRendering()
                check(live.renderState == "ready" && browser(live) === web)
                check(
                    client.onRenderProcessGone(
                        web,
                        object : RenderProcessGoneDetail() {
                            override fun didCrash() = true

                            override fun rendererPriorityAtExit() =
                                WebView.RENDERER_PRIORITY_IMPORTANT
                        },
                    )
                )
                check(live.renderState == "error" && live.prepared && browser(live) == null)
                chrome.onReceivedTitle(web, "wallpaper:ready")
                check(live.renderState == "error") { "Late title resurrected disposed renderer" }
                live.close()
                live.close()
                chrome.onReceivedTitle(web, "wallpaper:error")
                check(browser(live) == null)

                val failed = PracticeBackdrop(targetContext, deferRendering = true)
                failedBackdrop = failed
                var stops = 0
                var pauses = 0
                var destroys = 0
                val vendor =
                    object : WebView(targetContext) {
                        override fun stopLoading() {
                            stops++
                            throw IllegalStateException("injected stop failure")
                        }

                        override fun onPause() {
                            pauses++
                            throw IllegalStateException("injected pause failure")
                        }

                        override fun destroy() {
                            destroys++
                            super.destroy()
                        }
                    }
                failed.addView(vendor)
                failed.javaClass
                    .getDeclaredField("web")
                    .apply { isAccessible = true }
                    .set(failed, vendor)
                failed.close()
                failed.close()
                check(
                    stops == 1 &&
                        pauses == 1 &&
                        destroys == 1 &&
                        vendor.parent == null &&
                        browser(failed) == null
                ) {
                    "Vendor failure skipped cleanup or repeated destroy: $stops/$pauses/$destroys"
                }
            }
            result.putString(
                "stream",
                "PASS: real offline WebView ready, suspend/resume, renderer-gone callback fallback, late title ignored, repeated close and injected vendor stop/pause failures still destroy exactly once.\n",
            )
            success = true
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
        } finally {
            main {
                failedBackdrop?.close()
                activity?.finish()
            }
        }
        finish(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
