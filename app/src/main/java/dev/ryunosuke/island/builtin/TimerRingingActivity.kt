package dev.ryunosuke.island.builtin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ryunosuke.island.IslandApp
import dev.ryunosuke.island.island.ClockCommand
import dev.ryunosuke.island.ui.app.AppTheme
import dev.ryunosuke.island.ui.island.IslandColors

/** 画面が消えている間に内蔵タイマーが終わったときの全画面表示 */
class TimerRingingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val clock = IslandApp.graph.clock
        setContent {
            AppTheme {
                val timer by clock.timer.collectAsState()
                LaunchedEffect(timer.ringing) { if (!timer.ringing) finish() }
                Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("タイマー", color = Color(0xFFAEAEB2), fontSize = 20.sp)
                        Text("終了", color = IslandColors.Orange, fontSize = 72.sp, fontWeight = FontWeight.Light)
                        Row(Modifier.padding(top = 48.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                            Button(
                                onClick = { clock.handle(ClockCommand.TimerAddMinute) },
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3A3C), contentColor = Color.White),
                                modifier = Modifier.size(96.dp),
                            ) { Text("+1分", fontSize = 18.sp) }
                            Button(
                                onClick = { clock.handle(ClockCommand.TimerStopRinging) },
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(containerColor = IslandColors.Orange, contentColor = Color.Black),
                                modifier = Modifier.size(96.dp),
                            ) { Text("停止", fontSize = 18.sp) }
                        }
                    }
                }
            }
        }
    }
}
