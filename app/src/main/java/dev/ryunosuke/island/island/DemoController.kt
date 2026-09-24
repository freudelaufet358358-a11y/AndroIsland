package dev.ryunosuke.island.island

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import dev.ryunosuke.island.source.MediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * 設定画面の「デモ」。本物の出来事を待たずに見た目と操作を確かめるための偽の活動。
 * 押したボタンにもそれらしく反応する（応答すると通話中になる、など）。
 */
class DemoController(@Suppress("unused") private val scope: CoroutineScope) {

    enum class Type { Media, IncomingCall, OngoingCall, Timer, Stopwatch, Alarm, Navigation, Download, Recording }

    private val items = MutableStateFlow<Map<String, IslandActivity>>(emptyMap())
    val activities: Flow<List<IslandActivity>> = items.map { it.values.reversed() }

    private var track = 0
    private val tracks = listOf(
        Triple("サンプル曲 ひとつめ", "デモ アーティスト", intArrayOf(0xFFFF2D55.toInt(), 0xFF5856D6.toInt())),
        Triple("Morning Island", "Demo Band", intArrayOf(0xFF30D158.toInt(), 0xFF0A84FF.toInt())),
        Triple("夕暮れのテーマ", "サンプル楽団", intArrayOf(0xFFFF9F0A.toInt(), 0xFFFF375F.toInt())),
    )

    fun show(type: Type) {
        val now = System.currentTimeMillis()
        val a: IslandActivity = when (type) {
            Type.Media -> mediaFor(track, playing = true, positionMs = 42_000)
            Type.IncomingCall -> CallActivity(
                key = KEY_CALL, packageName = PKG, incoming = true, name = "山田 太郎", detail = "携帯電話",
                avatar = null, chrono = null, video = false,
                actions = listOf(
                    IslandAction(ActionRole.Decline, "拒否", ActionTarget.Demo("call:decline")),
                    IslandAction(ActionRole.Answer, "応答", ActionTarget.Demo("call:answer")),
                ),
                postTime = now, open = ActionTarget.Demo("open"),
            )
            Type.OngoingCall -> ongoingCall(now)
            Type.Timer -> TimerActivity(
                key = KEY_TIMER, packageName = PKG, label = "パスタ",
                chrono = Chrono(true, now + 5 * 60_000, 0, countDown = true), totalMs = 5 * 60_000,
                actions = timerActions(running = true), postTime = now, open = ActionTarget.Demo("open"),
            )
            Type.Stopwatch -> StopwatchActivity(
                key = KEY_STOPWATCH, packageName = PKG, chrono = Chrono(true, now - 12_340, 0, countDown = false),
                lapCount = 0, actions = stopwatchActions(running = true), postTime = now, open = ActionTarget.Demo("open"),
            )
            Type.Alarm -> RingingActivity(
                key = KEY_ALARM, packageName = PKG, title = "アラーム", detail = "7:00",
                isTimer = false,
                actions = listOf(
                    IslandAction(ActionRole.Snooze, "スヌーズ", ActionTarget.Demo("alarm:stop")),
                    IslandAction(ActionRole.Stop, "停止", ActionTarget.Demo("alarm:stop")),
                ),
                postTime = now, open = ActionTarget.Demo("open"),
            )
            Type.Navigation -> NavigationActivity(
                key = KEY_NAV, packageName = PKG, maneuver = null, distance = "200 m",
                instruction = "右折して 国道1号線", detail = "到着 12:34 · 8 分", open = ActionTarget.Demo("open"),
            )
            Type.Download -> LiveActivity(
                key = KEY_DOWNLOAD, packageName = PKG, kind = Kind.Progress, smallIcon = null, largeIcon = null,
                color = 0xFF0A84FF.toInt(), title = "ダウンロード中", text = "island-demo.zip", shortText = "42%",
                progress = 0.42f, indeterminate = false, chrono = null,
                actions = listOf(IslandAction(ActionRole.Cancel, "キャンセル", ActionTarget.Demo("download:cancel"))),
                postTime = now, open = ActionTarget.Demo("open"),
            )
            Type.Recording -> LiveActivity(
                key = KEY_RECORDING, packageName = PKG, kind = Kind.Recording, smallIcon = null, largeIcon = null,
                color = 0xFFFF453A.toInt(), title = "画面を録画しています", text = null, shortText = null,
                progress = null, indeterminate = false, chrono = Chrono(true, now - 3_000, 0, countDown = false),
                actions = listOf(IslandAction(ActionRole.Stop, "停止", ActionTarget.Demo("recording:stop"))),
                postTime = now, open = ActionTarget.Demo("open"),
            )
        }
        put(a)
    }

    fun clear() {
        items.value = emptyMap()
    }

