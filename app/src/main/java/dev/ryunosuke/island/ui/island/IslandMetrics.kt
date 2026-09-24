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

    fun dp(px: Float) = px / density

    companion object {
        /** iPhone の島の高さ 37.33pt ÷ 画面幅 393pt */
        const val HEIGHT_PER_WIDTH = 37.33f / 393f

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
            return IslandMetrics(
                density = d,
                width = info.widthPx.toFloat(),
                cx = cx,
                cy = cyFixed,
                holeRadius = holeR,
                height = h,
                // 待機時の島 126pt × 37.33pt（映像: 336×99px、比 3.39）
                sensorWidth = if (s.centerWidthDp > 0) s.centerWidthDp * d else h * 3.375f,
                // HIG の寸法図: 中身は島の端から 0.35〜0.47H
                sidePad = h * 0.40f,
                // 映像: 展開時の幅 373.2pt（画面 393pt）→ 左右 9.9pt = 0.265H
                margin = h * 0.265f,
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
