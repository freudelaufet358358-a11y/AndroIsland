package dev.ryunosuke.island.source

import dev.ryunosuke.island.data.IslandSettings
import dev.ryunosuke.island.ui.island.HostInfo
import dev.ryunosuke.island.ui.island.IslandMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusBarGapTest {
    // Pixel 6a: 幅 1080px、2.625 倍。カメラ穴の矩形は 480〜625px × 高さ 132px（端末の設定値）
    private val d = 2.625f
    private val m = IslandMetrics.from(HostInfo(widthPx = 1080, statusBarPx = 132, density = d), IslandSettings())

    @Test fun specIsCenteredRectangleKeepingHeight() {
        val gap = StatusBarGap.Gap.of(m, 0f, 200f, 132, 1080)
        // 200dp = 525px → 半分 262.5 を丸めて 263
        assertEquals("M -263,0 H 263 V 132 H -263 Z", gap.spec)
        assertEquals(526f, gap.widthPx, 0.01f)
        assertEquals(200, gap.widthDp)
    }

    @Test fun matchesOnlyTheReportedGap() {
        val gap = StatusBarGap.Gap.of(m, 0f, 200f, 132, 1080)
        assertTrue(gap.matches(526, 132))
        assertTrue(gap.matches(524, 131))
        // 端末の元の矩形（幅 145px）
        assertFalse(gap.matches(145, 132))
        // 高さが違う
        assertFalse(gap.matches(526, 150))
    }

    @Test fun specIsWrittenInPixelsOfTheLargestMode() {
        // 1440px のパネルを 1080px で使っているとき、設定値は 1440px の画素で書く（システムが 1080px に縮める）
        val gap = StatusBarGap.Gap.of(m, 0f, 200f, 132, 1440)
        assertEquals("M -350,0 H 350 V 176 H -350 Z", gap.spec)
        assertEquals(525f, gap.widthPx, 0.01f)
        assertTrue(gap.matches(525, 132))
    }
}
