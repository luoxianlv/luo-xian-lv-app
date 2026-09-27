package app.luoxianlv.ui.practice

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.luoxianlv.ui.theme.LuoXianLvTheme
import app.luoxianlv.ui.wallpaper.WallpaperImportModel

/** System file associations hand over granted content URIs; no storage permission is requested. */
class WallpaperImportActivity : AppCompatActivity() {
    private val model: WallpaperImportModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        setContent {
            LuoXianLvTheme {
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
                                    startActivity(
                                        Intent(
                                            this@WallpaperImportActivity,
                                            PracticeActivity::class.java,
                                        )
                                    )
                                    finish()
                                }
                            ) {
                                Text("进入演练场")
                            }
                        TextButton(onClick = { finish() }) { Text(if (model.busy) "取消" else "关闭") }
                    }
                }
            }
        }
    }
}
