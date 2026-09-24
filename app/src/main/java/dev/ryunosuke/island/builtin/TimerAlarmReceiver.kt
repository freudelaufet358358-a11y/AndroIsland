package dev.ryunosuke.island.builtin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.ryunosuke.island.IslandApp
import dev.ryunosuke.island.island.ClockCommand

class TimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val clock = IslandApp.graph.clock
        if (intent.action == ACTION_STOP) clock.handle(ClockCommand.TimerStopRinging) else clock.onAlarm()
    }

    companion object {
        const val ACTION_STOP = "dev.ryunosuke.island.TIMER_STOP"
    }
}
