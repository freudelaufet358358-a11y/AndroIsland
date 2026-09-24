package dev.ryunosuke.island.builtin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ryunosuke.island.IslandApp
import dev.ryunosuke.island.island.TimeFormat
import dev.ryunosuke.island.ui.app.AppTheme
import dev.ryunosuke.island.ui.island.IslandText

/** QS タイルから開く、タイマーの長さを選ぶ小さな画面 */
class TimerPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                TimerPicker(
                    initialMs = IslandApp.graph.clock.lastTimerMs,
                    onStart = {
                        IslandApp.graph.clock.startTimer(it)
                        finish()
                    },
                    onCancel = { finish() },
                )
            }
        }
    }
}

@Composable
fun TimerPicker(initialMs: Long, onStart: (Long) -> Unit, onCancel: (() -> Unit)?) {
    var minutes by remember { mutableIntStateOf((initialMs / 60_000).toInt().coerceIn(0, 599)) }
    var seconds by remember { mutableIntStateOf(((initialMs / 1000) % 60).toInt()) }
    val total = (minutes * 60L + seconds) * 1000
    Column(
        Modifier
            .background(Color(0xFF1C1C1E), RoundedCornerShape(28.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("タイマー", color = Color(0xFFAEAEB2), fontSize = 15.sp)
        Text(
            TimeFormat.clock(total),
            color = Color.White,
            fontSize = 56.sp,
            fontWeight = FontWeight.Light,
            style = IslandText.tabular,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stepper("分", minutes, { minutes = (minutes + it).coerceIn(0, 599) })
            Stepper("秒", seconds, { seconds = (seconds + it).mod(60) }, step = 5)
        }
        Row(
            Modifier.padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (m in listOf(1, 3, 5, 10, 15, 30)) {
                FilledTonalButton(
                    onClick = { minutes = m; seconds = 0 },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.width(48.dp),
                ) { Text("$m") }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 20.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            if (onCancel != null) TextButton(onClick = onCancel) { Text("キャンセル") }
            Button(onClick = { onStart(total) }, enabled = total > 0) { Text("開始") }
        }
    }
}

@Composable
private fun Stepper(unit: String, value: Int, onDelta: (Int) -> Unit, step: Int = 1) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onDelta(-step) }) { Text("−", fontSize = 22.sp) }
        Text("$value $unit", color = Color.White, fontSize = 17.sp, style = IslandText.tabular)
        TextButton(onClick = { onDelta(step) }) { Text("+", fontSize = 22.sp) }
    }
}
