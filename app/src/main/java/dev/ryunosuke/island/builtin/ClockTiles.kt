package dev.ryunosuke.island.builtin

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.ryunosuke.island.IslandApp
import dev.ryunosuke.island.island.ClockCommand
import dev.ryunosuke.island.island.TimeFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** QS タイル共通: 表示中だけ状態を購読してタイルに反映する */
abstract class ClockTileBase : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    protected val clock get() = IslandApp.graph.clock

    abstract fun render(tile: Tile)
    abstract fun observe(scope: CoroutineScope): Job

    override fun onStartListening() {
        job?.cancel()
        job = observe(scope)
    }

    override fun onStopListening() {
        job?.cancel()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    protected fun refresh() {
        val t = qsTile ?: return
        render(t)
        t.updateTile()
    }
}

class StopwatchTileService : ClockTileBase() {
    override fun observe(scope: CoroutineScope) = scope.launch { clock.stopwatch.collect { refresh() } }

    override fun render(tile: Tile) {
        val s = clock.stopwatch.value
        tile.state = if (s.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = when {
            s.running -> "計測中"
            s.active -> "一時停止 " + TimeFormat.stopwatch(s.elapsed(SystemClock.elapsedRealtime()))
            else -> "タップで開始"
        }
    }

    override fun onClick() {
        clock.handle(ClockCommand.StopwatchToggle)
        refresh()
    }
}

class TimerTileService : ClockTileBase() {
    override fun observe(scope: CoroutineScope) = scope.launch { clock.timer.collect { refresh() } }

    override fun render(tile: Tile) {
        val t = clock.timer.value
        tile.state = if (t.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = when {
            t.ringing -> "終了 — タップで停止"
            t.running -> "残り " + TimeFormat.countdown(t.remaining(SystemClock.elapsedRealtime()))
            t.active -> "一時停止中"
            else -> "タップで設定"
        }
    }

    override fun onClick() {
        val t = clock.timer.value
        when {
            t.ringing -> clock.handle(ClockCommand.TimerStopRinging)
            t.active -> clock.handle(ClockCommand.TimerToggle)
            else -> {
                val pi = PendingIntent.getActivity(
                    this, 0,
                    Intent(this, TimerPickerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                startActivityAndCollapse(pi)
            }
        }
        refresh()
    }
}
