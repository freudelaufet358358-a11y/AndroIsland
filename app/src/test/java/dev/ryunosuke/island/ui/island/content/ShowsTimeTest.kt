package dev.ryunosuke.island.ui.island.content

import dev.ryunosuke.island.island.CallActivity
import dev.ryunosuke.island.island.Chrono
import dev.ryunosuke.island.island.Kind
import dev.ryunosuke.island.island.LiveActivity
import dev.ryunosuke.island.island.MediaActivity
import dev.ryunosuke.island.island.NavigationActivity
import dev.ryunosuke.island.island.StopwatchActivity
import dev.ryunosuke.island.island.TimerActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2 つ同時で主の島が狭くなっても時間を出すもの（コンパクトで時間を出しているものと同じ） */
class ShowsTimeTest {
    private val running = Chrono(true, 0, 0, countDown = false)

    private fun call(incoming: Boolean = false, chrono: Chrono? = running) = CallActivity(
        "call", "dialer", incoming, "山田", null, null, chrono, false, emptyList(), 0, null,
    )

    private fun live(shortText: String? = null, progress: Float? = null, indeterminate: Boolean = false, chrono: Chrono? = running) =
        LiveActivity(
            "live", "rec", Kind.Recording, null, null, 0, "録画中", null, shortText, progress, indeterminate, chrono,
            emptyList(), 0, null,
        )

    @Test fun clocksShowTime() {
        assertTrue(StopwatchActivity("sw", "clock", running, lapCount = 0, actions = emptyList(), postTime = 0, open = null).showsTime())
        // 一時停止中も止まった時間を出す
        val paused = Chrono(false, 0, 3_000, countDown = true)
        assertTrue(TimerActivity("t", "clock", null, paused, null, emptyList(), 0, null).showsTime())
    }

    @Test fun ongoingCallShowsItsDuration() {
        assertTrue(call().showsTime())
        // 通話時間が分からない通話と、着信（名前を出している）は印だけ
        assertFalse(call(chrono = null).showsTime())
        assertFalse(call(incoming = true, chrono = null).showsTime())
    }

    @Test fun liveActivityOnlyWhenCompactShowsItsClock() {
        assertTrue(live().showsTime())
        // コンパクトの右側が短い文や進捗のときは、時間を出していないので印だけ
        assertFalse(live(shortText = "5 分").showsTime())
        assertFalse(live(progress = 0.4f).showsTime())
        assertFalse(live(indeterminate = true).showsTime())
        assertFalse(live(chrono = null).showsTime())
    }

    @Test fun othersKeepTheMarkOnly() {
        val media = MediaActivity(
            "media:x", "x", "曲", "a", null, 0, true, 0, 0, 0, 1f,
            canPrevious = true, canNext = true, canSeek = true, skipByTime = false, open = null,
        )
        assertFalse(media.showsTime())
        assertFalse(NavigationActivity("nav", "maps", null, "200 m", "右折", null, null).showsTime())
    }
}
