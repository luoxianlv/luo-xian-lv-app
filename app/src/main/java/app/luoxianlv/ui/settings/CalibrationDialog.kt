package app.luoxianlv.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.luoxianlv.core.score.PlayMode
import app.luoxianlv.data.KeyLayout

/** 音高校准对话框：8 个音符 X + 音符 Y + 4 个调式按钮 X/Y，百分比输入（与旧版一致）。 */
@Composable
fun CalibrationDialog(
    initial: KeyLayout,
    onDismiss: () -> Unit,
    onSave: (KeyLayout) -> Boolean,
) {
    val noteX = remember { initial.noteX.map { mutableStateOf("${it * 100}") } }
    var noteY by remember { mutableStateOf("${initial.noteY * 100}") }
    val modes =
        remember {
            initial.modes.mapValues { (_, point) ->
                mutableStateOf("${point[0] * 100}") to mutableStateOf("${point[1] * 100}")
            }
        }
    var error by remember { mutableStateOf<String?>(null) }

    fun parsePercent(value: String): Float {
        val parsed = value.trim().toFloatOrNull()?.div(100) ?: throw IllegalArgumentException("请输入数字")
        require(parsed in 0f..1f) { "比例范围为 0–100%" }
        return parsed
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("按键位置") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                noteX.forEachIndexed { index, state ->
                    OutlinedTextField(
                        value = state.value,
                        onValueChange = { state.value = it },
                        label = { Text("音符 ${if (index == 7) "i" else index + 1} X (%)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    )
                }
                OutlinedTextField(
                    value = noteY,
                    onValueChange = { noteY = it },
                    label = { Text("音符 Y (%)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                )
                modes.forEach { (mode, states) ->
                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                        listOf("X", "Y").forEachIndexed { axis, label ->
                            val state = if (axis == 0) states.first else states.second
                            OutlinedTextField(
                                value = state.value,
                                onValueChange = { state.value = it },
                                label = { Text("${mode.label} $label (%)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .padding(end = if (axis == 0) 6.dp else 0.dp),
                            )
                        }
                    }
                }
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val layout =
                        runCatching {
                            KeyLayout(
                                noteX.map { parsePercent(it.value) }.toFloatArray(),
                                parsePercent(noteY),
                                modes.mapValues { (_, states) ->
                                    floatArrayOf(parsePercent(states.first.value), parsePercent(states.second.value))
                                },
                            )
                        }
                    layout
                        .onSuccess { if (onSave(it)) error = null else error = "保存失败" }
                        .onFailure { error = it.message ?: "输入无效" }
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
