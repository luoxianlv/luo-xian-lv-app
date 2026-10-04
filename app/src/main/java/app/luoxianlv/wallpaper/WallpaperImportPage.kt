package app.luoxianlv.wallpaper

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import app.luoxianlv.business.ui.ComposePage
import app.luoxianlv.ui.theme.LuoXianLvTheme

/** 外部文件只在收到真实系统 Intent 时导入；候选预绘制只恢复已完成的结果。 */
class WallpaperImportPage : ComposePage() {
    private lateinit var model: WallpaperImportModel

    override fun prepare(state: Bundle) {
        model = ViewModelProvider(this)[WallpaperImportModel::class.java]
        model.restoreCompleted(state.getBundle("import"))
    }

    override fun newIntent(intent: Intent) {
        @Suppress("DEPRECATION")
        val uri = runCatching {
            when (intent.action) {
                Intent.ACTION_VIEW -> intent.data
                Intent.ACTION_SEND ->
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                        ?: intent.clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.uri
                else -> null
            }
        }
            .getOrNull()
        model.start(uri)
    }

    override fun canReplace() = super.canReplace() && ::model.isInitialized && !model.busy

    override fun savePageState() = Bundle().apply { putBundle("import", model.saveCompleted()) }

    @Composable
    override fun Content() {
        LuoXianLvTheme(applySystemBars = isActive) {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(
                    Modifier.safeDrawingPadding().padding(28.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("导入落弦律壁纸", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(20.dp))
                    if (model.busy) CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(model.message)
                    Spacer(Modifier.height(24.dp))
                    if (model.success)
                        Button(
                            onClick = {
                                host.open(
                                    Intent()
                                        .setClassName(
                                            pageContext.packageName,
                                            "app.luoxianlv.ui.practice.PracticeActivity",
                                        ),
                                    true,
                                )
                            }
                        ) {
                            Text("进入演练场")
                        }
                    TextButton(onClick = { host.closePage() }) {
                        Text(if (model.busy) "取消" else "关闭")
                    }
                }
            }
        }
    }
}
