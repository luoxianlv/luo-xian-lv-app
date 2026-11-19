package app.luoxianlv

import android.app.Activity
import android.app.Instrumentation
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.core.content.edit
import app.luoxianlv.platform.PlatformClient
import app.luoxianlv.shared.Kv
import java.util.concurrent.atomic.AtomicReference

/** 验证 OAuth 授权界面的打开与失败回滚，无需连接授权服务。 */
class OAuthBrowserInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val failure = AtomicReference<Throwable?>()
        runOnMainSync { failure.set(runCatching(::checkBrowserLaunch).exceptionOrNull()) }
        val error = failure.get()
        finish(
            if (error == null) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply {
                putString(
                    "stream",
                    error?.stackTraceToString() ?: "OAuth browser launch checks passed",
                )
            },
        )
    }

    private fun checkBrowserLaunch() {
        val client = PlatformClient(targetContext)
        val prefs = Kv.of(targetContext, "oauth")
        val requestKeys = listOf("state", "verifier", "redirect_uri")
        check(requestKeys.none(prefs::contains)) { "Run this test without a pending OAuth request" }
        val browser =
            object : Activity() {
                var canLaunch = false
                var attempts = 0

                override fun startActivity(intent: Intent) {
                    attempts++
                    check(intent.action == Intent.ACTION_VIEW)
                    check(intent.data?.host == "shushu.fan")
                    check(
                        intent.data?.getQueryParameter("state") ==
                            "app_${prefs.getString("state", null)}"
                    )
                    check(!prefs.getString("verifier", null).isNullOrEmpty())
                    if (!canLaunch) throw ActivityNotFoundException()
                }
            }
        try {
            check(!client.startShushuLogin(browser))
            check(browser.attempts == 1)
            check(requestKeys.none(prefs::contains)) {
                "Unlaunched OAuth request left a pending callback"
            }
            browser.canLaunch = true
            check(client.startShushuLogin(browser))
            check(browser.attempts == 2)
            check(requestKeys.all(prefs::contains))
        } finally {
            prefs.edit { requestKeys.forEach { remove(it) } }
        }
    }
}
