package app.luoxianlv.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri

internal fun openAuthor(context: Context): Boolean {
    val app = Intent(Intent.ACTION_VIEW, Uri.parse("bilibili://space/498496565"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(app) }.isSuccess) return true
    return runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://space.bilibili.com/498496565"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}
