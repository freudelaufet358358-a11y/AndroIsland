package dev.ryunosuke.island.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockStateTest {
    @Test fun stopwatchAccumulatesAcrossPauses() {
        var s = StopwatchState().toggle(now = 1_000)
        assertEquals(500, s.elapsed(1_500))
        s = s.toggle(2_000) // 止める: 1000ms
        assertEquals(1_000, s.elapsed(9_999))
        s = s.toggle(10_000)
        assertEquals(1_250, s.elapsed(10_250))
    }

    @Test fun lapOnlyWhileRunning() {
        var s = StopwatchState().toggle(0)
        s = s.lap(300).lap(700)
        assertEquals(listOf(300L, 700L), s.laps)
        s = s.toggle(800)
        assertEquals(s, s.lap(900))
        assertEquals(StopwatchState(), s.reset())
    }

    @Test fun timerPauseResume() {
        var t = TimerState.start(60_000, now = 0)
        assertEquals(40_000, t.remaining(20_000))
        t = t.toggle(20_000)
        assertFalse(t.running)
        assertEquals(40_000, t.remaining(99_000))
        t = t.toggle(100_000)
        assertEquals(140_000, t.endElapsed)
    }

    @Test fun timerAddMinuteAndFinish() {
        var t = TimerState.start(60_000, 0).addMinute(10_000)
        assertEquals(120_000, t.endElapsed)
        assertEquals(120_000, t.totalMs)
        t = t.finish()
        assertTrue(t.ringing)
        assertEquals(0, t.remaining(500_000))
        // 鳴っている間の +1 分はもう一度 1 分かけ直す
        val again = t.addMinute(500_000)
        assertFalse(again.ringing)
        assertEquals(560_000, again.endElapsed)
    }

    @Test fun inactiveTimerIgnoresToggle() {
        assertEquals(TimerState(), TimerState().toggle(5))
    }
}
