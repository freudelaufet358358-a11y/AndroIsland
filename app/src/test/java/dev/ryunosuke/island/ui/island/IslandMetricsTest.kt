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
        // 待機時の幅を 62dp に縮めると、音楽のコンパクトは約 136dp。空きはそれに島の端とアイコンの隙間 2 つ分を足した約 151dp
        val m = metrics(IslandSettings(centerWidthDp = 62f))
        assertEquals(m.compactWidth(m.glyph + m.sidePad) / 2 + m.detachedGap, m.statusBarGapHalf(0f, 0f), 0.01f)
        assertEquals(151f, 2 * m.statusBarGapHalf(0f, 0f) / d, 1f)
        assertEquals(0f, m.compactOverStatusBar(0f, 0f), 0.01f)
    }

    @Test fun statusBarGapShrinksWithShortIsland() {
        // 待機時の幅を 47dp に縮めると、音楽のコンパクトは約 121dp。空きもそれに隙間 2 つ分を足しただけ
        // （前は 2 つ同時の右の丸まで入れていたので 171dp 空き、島の両脇に 25dp ずつ余っていた）
        val m = metrics(IslandSettings(centerWidthDp = 47f))
        val compact = m.compactWidth(m.glyph + m.sidePad)
        assertEquals(121f, compact / d, 1f)
        assertEquals(compact / 2 + m.detachedGap, m.statusBarGapHalf(0f, 0f), 0.01f)
        assertEquals(136f, 2 * m.statusBarGapHalf(0f, 0f) / d, 1f)
        assertTrue(m.statusBarGapHalf(0f, 0f) < metrics(IslandSettings(centerWidthDp = 62f)).statusBarGapHalf(0f, 0f))
    }

    @Test fun statusBarGapFollowsFixedCompactWidthAndOffset() {
        // コンパクトを決めた幅にすれば、それが入る幅
        val fixed = metrics(IslandSettings(compactWidthDp = 140f))
        assertEquals(140f * d / 2 + fixed.detachedGap, fixed.statusBarGapHalf(0f, 0f), 0.5f)
        // 島を右にずらすと、空き（画面の中央に置かれる）もその分広がる
        val short = IslandSettings(centerWidthDp = 47f)
        val shifted = metrics(short.copy(offsetXDp = 10f))
        assertEquals(metrics(short).statusBarGapHalf(0f, 0f) + 10f * d, shifted.statusBarGapHalf(0f, 0f), 0.5f)
    }

    @Test fun statusBarGapLeavesRoomForMinimumIcons() {
        // Pixel 6a の既定の文字サイズでは、両脇に 126dp ずつ残して 159dp まで
        val cap = IslandMetrics.maxStatusBarGapDp(1080 / d, 1f)
        assertEquals(159f, cap, 1f)
        val half = cap * d / 2
        // 171dp 空けたときのスクリーンショット（px）で、時計「14:14」のあとの通知アイコンの枠（58px）は 138.5px から並ぶ。
        // 2 つと「•」の場所の 3 枠に、「1」より幅の広い数字だけの時刻の分（16px）を足しても入る
        assertTrue(540 - half >= 138.5f + 3 * 58f + 16f)
        // 右は 5G が 806px から。その手前に、ほかのアイコンをまとめた「•」の場所（アイコン 1 つ分、約 47px）が残る
        assertTrue(540 + half + 47f <= 806f)
        // 文字を大きくすると、時計とアイコンも大きくなるので狭くなる
        assertTrue(IslandMetrics.maxStatusBarGapDp(1080 / d, 1.3f) < cap - 40f)
    }

    @Test fun statusBarGapKeepsIconRoomOverCompactIsland() {
        // 既定の大きさのコンパクト（約 206dp）は入れきらない。アイコンの場所を残し、コンパクトの方が被る
        val m = metrics()
        val half = IslandMetrics.maxStatusBarGapDp(1080 / d, 1f) * d / 2
        assertEquals(half, m.statusBarGapHalf(0f, 0f), 0.5f)
        assertEquals(m.compactReach - half, m.compactOverStatusBar(0f, 0f), 0.5f)
        assertEquals(23f, m.dp(m.compactOverStatusBar(0f, 0f)), 1f)
        // 手で決めた幅も同じ
        assertEquals(half, m.statusBarGapHalf(200f, 0f), 0.5f)
        // 文字が大きいと、残す場所も広い
        val big = IslandMetrics.from(info.copy(fontScale = 1.3f), IslandSettings())
        assertTrue(big.statusBarGapHalf(0f, 0f) < half)
    }

    @Test fun statusBarGapSettingIsClampedToCameraAndScreen() {
        val m = metrics()
        assertEquals(150f * d / 2, m.statusBarGapHalf(150f, 0f), 0.01f)
        // カメラ穴は必ず覆う
        assertEquals(100f, m.statusBarGapHalf(50f, 100f), 0.01f)
        // 広い画面でも、両脇に画面幅の 1/8 は残す
        val wide = IslandMetrics.from(HostInfo(widthPx = 3000, statusBarPx = 100, density = 2f), IslandSettings())
        assertEquals(3000f * 3 / 8, wide.statusBarGapHalf(1400f, 0f), 0.01f)
    }

    @Test fun cameraBottomIsMeasuredFromIslandTop() {
        // 島はカメラ穴を上下の真ん中に置くので、穴の下端は島の上端から H/2 + 穴の半径
        val m = metrics()
        assertEquals(m.height / 2 + m.holeRadius, m.cameraBottom, 0.01f)
        // 島を下へずらしても、穴（島の真ん中とみなす）との関係は変わらない
        val lowered = metrics(IslandSettings(offsetYDp = 10f))
        assertEquals(lowered.height / 2 + lowered.holeRadius, lowered.cameraBottom, 0.01f)
    }

    @Test fun idleWidthEstimateMatchesMetrics() {
        assertEquals(metrics().sensorWidth / d, IslandMetrics.idleWidthDp(1080 / d, IslandSettings()), 0.5f)
        assertEquals(150f, IslandMetrics.idleWidthDp(1080 / d, IslandSettings(centerWidthDp = 150f)), 0.01f)
    }
}
