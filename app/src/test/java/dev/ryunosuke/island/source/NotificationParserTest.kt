package dev.ryunosuke.island.source

import dev.ryunosuke.island.island.ActionRole
import dev.ryunosuke.island.island.ActionTarget
import dev.ryunosuke.island.island.CallActivity
import dev.ryunosuke.island.island.Kind
import dev.ryunosuke.island.island.LiveActivity
import dev.ryunosuke.island.island.NavigationActivity
import dev.ryunosuke.island.island.RingingActivity
import dev.ryunosuke.island.island.StopwatchActivity
import dev.ryunosuke.island.island.TimerActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationParserTest {
    private val o = ParserOptions()
    private val clock = "com.google.android.deskclock"

    private fun snap(pkg: String = "com.example", key: String = "k", post: Long = 100_000, `when`: Long = 0) =
        NotificationSnapshot(key = key, packageName = pkg, postTime = post, whenTime = `when`)

    private fun actions(vararg t: String) = t.map { SnapshotAction(it, false) }

    @Test fun incomingCallStyle() {
        val s = snap("com.google.android.dialer").copy(
            template = "android.app.Notification\$CallStyle", category = "call", callType = 1,
            callerName = "山田", hasAnswer = true, hasDecline = true, hasFullScreenIntent = true, hasContentIntent = true,
        )
        val a = NotificationParser.parse(s, o) as CallActivity
        assertTrue(a.incoming)
        assertEquals("山田", a.name)
        assertEquals(listOf(ActionRole.Decline, ActionRole.Answer), a.actions.map { it.role })
        assertEquals(Kind.IncomingCall, a.kind)
    }

    @Test fun ongoingCallKeepsExtraButtons() {
        val s = snap("com.google.android.dialer", `when` = 90_000).copy(
            template = "android.app.Notification\$CallStyle", category = "call", callType = 2, ongoing = true,
            title = "山田", hasHangUp = true, actions = actions("スピーカー", "通話を終了"),
        )
        val a = NotificationParser.parse(s, o) as CallActivity
        assertFalse(a.incoming)
        assertEquals(90_000L, a.chrono?.baseWall)
        // 通知側の「通話を終了」は CallStyle の終了 intent と重なるので一つにまとめる
        assertEquals(listOf(ActionRole.Speaker, ActionRole.HangUp), a.actions.map { it.role })
        assertTrue(a.actions.last().target is ActionTarget.NotificationIntent)
    }

    @Test fun missedCallIsIgnored() {
        val s = snap("com.google.android.dialer").copy(category = "call", title = "不在着信")
        assertTrue(NotificationParser.parse(s, o) !is CallActivity)
    }

    @Test fun standardStopwatchRunning() {
        val s = snap(clock, `when` = 88_000).copy(
            category = "stopwatch", ongoing = true, showChronometer = true, actions = actions("一時停止", "ラップ"),
        )
        val a = NotificationParser.parse(s, o) as StopwatchActivity
        assertTrue(a.chrono.running)
        assertEquals(12_000, a.chrono.valueAt(100_000))
    }

    @Test fun customViewStopwatchPausedFreezesAtPostTime() {
        val s = snap(clock, post = 100_000).copy(
            category = "stopwatch", actions = actions("開始", "リセット"),
            custom = CustomContent(chronometerBaseWall = 95_000, chronometerCountDown = false, texts = listOf("ラップ 2")),
        )
        val a = NotificationParser.parse(s, o) as StopwatchActivity
        assertFalse(a.chrono.running)
        assertEquals(5_000, a.chrono.valueAt(999_999))
        assertEquals(2, a.lapCount)
    }

    @Test fun googleClockPausedStopwatchIsTextOnly() {
        // 実機（Google 時計, 2026-09）: 一時停止中は Chronometer が消え、秒までの文字だけになる
        val s = snap(clock).copy(
            channelId = "Stopwatch v2", template = "android.app.Notification\$DecoratedCustomViewStyle",
            actions = actions("開始", "リセット"),
            custom = CustomContent(chronometerBaseWall = null, chronometerCountDown = false, texts = listOf("00:03", "一時停止しました")),
        )
        val a = NotificationParser.parse(s, o) as StopwatchActivity
        assertFalse(a.chrono.running)
        assertEquals(3_000, a.chrono.frozenMs)
        assertFalse(a.preciseFraction)
    }

    @Test fun googleClockRunningTimer() {
        // 実機: 動作中は「一時停止」「1分追加」の 2 つだけで、ongoing フラグは立たない
        val s = snap(clock, post = 100_000).copy(
            channelId = "Timers v2", actions = actions("一時停止", "1分追加"),
            custom = CustomContent(chronometerBaseWall = 700_000, chronometerCountDown = true, texts = emptyList()),
        )
        val a = NotificationParser.parse(s, o) as TimerActivity
        assertTrue(a.chrono.running)
        assertEquals(listOf(ActionRole.Pause, ActionRole.AddTime), a.actions.map { it.role })
    }

    @Test fun customViewTimerCountsDown() {
        val s = snap(clock, post = 100_000).copy(
            channelId = "Timers", ongoing = true, actions = actions("一時停止", "+1:00", "リセット"),
            custom = CustomContent(chronometerBaseWall = 400_000, chronometerCountDown = true, texts = listOf("タイマー")),
        )
        val a = NotificationParser.parse(s, o) as TimerActivity
        assertTrue(a.chrono.running)
        assertEquals(300_000, a.chrono.valueAt(100_000))
        assertEquals(listOf(ActionRole.Pause, ActionRole.AddTime, ActionRole.Reset), a.actions.map { it.role })
    }

    @Test fun pausedTimerWithoutChronometerReadsText() {
        val s = snap(clock).copy(channelId = "Timers", title = "4:32", text = "タイマー", actions = actions("再開", "リセット"))
        val a = NotificationParser.parse(s, o) as TimerActivity
        assertFalse(a.chrono.running)
        assertEquals(272_000, a.chrono.frozenMs)
    }

    @Test fun ringingAlarm() {
        val s = snap(clock).copy(category = "alarm", hasFullScreenIntent = true, title = "アラーム", text = "7:00", actions = actions("スヌーズ", "停止"))
        val a = NotificationParser.parse(s, o) as RingingActivity
        assertFalse(a.isTimer)
        assertEquals(listOf(ActionRole.Snooze, ActionRole.Stop), a.actions.map { it.role })
    }

    @Test fun upcomingAlarmIsNotShown() {
        val s = snap(clock).copy(category = "alarm", title = "次のアラーム", text = "7:00", actions = actions("今すぐ解除"))
        assertNull(NotificationParser.parse(s, o))
    }

    @Test fun navigationDistance() {
        val s = snap("com.google.android.apps.maps").copy(
            category = "navigation", ongoing = true, title = "200 m", text = "右折して 国道1号線", subText = "到着 12:34",
        )
        val a = NotificationParser.parse(s, o) as NavigationActivity
        assertEquals("200 m", a.distance)
        assertEquals("右折して 国道1号線", a.instruction)
    }

    @Test fun downloadProgressIsLive() {
        val s = snap("com.android.providers.downloads").copy(ongoing = true, title = "file.zip", progress = 42, progressMax = 100)
        val a = NotificationParser.parse(s, o) as LiveActivity
        assertEquals(Kind.Progress, a.kind)
        assertEquals(0.42f, a.progress!!, 0.001f)
    }

    @Test fun recordingChronometerIsLive() {
        val s = snap("com.android.systemui", `when` = 1).copy(ongoing = true, title = "画面を録画しています", showChronometer = true)
        assertEquals(Kind.Recording, (NotificationParser.parse(s, o) as LiveActivity).kind)
    }

    @Test fun plainForegroundServiceIsIgnored() {
        val s = snap("com.example").copy(ongoing = true, title = "同期しています")
        assertNull(NotificationParser.parse(s, o))
    }

    @Test fun promotedLiveUpdate() {
        val s = snap("com.example.food").copy(promoted = true, ongoing = true, title = "配達中", shortCriticalText = "5分")
        val a = NotificationParser.parse(s, o) as LiveActivity
        assertEquals("5分", a.shortText)
    }

    @Test fun excludedAndOwnAndMediaAreSkipped() {
        val live = snap("com.example.food").copy(promoted = true, ongoing = true, title = "配達中")
        assertNull(NotificationParser.parse(live, o.copy(excludedPackages = setOf("com.example.food"))))
        assertNull(NotificationParser.parse(live.copy(packageName = o.ownPackage), o))
        assertNull(NotificationParser.parse(live.copy(isMedia = true), o))
    }

    @Test fun roles() {
        assertEquals(ActionRole.Pause, NotificationParser.roleOf("一時停止"))
        assertEquals(ActionRole.Play, NotificationParser.roleOf("Resume"))
        assertEquals(ActionRole.AddTime, NotificationParser.roleOf("+1:00"))
        assertEquals(ActionRole.Cancel, NotificationParser.roleOf("削除"))
        assertEquals(ActionRole.Stop, NotificationParser.roleOf("停止"))
        assertEquals(ActionRole.HangUp, NotificationParser.roleOf("Hang up"))
        assertEquals(ActionRole.Generic, NotificationParser.roleOf("返信"))
    }

    @Test fun timeText() {
        assertEquals(3_723_000L, NotificationParser.parseTimeText(listOf("残り 1:02:03")))
        assertEquals(12_340L, NotificationParser.parseTimeText(listOf("0:12.34")))
        assertNull(NotificationParser.parseTimeText(listOf("タイマー")))
    }
}
