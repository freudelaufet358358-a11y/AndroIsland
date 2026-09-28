package dev.ryunosuke.island.ui.island

import dev.ryunosuke.island.data.IslandSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandMetricsTest {
    // Pixel 6a: 幅 1080px、2.625 倍（412dp）
    private val d = 2.625f
    private val info = HostInfo(widthPx = 1080, statusBarPx = 131, density = d)
    private fun metrics(s: IslandSettings = IslandSettings()) = IslandMetrics.from(info, s)

    @Test fun autoWidthsKeepIphoneRatios() {
        val m = metrics()
        assertEquals(m.height * IslandMetrics.IDLE_WIDTH_PER_HEIGHT, m.sensorWidth, 0.01f)
        assertEquals(m.height * 0.265f, m.margin, 0.01f)
        // コンパクトはカメラ部分の外に中身を置いた幅
        assertEquals(m.sensorWidth + 2 * 80f, m.compactWidth(80f), 0.01f)
    }

    @Test fun expandedWidthSettingIsCenteredOnScreen() {
        val m = metrics(IslandSettings(expandedWidthDp = 360f))
        assertEquals(360f * d, m.expandedWidth, 0.5f)
        assertEquals((1080f - 360f * d) / 2, m.margin, 0.5f)
    }

    @Test fun expandedWidthStaysBetweenMinimumAndScreen() {
        assertEquals(IslandMetrics.MIN_EXPANDED_WIDTH_DP * d, metrics(IslandSettings(expandedWidthDp = 100f)).expandedWidth, 0.5f)
        assertEquals(1080f, metrics(IslandSettings(expandedWidthDp = 2000f)).expandedWidth, 0.5f)
    }

    @Test fun windowWithoutWidthYetDoesNotThrow() {
        // 窓の幅が分かる前にも作られる（OverlayHost の remember）
        val m = IslandMetrics.from(HostInfo(density = d), IslandSettings(expandedWidthDp = 360f))
        assertEquals(0f, m.expandedWidth, 0.01f)
    }

    @Test fun compactWidthSettingIsUsedWhenContentFits() {
        val m = metrics(IslandSettings(compactWidthDp = 180f))
        assertEquals(180f * d, m.compactWidth(80f), 0.5f)
        // 自動より広くもできる（中身は両端に寄る）
        assertEquals(400f * d, metrics(IslandSettings(compactWidthDp = 400f)).compactWidth(80f), 0.5f)
    }

    @Test fun compactWidthNeverNarrowerThanIdleIslandNorOverTheCamera() {
        val m = metrics(IslandSettings(compactWidthDp = 50f))
        assertEquals(m.sensorWidth, m.compactWidth(80f), 0.01f)
        // 中身が広いときは、カメラ穴と余白 1 つ分の間を残して広がる
        assertEquals(m.holeRadius * 2 + m.sidePad + 2 * 300f, m.compactWidth(300f), 0.01f)
    }

    @Test fun statusBarGapHugsCompactIsland() {
        val m = metrics()
        // コンパクト（左右に絵。音楽など）がちょうど入り、両端に隙間 1 つ分
        assertEquals(m.compactWidth(m.glyph + m.sidePad) / 2 + m.detachedGap, m.statusBarGapHalf(0f, 0f), 0.01f)
        // Pixel 6a の既定の大きさでは約 221dp（両脇に 95dp ずつ残る）
        assertEquals(221f, 2 * m.statusBarGapHalf(0f, 0f) / d, 1f)
    }

    @Test fun statusBarGapShrinksWithShortIsland() {
        // 待機時の幅を 47dp に縮めると、音楽のコンパクトは約 121dp。空きもそれに隙間 2 つ分を足しただけ
        // （前は 2 つ同時の右の丸まで入れていたので 171dp 空き、島の両脇に 25dp ずつ余っていた）
        val m = metrics(IslandSettings(centerWidthDp = 47f))
        val compact = m.compactWidth(m.glyph + m.sidePad)
        assertEquals(121f, compact / d, 1f)
        assertEquals(compact / 2 + m.detachedGap, m.statusBarGapHalf(0f, 0f), 0.01f)
        assertEquals(136f, 2 * m.statusBarGapHalf(0f, 0f) / d, 1f)
        assertTrue(m.statusBarGapHalf(0f, 0f) < metrics().statusBarGapHalf(0f, 0f))
    }

    @Test fun statusBarGapFollowsFixedCompactWidthAndOffset() {
        // コンパクトを広くすれば、それが入る幅
        val wide = metrics(IslandSettings(compactWidthDp = 280f))
        assertEquals(280f * d / 2 + wide.detachedGap, wide.statusBarGapHalf(0f, 0f), 0.5f)
        // 島を右にずらすと、空き（画面の中央に置かれる）もその分広がる
        val shifted = metrics(IslandSettings(offsetXDp = 10f))
        assertEquals(metrics().statusBarGapHalf(0f, 0f) + 10f * d, shifted.statusBarGapHalf(0f, 0f), 0.5f)
    }

    @Test fun statusBarGapSettingIsClampedToCameraAndScreen() {
        val m = metrics()
        assertEquals(200f * d / 2, m.statusBarGapHalf(200f, 0f), 0.01f)
        // カメラ穴は必ず覆い、両脇に画面幅の 1/8 は残す
        assertEquals(100f, m.statusBarGapHalf(50f, 100f), 0.01f)
        assertEquals(1080f * 3 / 8, m.statusBarGapHalf(1000f, 0f), 0.01f)
    }

    @Test fun idleWidthEstimateMatchesMetrics() {
        assertEquals(metrics().sensorWidth / d, IslandMetrics.idleWidthDp(1080 / d, IslandSettings()), 0.5f)
        assertEquals(150f, IslandMetrics.idleWidthDp(1080 / d, IslandSettings(centerWidthDp = 150f)), 0.01f)
    }
}
