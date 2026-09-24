package dev.ryunosuke.island.island

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatTest {
    @Test fun clock() {
        assertEquals("0:00", TimeFormat.clock(0))
        assertEquals("4:59", TimeFormat.clock(299_999))
        assertEquals("1:02:03", TimeFormat.clock(3_723_000))
    }

    @Test fun countdownRoundsUp() {
        // 残り 0.4 秒は 0:01 と出す（0:00 になったら終わり）
        assertEquals("0:01", TimeFormat.countdown(400))
        assertEquals("5:00", TimeFormat.countdown(299_001))
        assertEquals("0:00", TimeFormat.countdown(0))
    }

    @Test fun stopwatch() {
        assertEquals("0:12.34", TimeFormat.stopwatch(12_345))
        assertEquals("59:59.99", TimeFormat.stopwatch(3_599_999))
        assertEquals("1:00:00", TimeFormat.stopwatch(3_600_000))
    }

    @Test fun media() {
        assertEquals("-3:05", TimeFormat.media(185_000, remaining = true))
    }
}
