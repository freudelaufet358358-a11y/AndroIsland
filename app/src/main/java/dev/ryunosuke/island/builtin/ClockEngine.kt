package dev.ryunosuke.island.builtin

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.service.quicksettings.TileService
import android.util.Log
import dev.ryunosuke.island.island.ActionRole
import dev.ryunosuke.island.island.ActionTarget
import dev.ryunosuke.island.island.Chrono
import dev.ryunosuke.island.island.ClockCommand
import dev.ryunosuke.island.island.IslandAction
import dev.ryunosuke.island.island.IslandActivity
import dev.ryunosuke.island.island.RingingActivity
import dev.ryunosuke.island.island.StopwatchActivity
import dev.ryunosuke.island.island.TimerActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update

/**
 * アプリ内蔵のストップウォッチとタイマー。状態は SharedPreferences に同期で書く
 * （タイマー終了の受信でプロセスが起き直したときに、すぐ読めるように）。
 */
class ClockEngine(private val context: Context) {

    private val prefs = context.getSharedPreferences("clock", Context.MODE_PRIVATE)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val ringer = TimerRinger(context)

    private val _stopwatch = MutableStateFlow(StopwatchState())
    private val _timer = MutableStateFlow(TimerState())
    val stopwatch: StateFlow<StopwatchState> = _stopwatch
    val timer: StateFlow<TimerState> = _timer

    /** 最後にかけたタイマーの長さ（「繰り返す」用） */
    var lastTimerMs: Long
        get() = prefs.getLong("last_timer_ms", 5 * 60_000L)
        private set(v) = prefs.edit().putLong("last_timer_ms", v).apply()

    init {
        load()
    }

    val activities: Flow<List<IslandActivity>> = combine(_stopwatch, _timer) { sw, t -> toActivities(sw, t) }

    fun handle(cmd: ClockCommand) {
        val now = SystemClock.elapsedRealtime()
        when (cmd) {
            ClockCommand.StopwatchToggle -> setStopwatch(_stopwatch.value.toggle(now))
            ClockCommand.StopwatchLap -> setStopwatch(_stopwatch.value.lap(now))
            ClockCommand.StopwatchReset -> setStopwatch(_stopwatch.value.reset())
            ClockCommand.TimerToggle -> setTimer(_timer.value.toggle(now))
            ClockCommand.TimerCancel -> setTimer(TimerState())
            ClockCommand.TimerAddMinute -> setTimer(_timer.value.addMinute(now))
            ClockCommand.TimerStopRinging -> setTimer(TimerState())
            ClockCommand.TimerRepeat -> startTimer(lastTimerMs)
        }
    }

    fun startTimer(ms: Long) {
        if (ms <= 0) return
        lastTimerMs = ms
        setTimer(TimerState.start(ms, SystemClock.elapsedRealtime()))
    }

    /** AlarmManager から呼ばれる。遅れて届いても、まだ時間が残っていれば鳴らさない */
    fun onAlarm() {
        val t = _timer.value
        val now = SystemClock.elapsedRealtime()
        if (!t.running) return
        if (t.remaining(now) > 500) {
            schedule(t)
            return
        }
        setTimer(t.finish())
    }

    private fun setStopwatch(s: StopwatchState) {
        _stopwatch.value = s
        prefs.edit()
            .putBoolean("sw_running", s.running)
            .putLong("sw_start", s.startElapsed)
            .putLong("sw_acc", s.accumulatedMs)
            .putString("sw_laps", s.laps.joinToString(","))
            .apply()
        refreshTile(StopwatchTileService::class.java)
    }

    private fun setTimer(t: TimerState) {
        val wasRinging = _timer.value.ringing
        _timer.value = t
        prefs.edit()
            .putLong("t_total", t.totalMs)
            .putBoolean("t_running", t.running)
            .putLong("t_end", t.endElapsed)
            .putLong("t_remaining", t.remainingMs)
            .putBoolean("t_ringing", t.ringing)
            .apply()
        schedule(t)
        if (t.ringing && !wasRinging) ringer.start()
        if (!t.ringing && wasRinging) ringer.stop()
        refreshTile(TimerTileService::class.java)
    }

