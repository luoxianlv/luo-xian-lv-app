package app.luoxianlv

import android.app.Instrumentation
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import app.luoxianlv.wallpaper.WallpaperResources
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** 经过真实 WebView 读取分段，验证引擎收到的字节，而非只验证原生响应流。 */
internal fun Instrumentation.checkWallpaperRanges(root: File) {
    lateinit var browser: WebView
    val loaded = CountDownLatch(1)
    runOnMainSync {
        browser = WebView(targetContext)
        browser.settings.javaScriptEnabled = true
        val resources = WallpaperResources(targetContext, root)
        browser.webViewClient =
            object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                    resources.response(request)

                override fun onPageFinished(view: WebView, url: String) {
                    loaded.countDown()
                }
            }
        browser.loadDataWithBaseURL(
            "https://practice.invalid/",
            "<html></html>",
            "text/html",
            "UTF-8",
            null,
        )
    }
    try {
        check(loaded.await(10, TimeUnit.SECONDS))
        val file = root.listFiles()!!.single { it.extension == "mp4" }
        val size = file.length()
        val ranges = listOf(0L to 4095L, size / 2 to size / 2 + 4095, size - 64 to size - 1)
        for ((start, end) in ranges) {
            val expected = ByteArray((end - start + 1).toInt())
            java.io.RandomAccessFile(file, "r").use {
                it.seek(start)
                it.readFully(expected)
            }
            val digest =
                MessageDigest.getInstance("SHA-256").digest(expected).joinToString("") {
                    "%02x".format(it)
                }
            val url =
                JSONObject.quote(
                    "https://practice.invalid/project/" + android.net.Uri.encode(file.name)
                )
            runOnMainSync {
                browser.evaluateJavascript(
                    """
                    window.rangeResult=null;
                    (async()=>{try {
                      const r=await fetch($url,{headers:{Range:'bytes=$start-$end'}});
                      const data=await r.arrayBuffer();
                      if(data.byteLength>65536) throw Error('分段长度错误：'+data.byteLength);
                      window.rangeResult={size:data.byteLength,status:r.status,
                        bytes:btoa(String.fromCharCode(...new Uint8Array(data)))};
                    } catch(e) {window.rangeResult={error:String(e)}}})();
                """
                        .trimIndent(),
                    null,
                )
            }
            var result = "null"
            val deadline = android.os.SystemClock.uptimeMillis() + 10000
            while (result == "null" && android.os.SystemClock.uptimeMillis() < deadline) {
                val done = CountDownLatch(1)
                runOnMainSync {
                    browser.evaluateJavascript("window.rangeResult") {
                        result = it
                        done.countDown()
                    }
                }
                check(done.await(5, TimeUnit.SECONDS))
                if (result == "null") Thread.sleep(50)
            }
            val value = JSONObject(result)
            val actual =
                android.util.Base64.decode(value.optString("bytes"), android.util.Base64.DEFAULT)
            check(actual.contentEquals(expected)) {
                "WebView 分段字节错位：$start-$end，返回 ${actual.size} 字节，错误=${value.optString("error")}，预期 SHA-256=$digest"
            }
        }
        android.util.Log.i("壁纸回归", "WebView 首段、中段、尾段逐字节摘要通过")
    } finally {
        runOnMainSync { browser.destroy() }
    }
}
