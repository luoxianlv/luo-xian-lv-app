package app.luoxianlv.shared

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/** 导入后立即复制内容；使用 GET_CONTENT，让普通文件管理器也能提供文件。 */
class ImportFilePicker(private val title: String) : ActivityResultContract<Array<String>, Uri?>() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        Intent.createChooser(
            Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_MIME_TYPES, input)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
            },
            title,
        )

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        if (resultCode != Activity.RESULT_OK) return null
        return intent?.data ?: intent?.clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.uri
    }
}
