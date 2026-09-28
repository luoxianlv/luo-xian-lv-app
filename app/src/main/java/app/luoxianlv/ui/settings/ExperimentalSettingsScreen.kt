package app.luoxianlv.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.luoxianlv.data.ExperimentalOptions
import app.luoxianlv.service.MusicAccessibilityService
import app.luoxianlv.ui.components.PreferenceSwitchItem
import app.luoxianlv.ui.components.SettingsCard

@Composable
fun ExperimentalSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var fixed by remember { mutableStateOf(ExperimentalOptions.fixedHarmonicaKeys(context)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.padding(start = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("实验性选项", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            SettingsCard {
                PreferenceSwitchItem(
                    title = "口琴按键位置固定",
                    summary = "使用演练场的按键位置，无需截图识别",
                    checked = fixed,
                    onCheckedChange = {
                        ExperimentalOptions.setFixedHarmonicaKeys(context, it)
                        MusicAccessibilityService.instance?.reloadExperimentalOptions()
                        fixed = it
                    },
                )
            }
            Text(
                "适合无障碍无法截图的手机。其他软件的口琴需与演练场布局一致。首次播放前请选择自然音并关闭半音，播放中不要手动切换音区。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
            )
        }
    }
}
