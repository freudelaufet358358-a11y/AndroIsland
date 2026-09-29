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
        val gap = StatusBarGap.Gap.of(m, 0f, 144f, 132, 1080)
        // 144dp = 378px → 半分 189
        assertEquals("M -189,0 H 189 V 132 H -189 Z", gap.spec)
        assertEquals(378f, gap.widthPx, 0.01f)
        assertEquals(144, gap.widthDp)
        assertTrue(gap.isWellFormed())
    }

    @Test fun neverCrowdsOutTheMinimumIcons() {
        // 自動（既定の島なら 221dp）も、手で決めた広い幅も、通知アイコン 2 つと 5G・アンテナ・電池が入る 159dp まで
        val auto = StatusBarGap.Gap.of(m, 0f, 0f, 132, 1080)
        assertEquals(159, auto.widthDp)
        assertEquals(auto.spec, StatusBarGap.Gap.of(m, 0f, 250f, 132, 1080).spec)
        assertTrue(auto.isWellFormed())
    }

    @Test fun readsSystemUiPidFromPidof() {
        assertEquals(1234, StatusBarGap.parsePid("1234\n"))
        // 同じ名前が複数あれば最初の 1 つ
        assertEquals(1234, StatusBarGap.parsePid("1234 5678\n"))
        assertEquals(null, StatusBarGap.parsePid(""))
        assertEquals(null, StatusBarGap.parsePid("0"))
    }

    @Test fun emptyRectangleIsNeverSent() {
        // システムの中で読まれる値なので、中身の無い矩形は送らない
        assertFalse(StatusBarGap.Gap(0, 132, 1f, d).isWellFormed())
        assertFalse(StatusBarGap.Gap(263, 0, 1f, d).isWellFormed())
    }

    @Test fun matchesOnlyTheReportedGap() {
        val gap = StatusBarGap.Gap.of(m, 0f, 144f, 132, 1080)
        assertTrue(gap.matches(378, 132))
        assertTrue(gap.matches(376, 131))
        // 端末の元の矩形（幅 145px）
        assertFalse(gap.matches(145, 132))
        // 高さが違う
        assertFalse(gap.matches(378, 150))
    }

    @Test fun specIsWrittenInPixelsOfTheLargestMode() {
        // 1440px のパネルを 1080px で使っているとき、設定値は 1440px の画素で書く（システムが 1080px に縮める）
        val gap = StatusBarGap.Gap.of(m, 0f, 144f, 132, 1440)
        assertEquals("M -252,0 H 252 V 176 H -252 Z", gap.spec)
        assertEquals(378f, gap.widthPx, 0.01f)
        assertTrue(gap.matches(378, 132))
    }
}
