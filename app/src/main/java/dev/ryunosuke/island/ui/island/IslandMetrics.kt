package dev.ryunosuke.island.ui.island

import android.graphics.RectF
import dev.ryunosuke.island.data.IslandSettings

/** オーバーレイ窓から見た画面の事実。ピクセル、窓の座標 */
data class HostInfo(
    val widthPx: Int = 0,
    /** パンチホールの矩形。無ければ null */
    val cutout: RectF? = null,
    /** 画面の角の丸み */
    val cornerRadiusPx: Int = 0,
    val statusBarPx: Int = 0,
    val density: Float = 1f,
)

/**
 * 島の寸法（すべてピクセル）。
 *
 * 比率は Apple の WWDC23「Design dynamic Live Activities」の映像（11:10 からの島の見本）と
 * HIG の寸法図を実測したもの。島の高さ H を単位にしている。
 * iPhone では H = 37.33pt（画面幅 393pt の 9.5%）なので、この端末でも画面幅に対して同じ割合にする。
 */
data class IslandMetrics(
    val density: Float,
    val width: Float,
    val cx: Float,
    val cy: Float,
    val holeRadius: Float,
    /** 島の高さ H（待機・コンパクト・分離のとき） */
    val height: Float,
    /** カメラ部分（待機時の島）の幅。コンパクトの中身はこの外側に置く */
    val sensorWidth: Float,
    /** コンパクト時の、中身と島の端の間 */
    val sidePad: Float,
    /** 設定で決めたコンパクトの幅。0 は自動（[compactWidth] を見る） */
    val fixedCompactWidth: Float,
    /** 展開時の左右の余白 */
    val margin: Float,
    /** 展開時の角の大きさ（超楕円の角が縁に沿って占める長さ）と指数 */
    val expandedCornerExtent: Float,
    val expandedCornerExponent: Float,
    /** 2 つ同時のとき、主の島がカメラ部分から左へ伸びる長さ */
    val minimalLead: Float,
    /** 2 つ同時のとき、右に離れる島の幅と、主の島との隙間 */
    val detachedWidth: Float,
    val detachedGap: Float,
) {
    val top get() = cy - height / 2
    val expandedWidth get() = width - margin * 2

    /** コンパクト時の小さな絵（ジャケット・アイコン・波形）の大きさ。HIG の寸法図では中身の高さが 0.53H */
    val glyph get() = (height * 0.55f).coerceIn(16 * density, 26 * density)

    fun dp(px: Float) = px / density

    /**
     * コンパクトの幅。[edge] は片側の中身の幅と島の端との余白の和（左右の広い方）。
     * 自動なら、カメラ部分の外に中身を置いた幅（iPhone と同じ）。設定で決めていればその幅にして、中身は両端に寄せる。
     * ただし待機時の島より狭くはせず、中身がカメラ穴に被らないだけの間（穴 + 余白 1 つ分）は残す
     */
    fun compactWidth(edge: Float): Float {
        if (fixedCompactWidth <= 0f) return sensorWidth + 2 * edge
        return maxOf(fixedCompactWidth, sensorWidth, holeRadius * 2 + sidePad + 2 * edge)
    }

    /**
     * ステータスバーの真ん中に空ける幅の半分（画面の中央から片側）。
     * [gapDp] が 0（自動）なら、島が待機・コンパクト（左右に絵を置いた形。音楽など）・2 つ同時のどの形でも入り、
     * 島の端とアイコンの間に [detachedGap] だけ隙間が残る幅。ステータスバーは空きを画面の中央に置くので、
     * 島を左右にずらしていればその分も広げる。展開した島は覆ってしまうので見ない。
     * どちらでも、カメラ穴（中央から [holeHalf] まで）は覆い、両脇に画面幅の 1/8 ずつは残す
     * （空きが画面の端に届くと、システムがカメラ穴を角にあるものとみなし、真ん中を空けなくなる）
     */
    fun statusBarGapHalf(gapDp: Float, holeHalf: Float): Float {
        val half = if (gapDp > 0f) {
            gapDp * density / 2
        } else {
            val compact = compactWidth(glyph + sidePad) / 2
            // 2 つ同時: 主は右端をカメラ部分にそろえて左へ伸び、右に離れた島が出る
            val left = maxOf(compact, sensorWidth / 2 + minimalLead)
            val right = maxOf(compact, sensorWidth / 2 + detachedGap + detachedWidth)
            val center = width / 2
            maxOf(cx + right - center, center - (cx - left)) + detachedGap
        }
        return half.coerceAtLeast(holeHalf).coerceAtMost(width * 3 / 8)
    }

    companion object {
        /** iPhone の島の高さ 37.33pt ÷ 画面幅 393pt */
        const val HEIGHT_PER_WIDTH = 37.33f / 393f

        /** 待機時の島 126pt × 37.33pt（映像: 336×99px、比 3.39） */
        const val IDLE_WIDTH_PER_HEIGHT = 3.375f

        /** 展開したときの幅の下限。音楽の操作ボタン（中央の 3 つ）と右端の出力先ボタンが重ならない幅 */
        const val MIN_EXPANDED_WIDTH_DP = 330f

        /** 待機時の島の幅の目安（dp）。設定画面のスライダーに使う（カメラ穴の大きさは見ないので、実際と少しずれうる） */
        fun idleWidthDp(screenWidthDp: Float, s: IslandSettings): Float =
            if (s.centerWidthDp > 0) s.centerWidthDp
            else (if (s.heightDp > 0) s.heightDp else screenWidthDp * HEIGHT_PER_WIDTH) * IDLE_WIDTH_PER_HEIGHT

        fun from(info: HostInfo, s: IslandSettings): IslandMetrics {
            val d = info.density
            val hole = info.cutout
            val holeR = hole?.let { minOf(it.width(), it.height()) / 2 } ?: (5 * d)
            val cx = (hole?.centerX() ?: (info.widthPx / 2f)) + s.offsetXDp * d
            val cy = (hole?.centerY() ?: (info.statusBarPx / 2f)).coerceAtLeast(16 * d) + s.offsetYDp * d
            // カメラ穴を必ず覆う大きさは確保する
            val h = if (s.heightDp > 0) s.heightDp * d else maxOf(info.widthPx * HEIGHT_PER_WIDTH, holeR * 2 + 6 * d)
            // 上端が画面の外に出ないように
            val top = (cy - h / 2).coerceAtLeast(2 * d)
            val cyFixed = top + h / 2
            val w = info.widthPx.toFloat()
            // 映像: 展開時の幅 373.2pt（画面 393pt）→ 左右 9.9pt = 0.265H。設定で決めていれば、下限から画面幅までに収めて画面の中央に置く
            // （窓の幅がまだ分からない 0 のときも落ちないように、上限を後にかける）
            val margin = if (s.expandedWidthDp > 0) {
                (w - (s.expandedWidthDp * d).coerceAtLeast(MIN_EXPANDED_WIDTH_DP * d).coerceAtMost(w)) / 2
            } else h * 0.265f
            return IslandMetrics(
                density = d,
                width = w,
                cx = cx,
                cy = cyFixed,
                holeRadius = holeR,
                height = h,
                sensorWidth = if (s.centerWidthDp > 0) s.centerWidthDp * d else h * IDLE_WIDTH_PER_HEIGHT,
                // HIG の寸法図: 中身は島の端から 0.35〜0.47H
                sidePad = h * 0.40f,
                fixedCompactWidth = s.compactWidthDp * d,
                margin = margin,
                // 映像: 展開時の角は超楕円（指数 3.2、縁に沿って 61pt = 1.634H）
                expandedCornerExtent = h * 1.634f,
                expandedCornerExponent = 3.2f,
                // 主の島は右端をカメラ部分にそろえて左へ伸びる。映像（中身なし）は 0.6H、HIG の寸法図（中身あり）は 0.9H
                minimalLead = h * 0.9f,
                // 映像: 右の島は 1.2H × 1.0H、隙間 0.19H（画角の縮みを補正した値）
                detachedWidth = h * 1.2f,
                detachedGap = h * 0.19f,
            )
        }
    }
}
