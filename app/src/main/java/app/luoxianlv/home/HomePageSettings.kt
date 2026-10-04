package app.luoxianlv.home

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.luoxianlv.business.ui.LocalPageVisible
import app.luoxianlv.business.ui.PageAlertDialog as AlertDialog
import app.luoxianlv.business.ui.PageReplacementGuard
import app.luoxianlv.shared.SmallSwitch
import app.luoxianlv.ui.theme.OnBackdropContent

/** 「首页设置」：启动按钮左侧的 Tune 图标，下拉两项—— 「编辑一言」弹窗输入，改动即时写盘生效（无需保存按钮）； 「展开侧边栏」即点即开，控制「我的」页左侧竖栏，默认开。 */
@Composable
internal fun HomePageSettings(
    quote: String,
    sideRailEnabled: Boolean,
    onQuoteChange: (String) -> Unit,
    onSideRailChange: (Boolean) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val visible = LocalPageVisible.current
    PageReplacementGuard { !menu }
    Box {
        IconButton(onClick = { menu = true }) {
            // 同上：图标直接压在内容卡渐变上，不能吃默认的黑。
            Icon(Icons.Filled.Tune, contentDescription = "首页设置", tint = OnBackdropContent)
        }
        DropdownMenu(expanded = menu && visible, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("编辑一言") },
                leadingIcon = { Icon(Icons.Filled.EditNote, contentDescription = null) },
                onClick = {
                    menu = false
                    editing = true
                },
            )
            DropdownMenuItem(
                text = { Text("展开侧边栏") },
                trailingIcon = {
                    SmallSwitch(checked = sideRailEnabled, onCheckedChange = onSideRailChange)
                },
                onClick = { onSideRailChange(!sideRailEnabled) },
            )
        }
    }

    if (editing) {
        HomeQuoteEditor(
            quote = quote,
            onChange = onQuoteChange,
            onClose = { editing = false },
        )
    }
}

/** 一言编辑弹窗：没有保存按钮 —— 每次输入都直接写盘， 弹窗背后的问候语区实时跟着变，「完成」只是关窗。 */
@Composable
private fun HomeQuoteEditor(
    quote: String,
    onChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    var draft by remember { mutableStateOf(quote) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("编辑一言") },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    onChange(it)
                },
                singleLine = true,
                placeholder = { Text("问候语下方的那句话") },
            )
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text("完成") }
        },
    )
}