    fun handle(cmd: String) {
        val now = System.currentTimeMillis()
        when (cmd) {
            "call:answer" -> put(ongoingCall(now))
            "call:decline", "call:hangup" -> remove(KEY_CALL)
            "timer:toggle" -> update<TimerActivity>(KEY_TIMER) { t ->
                val c = t.chrono
                val chrono = if (c.running) Chrono(false, 0, c.valueAt(now), true) else Chrono(true, now + c.frozenMs, 0, true)
                t.copy(chrono = chrono, actions = timerActions(chrono.running))
            }
            "timer:cancel" -> remove(KEY_TIMER)
            "sw:toggle" -> update<StopwatchActivity>(KEY_STOPWATCH) { s ->
                val c = s.chrono
                val chrono = if (c.running) Chrono(false, 0, c.valueAt(now), false) else Chrono(true, now - c.frozenMs, 0, false)
                s.copy(chrono = chrono, actions = stopwatchActions(chrono.running))
            }
            "sw:lap" -> update<StopwatchActivity>(KEY_STOPWATCH) { it.copy(lapCount = it.lapCount + 1) }
            "sw:reset" -> remove(KEY_STOPWATCH)
            "alarm:stop" -> remove(KEY_ALARM)
            "download:cancel" -> remove(KEY_DOWNLOAD)
            "recording:stop" -> remove(KEY_RECORDING)
        }
    }

    fun media(cmd: MediaCommand) {
        val m = items.value[KEY_MEDIA] as? MediaActivity ?: return
        val nowE = SystemClock.elapsedRealtime()
        val pos = m.positionAt(nowE)
        when (cmd) {
            MediaCommand.PlayPause -> put(m.copy(playing = !m.playing, positionMs = pos, positionAtElapsed = nowE))
            MediaCommand.Next -> { track = (track + 1) % tracks.size; put(mediaFor(track, m.playing, 0)) }
            MediaCommand.Previous ->
                if (pos > 3_000) put(m.copy(positionMs = 0, positionAtElapsed = nowE))
                else { track = (track + tracks.size - 1) % tracks.size; put(mediaFor(track, m.playing, 0)) }
            MediaCommand.Forward -> put(m.copy(positionMs = pos + 15_000, positionAtElapsed = nowE))
            MediaCommand.Rewind -> put(m.copy(positionMs = (pos - 15_000).coerceAtLeast(0), positionAtElapsed = nowE))
            MediaCommand.Output -> Unit
        }
    }

    fun seek(positionMs: Long) {
        val m = items.value[KEY_MEDIA] as? MediaActivity ?: return
        put(m.copy(positionMs = positionMs, positionAtElapsed = SystemClock.elapsedRealtime()))
    }

    fun put(a: IslandActivity) = items.update { (it - a.key) + (a.key to a) }
    fun remove(key: String) = items.update { it - key }
    private inline fun <reified T : IslandActivity> update(key: String, f: (T) -> IslandActivity) {
        val cur = items.value[key] as? T ?: return
        put(f(cur))
    }

    private fun ongoingCall(now: Long) = CallActivity(
        key = KEY_CALL, packageName = PKG, incoming = false, name = "山田 太郎", detail = "携帯電話",
        avatar = null, chrono = Chrono(true, now, 0, countDown = false), video = false,
        actions = listOf(
            IslandAction(ActionRole.Speaker, "スピーカー", ActionTarget.Demo("noop")),
            IslandAction(ActionRole.HangUp, "終了", ActionTarget.Demo("call:hangup")),
        ),
        postTime = now, open = ActionTarget.Demo("open"),
    )

    private fun timerActions(running: Boolean) = listOf(
        IslandAction(ActionRole.Cancel, "キャンセル", ActionTarget.Demo("timer:cancel")),
        if (running) IslandAction(ActionRole.Pause, "一時停止", ActionTarget.Demo("timer:toggle"))
        else IslandAction(ActionRole.Play, "再開", ActionTarget.Demo("timer:toggle")),
    )

    private fun stopwatchActions(running: Boolean) =
        if (running) listOf(
            IslandAction(ActionRole.Lap, "ラップ", ActionTarget.Demo("sw:lap")),
            IslandAction(ActionRole.Pause, "一時停止", ActionTarget.Demo("sw:toggle")),
        ) else listOf(
            IslandAction(ActionRole.Reset, "リセット", ActionTarget.Demo("sw:reset")),
            IslandAction(ActionRole.Play, "再開", ActionTarget.Demo("sw:toggle")),
        )

    private fun mediaFor(i: Int, playing: Boolean, positionMs: Long): MediaActivity {
        val (title, artist, colors) = tracks[i]
        val art = gradient(colors)
        return MediaActivity(
            key = KEY_MEDIA, packageName = PKG, title = title, artist = artist, art = art,
            accent = MediaSource.accentOf(art), playing = playing, durationMs = 214_000,
            positionMs = positionMs, positionAtElapsed = SystemClock.elapsedRealtime(), speed = 1f,
            canPrevious = true, canNext = true, canSeek = true, skipByTime = false,
            open = ActionTarget.MediaOpen(KEY_MEDIA),
        )
    }

    private fun gradient(colors: IntArray): Bitmap {
        val b = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        val p = Paint().apply { shader = LinearGradient(0f, 0f, 160f, 160f, colors, null, Shader.TileMode.CLAMP) }
        Canvas(b).drawRect(0f, 0f, 160f, 160f, p)
        return b
    }

    companion object {
        private const val PKG = "dev.ryunosuke.island"
        const val KEY_MEDIA = "demo:media"
        private const val KEY_CALL = "demo:call"
        private const val KEY_TIMER = "demo:timer"
        private const val KEY_STOPWATCH = "demo:stopwatch"
        private const val KEY_ALARM = "demo:alarm"
        private const val KEY_NAV = "demo:nav"
        private const val KEY_DOWNLOAD = "demo:download"
        private const val KEY_RECORDING = "demo:recording"

        fun isDemo(key: String) = key.startsWith("demo:")
    }
}
