package app.luoxianlv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.luoxianlv.BuildConfig
import app.luoxianlv.core.Analytics
import app.luoxianlv.ui.components.IconBlue
import app.luoxianlv.ui.components.IconGreen
import app.luoxianlv.ui.components.IconOrange
import app.luoxianlv.ui.components.IconTeal
import app.luoxianlv.ui.components.NavBarClearance
import app.luoxianlv.ui.components.PreferenceGroupCaption
import app.luoxianlv.ui.components.PreferenceItem
import app.luoxianlv.ui.components.SettingsCard
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 统计诊断页（仅 debug 包）：排查友盟「集成测试 / 实时日志」扫码唤起后无数据的问题。
 * 展示 SDK 是否初始化、扫码唤起是否到达 MainActivity、以及埋点流水。
 */
@Composable
fun AnalyticsDebugScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    fun fmt(ts: Long) = timeFmt.format(java.util.Date(ts))

    Column(modifier = Modifier.fillMaxSize()) {
        // 子页面统一标题：返回箭头 + 粗体大标题
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp, top = 4.dp),
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Text(
                "统计诊断",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = NavBarClearance),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column {
                    PreferenceGroupCaption("SDK 状态")
                    Spacer(modifier = Modifier.height(6.dp))
                    SettingsCard {
                        val initAt = Analytics.initAt
                        PreferenceItem(
                            title = "初始化状态",
                            summary =
                                if (initAt == null) {
                                    "未初始化（先同意免责协议）"
                                } else {
                                    "已初始化 · ${fmt(initAt)}"
                                },
                            icon = Icons.Filled.Bolt,
                            iconTint = if (initAt == null) IconOrange else IconGreen,
                        )
                        PreferenceItem(
                            title = "AppKey / 渠道",
                            summary = "${Analytics.APP_KEY} · ${Analytics.CHANNEL}",
                            icon = Icons.Filled.Key,
                            iconTint = IconBlue,
                        )
                        PreferenceItem(
                            title = "版本",
                            summary = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})${if (BuildConfig.DEBUG) " · debug" else ""}",
                            icon = Icons.Filled.DeveloperBoard,
                            iconTint = IconTeal,
                        )
                        Button(
                            onClick = { Analytics.logEvent(context, "debug_test_event") },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Icon(Icons.Filled.Send, null)
                            Text("  发送测试事件 debug_test_event")
                        }
                    }
                }
            }
            item {
                Column {
                    PreferenceGroupCaption("唤起记录（扫码后看这里）")
                    Spacer(modifier = Modifier.height(6.dp))
                    SettingsCard {
                        val wakes = Analytics.diagEntries.filter { it.kind == "唤起" }.asReversed()
                        if (wakes.isEmpty()) {
                            PreferenceItem(
                                title = "暂无唤起",
                                summary = "重新扫集成测试二维码后回到本页查看",
                                icon = Icons.Filled.QrCodeScanner,
                                iconTint = IconOrange,
                            )
                        } else {
                            wakes.take(5).forEach { e ->
                                PreferenceItem(
                                    title = fmt(e.time),
                                    summary = e.detail,
                                    icon = Icons.Filled.QrCodeScanner,
                                    iconTint = IconGreen,
                                )
                            }
                        }
                    }
                }
            }
            item {
                Column {
                    PreferenceGroupCaption("埋点流水")
                    Spacer(modifier = Modifier.height(6.dp))
                    SettingsCard {
                        val entries = Analytics.diagEntries.asReversed()
                        if (entries.isEmpty()) {
                            PreferenceItem(
                                title = "暂无记录",
                                summary = "切换几个页面或点上面的测试事件",
                            )
                        } else {
                            entries.forEach { e ->
                                PreferenceItem(
                                    title = fmt(e.time),
                                    summary = "[${e.kind}] ${e.detail}",
                                )
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "判断方法：唤起记录里出现 um.6ab2… 的完整链接，说明二维码已到达 App；" +
                        "若实时日志仍无数据，则是 SDK 侧标记或上报问题。",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Default,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}