    private fun alarmIntent() = PendingIntent.getBroadcast(
        context, 0, Intent(context, TimerAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun schedule(t: TimerState) {
        val pi = alarmIntent()
        if (!t.running) {
            alarms.cancel(pi)
            return
        }
        try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, t.endElapsed, pi)
        } catch (e: SecurityException) {
            Log.w(TAG, "正確なアラームを使えない。多少遅れる", e)
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, t.endElapsed, pi)
        }
    }

    private fun refreshTile(cls: Class<*>) {
        runCatching { TileService.requestListeningState(context, ComponentName(context, cls)) }
    }

    /** 再起動をまたいだら elapsedRealtime の基準が変わるので捨てる */
    private fun load() {
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (prefs.getInt("boot", -2) != boot) {
            prefs.edit().clear().putInt("boot", boot).apply()
            return
        }
        _stopwatch.value = StopwatchState(
            running = prefs.getBoolean("sw_running", false),
            startElapsed = prefs.getLong("sw_start", 0),
            accumulatedMs = prefs.getLong("sw_acc", 0),
            laps = prefs.getString("sw_laps", "").orEmpty().split(",").mapNotNull { it.toLongOrNull() },
        )
        val t = TimerState(
            totalMs = prefs.getLong("t_total", 0),
            running = prefs.getBoolean("t_running", false),
            endElapsed = prefs.getLong("t_end", 0),
            remainingMs = prefs.getLong("t_remaining", 0),
            ringing = prefs.getBoolean("t_ringing", false),
        )
        _timer.value = t
        // 鳴っている最中にプロセスが落ちていたら、鳴らし直さずに「終了」表示だけ戻す
        if (t.running && t.remaining(SystemClock.elapsedRealtime()) == 0L) _timer.update { it.finish() }
    }

    private fun toActivities(sw: StopwatchState, t: TimerState): List<IslandActivity> {
        val nowE = SystemClock.elapsedRealtime()
        val toWall = System.currentTimeMillis() - nowE
        val own = context.packageName
        val open = ActionTarget.Launch(own)
        // 投稿時刻は「しまった」状態の判定に使われるので、状態が同じなら同じ値にする
        val timerStamp = t.endElapsed
        fun act(role: ActionRole, label: String, cmd: ClockCommand) = IslandAction(role, label, ActionTarget.Builtin(cmd))
        val list = mutableListOf<IslandActivity>()
        when {
            t.ringing -> list += RingingActivity(
                key = KEY_TIMER,
                packageName = own,
                title = "タイマー",
                detail = "終了",
                isTimer = true,
                actions = listOf(
                    act(ActionRole.Stop, "停止", ClockCommand.TimerStopRinging),
                    act(ActionRole.AddTime, "+1:00", ClockCommand.TimerAddMinute),
                ),
                postTime = timerStamp,
                open = open,
            )
            t.active -> list += TimerActivity(
                key = KEY_TIMER,
                packageName = own,
                label = null,
                chrono = Chrono(t.running, t.endElapsed + toWall, t.remainingMs, countDown = true),
                totalMs = t.totalMs,
                actions = listOf(
                    act(ActionRole.Cancel, "キャンセル", ClockCommand.TimerCancel),
                    if (t.running) act(ActionRole.Pause, "一時停止", ClockCommand.TimerToggle)
                    else act(ActionRole.Play, "再開", ClockCommand.TimerToggle),
                ),
                postTime = timerStamp,
                open = open,
            )
        }
        if (sw.active) {
            list += StopwatchActivity(
                key = KEY_STOPWATCH,
                packageName = own,
                chrono = Chrono(sw.running, sw.startElapsed + toWall - sw.accumulatedMs, sw.accumulatedMs, countDown = false),
                lapCount = sw.laps.size,
                actions = if (sw.running) {
                    listOf(
                        act(ActionRole.Lap, "ラップ", ClockCommand.StopwatchLap),
                        act(ActionRole.Pause, "一時停止", ClockCommand.StopwatchToggle),
                    )
                } else {
                    listOf(
                        act(ActionRole.Reset, "リセット", ClockCommand.StopwatchReset),
                        act(ActionRole.Play, "再開", ClockCommand.StopwatchToggle),
                    )
                },
                postTime = sw.startElapsed,
                open = open,
            )
        }
        return list
    }

    companion object {
        private const val TAG = "IslandClock"
        const val KEY_TIMER = "builtin:timer"
        const val KEY_STOPWATCH = "builtin:stopwatch"
    }
}
