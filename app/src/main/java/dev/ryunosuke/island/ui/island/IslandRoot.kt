package dev.ryunosuke.island.ui.island

import android.graphics.Rect
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as GeoRect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.ryunosuke.island.island.ActionTarget
import dev.ryunosuke.island.island.CallActivity
import dev.ryunosuke.island.island.IslandAction
import dev.ryunosuke.island.island.IslandActivity
import dev.ryunosuke.island.island.IslandAlert
import dev.ryunosuke.island.island.IslandPresentation
import dev.ryunosuke.island.ui.island.content.AlertContent
import dev.ryunosuke.island.ui.island.content.CompactContent
import dev.ryunosuke.island.ui.island.content.ContentActions
import dev.ryunosuke.island.ui.island.content.ExpandedContent
import dev.ryunosuke.island.ui.island.content.MinimalAttachedContent
import dev.ryunosuke.island.ui.island.content.MinimalContent
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt

class IslandCallbacks(
    val onTap: (IslandActivity) -> Unit,
    val onLongPress: (IslandActivity) -> Unit,
    val onSwipe: (IslandActivity) -> Unit,
    val onCollapse: () -> Unit,
    val onTouched: () -> Unit,
    val onAction: (IslandAction) -> Unit,
    val onTarget: (ActionTarget) -> Unit,
    /**
     * 一時表示をタップした（電池残量低下なら省電力をオンにする、イヤホンなら電池の内訳を開く）。
     * 開ける一時表示（[IslandAlert.Device.expandable]）は長押しでもこれを呼ぶ
     */
    val onAlertTap: (IslandAlert) -> Unit,
    /** 展開していない島を下へスワイプした（通知シェードを開く） */
    val onPullDown: () -> Unit,
    /** しまったものを戻す（しまったあとに残る待機時の島をタップ・左右にスワイプした） */
    val onRestore: () -> Unit,
    /** 触れる範囲（窓の座標）。空なら全部素通し */
    val onTouchRegion: (List<Rect>) -> Unit,
)

// ---- 何を出すか ----

private sealed interface Slot {
    val id: String
}

private data object HiddenSlot : Slot {
    override val id = "hidden"
}

/** 何もないときの島（設定でオンのときだけ。iPhone ではこれが常に見えている） */
private data object IdleSlot : Slot {
    override val id = "idle"
}

/**
 * split = 2 つ同時。主はカメラ部分の左に小さな印だけを出し、右端をカメラ部分にそろえる
 * （iPhone の「minimal attached」）。2 つ目は右に離れた島に出る。
 */
private data class CompactSlot(val a: IslandActivity, val split: Boolean) : Slot {
    override val id get() = "c:${a.key}:${variantOf(a)}:${if (split) "split" else ""}"
}

private data class ExpandedSlot(val a: IslandActivity) : Slot {
    override val id get() = "e:${a.key}:${variantOf(a)}"
}

private data class AlertSlot(val alert: IslandAlert) : Slot {
    // 電池残量低下は省電力がオンになっても入れ替えず、同じ表示の中で色を変える。
    // イヤホンも、あとから届いた電池は同じ表示の中で書き換える（入れ替えるのは内訳を開いたときだけ）
    override val id get() = when (alert) {
        is IslandAlert.LowBattery -> "a:${alert.type}"
        is IslandAlert.Device -> "a:${alert.type}:${alert.address ?: alert.name}:${alert.detail}"
        else -> "a:$alert"
    }
}

/** 同じ活動でも見た目が大きく変わるとき（着信→通話中）は入れ替えのアニメーションをかける */
private fun variantOf(a: IslandActivity) = when (a) {
    is CallActivity -> if (a.incoming) "in" else "on"
    else -> a.kind.name
}

/** 大きく開いた形で出す一時表示（iOS 26 の電池残量低下、イヤホンの電池の内訳） */
private fun IslandAlert.isExpanded() = this is IslandAlert.LowBattery || (this is IslandAlert.Device && detail)

/** タップ・長押しで開ける一時表示（イヤホンの電池。左右とケースの内訳が分かっているとき） */
private fun IslandAlert.expandable() = (this as? IslandAlert.Device)?.expandable == true

/** 押している間に膨らむ状態（iPhone でも展開していない島だけが膨らむ。開けるイヤホンの電池も同じ） */
private fun Slot.pressable() = this is IdleSlot || this is CompactSlot || (this is AlertSlot && alert.expandable())

// ---- 形の目標値 ----

private data class Geo(
    val l: Float, val t: Float, val r: Float, val b: Float,
    val radius: Float, val exponent: Float,
    /** 右の島の中心と、半分の幅・高さ */
    val bx: Float, val by: Float, val bw: Float, val bh: Float,
    val shadow: Float, val alpha: Float,
)

private fun targetGeo(slot: Slot, measured: IntSize?, m: IslandMetrics): Geo {
    val h = m.height
    val s = m.sensorWidth
    val l: Float
    val r: Float
    val t: Float
    val b: Float
    var radius = h / 2
    var exponent = 2f
    var split = false
    var expanded = false
    when (slot) {
        HiddenSlot -> {
            // カメラ穴の中に縮んで消える
            val rr = m.holeRadius * 0.8f
            l = m.cx - rr; r = m.cx + rr; t = m.cy - rr; b = m.cy + rr; radius = rr
        }
        IdleSlot -> {
            l = m.cx - s / 2; r = m.cx + s / 2; t = m.top; b = t + h
        }
        is CompactSlot -> if (slot.split) {
            split = true
            // 中身の無い見本と比べるときは、見本と同じ 0.6H だけ伸ばす
            val lead = if (slot.a is dev.ryunosuke.island.island.ReferenceActivity) h * 0.6f else m.minimalLead
            l = m.cx - s / 2 - lead; r = m.cx + s / 2; t = m.top; b = t + h
        } else {
            val w = maxOf(measured?.width?.toFloat() ?: (s + 2 * (m.sidePad + 0.6f * h)), s)
            l = m.cx - w / 2; r = m.cx + w / 2; t = m.top; b = t + h
        }
        is AlertSlot -> if (slot.alert.isExpanded()) {
            expanded = true
            val eh = measured?.height?.toFloat() ?: (2.8f * h)
            l = m.margin; r = m.width - m.margin; t = m.top; b = t + eh
        } else {
            val w = maxOf(measured?.width?.toFloat() ?: (s + 2 * (m.sidePad + 0.6f * h)), s)
            l = m.cx - w / 2; r = m.cx + w / 2; t = m.top; b = t + h
        }
        is ExpandedSlot -> {
            expanded = true
            val eh = measured?.height?.toFloat() ?: (3.5f * h)
            l = m.margin; r = m.width - m.margin; t = m.top; b = t + eh
        }
    }
    if (expanded) {
        radius = minOf(m.expandedCornerExtent, (b - t) / 2); exponent = m.expandedCornerExponent
    }
    // 右の島。出ていないときは本体の右端の中に大きさ 0 で隠れている（そこから粘って出てくる）
    val bw = if (split) m.detachedWidth / 2 else 0f
    val bh = if (split) h / 2 else 0f
    val bx = if (split) r + m.detachedGap + bw else r - h / 2
    return Geo(
        l, t, r, b, radius, exponent, bx, m.cy, bw, bh,
        shadow = if (expanded) 1f else 0f,
        alpha = if (slot == HiddenSlot) 0f else 1f,
    )
}

/** 幅と高さに別々のバネを使う。値は Apple の映像から当てはめたもの（IslandMotion） */
private class Motion(
    val width: SpringSpec<Float>,
    val height: SpringSpec<Float>,
    /** 左端だけ別のバネ（2 つ同時になるとき） */
    val left: SpringSpec<Float> = width,
)

private class GeoAnim(g: Geo) {
    val l = Animatable(g.l)
    val t = Animatable(g.t)
    val r = Animatable(g.r)
    val b = Animatable(g.b)
    val radius = Animatable(g.radius)
    val exponent = Animatable(g.exponent)
    val bx = Animatable(g.bx)
    val by = Animatable(g.by)
    val bw = Animatable(g.bw)
    val bh = Animatable(g.bh)
    val shadow = Animatable(g.shadow)
    val alpha = Animatable(g.alpha)

    /** 右の島との首の太さ（1 = 島の高さいっぱい、0 = ちぎれた） */
    val neck = Animatable(0f)

    /** 押している間の膨らみ（0〜1）。中心を保ったまま幅 +13%・高さ +15% になる */
    val press = Animatable(0f)

    suspend fun animateTo(g: Geo, m: Motion, im: IslandMotion) = coroutineScope {
        launch { l.animateTo(g.l, m.left) }
        launch { r.animateTo(g.r, m.width) }
        launch { t.animateTo(g.t, m.height) }
        launch { b.animateTo(g.b, m.height) }
        launch { radius.animateTo(g.radius, m.height) }
        launch { exponent.animateTo(g.exponent, m.height) }
        val detaching = g.bh > 0f && bh.targetValue <= 0f
        val absorbing = g.bh <= 0f && bh.targetValue > 0f
        val out = g.bh > 0f
        launch { bx.animateTo(g.bx, if (out) im.detachedPosition else im.detachedAbsorb) }
        launch { by.animateTo(g.by, if (out) im.detachedPosition else im.detachedAbsorb) }
        launch { bw.animateTo(g.bw, if (out) im.detachedWidth else im.detachedAbsorb) }
        launch { bh.animateTo(g.bh, if (out) im.detachedHeight else im.detachedAbsorb) }
        if (detaching) {
            launch {
                neck.snapTo(1f)
                neck.animateTo(
                    0f,
                    androidx.compose.animation.core.keyframes {
                        durationMillis = im.ms(IslandMotion.NECK_MS)
                        1f at im.ms(IslandMotion.NECK_HOLD_MS) using androidx.compose.animation.core.FastOutSlowInEasing
                        IslandMotion.NECK_THIN at im.ms(IslandMotion.NECK_THIN_MS) using androidx.compose.animation.core.LinearEasing
                    },
                )
            }
        } else if (absorbing) {
            launch { neck.snapTo(0f) }
        }
        launch { shadow.animateTo(g.shadow, im.tween(200)) }
        launch {
            // 出るときはすぐ見せ、消えるときは穴に縮みきってから透明にする
            if (g.alpha > alpha.value) alpha.snapTo(g.alpha) else alpha.animateTo(g.alpha, im.tween(110, delay = 190))
        }
    }

    /**
     * 押して膨らんだ大きさを形そのものに移し、膨らみを 0 に戻す。長押しで展開するときに、
     * 膨らみが縮むバネと展開のバネが重なって幅が一瞬へこむのを防ぐ（見本も膨らんだ大きさから続けて開く）
     */
    suspend fun absorbPress() {
        val p = press.value
        if (p <= 0.001f) return
        val m = mainRect()
        val rad = radius.value * (1 + IslandMotion.PRESS_GROW_H * p)
        press.snapTo(0f)
        l.snapTo(m.left); r.snapTo(m.right); t.snapTo(m.top); b.snapTo(m.bottom); radius.snapTo(rad)
    }

    /** 押している間の膨らみを足した本体の矩形 */
    fun mainRect(): GeoRect {
        val p = press.value
        val dw = (r.value - l.value) * IslandMotion.PRESS_GROW_W / 2 * p
        val dh = (b.value - t.value) * IslandMotion.PRESS_GROW_H / 2 * p
        return GeoRect(l.value - dw, t.value - dh, r.value + dw, b.value + dh)
    }

    fun frame(h: Float): ShapeFrame {
        // 首の太さ 1 = 島の高さいっぱい（見た目は 1 本の島）。つなぎ目のなめらかさは首が細るほど強くし、
        // 太いとき（同じ太さの形どうしを丸めると縁にこぶが出る）と、ちぎれたあと（止まっている右の島の
        // 向かい合う縁が膨らむ）は普通の和集合にする
        val n = neck.value.coerceIn(0f, 1f)
        val k = if (n > 0.01f) IslandMotion.NECK_SMOOTH * h * (1f - n) else 0f
        val main = mainRect()
        return ShapeFrame(
            main.left, main.top, main.right, main.bottom,
            radius.value * (1 + IslandMotion.PRESS_GROW_H * press.value), exponent.value.coerceAtLeast(2f),
            bx.value, by.value, bw.value.coerceAtLeast(0f), bh.value.coerceAtLeast(0f),
            neck = if (n > 0.01f) h / 2 * n else 0f, smoothK = k,
            shadow = shadow.value, alpha = alpha.value,
        )
    }
}

/**
 * 動きの値。iOS 26（Beta 4 / Beta 5）の島の映像を 1 コマずつ測り、減衰するバネを当てはめたもの。
 * iOS 17 の頃（WWDC23 の見本）より 1.5〜1.6 倍速く、ほとんど弾まない（行き過ぎ 1% 前後）。
 * 横に広がるときに高さが膨らむ動きも無くなり、幅と高さは同時に動き出す。
 * SwiftUI の response / dampingFraction で書き、Compose の stiffness = (2π / response)²（質量 1）に直す。
 *
 * speed は設定の「アニメーションの速さ」。すべての時間を 1 / speed 倍にする。
 */
class IslandMotion(val speed: Float = 1f) {
    private val k = speed.coerceIn(0.25f, 4f)

    private fun s(damping: Float, response: Float) = spring(
        dampingRatio = damping,
        stiffness = (2 * PI / (response / k)).let { (it * it).toFloat() },
        visibilityThreshold = 0.5f,
    )

    /** ミリ秒を速さに合わせて伸び縮みさせる */
    fun ms(v: Int) = (v / k).roundToInt()

    fun <T> tween(durationMs: Int, delay: Int = 0, easing: androidx.compose.animation.core.Easing = androidx.compose.animation.core.FastOutSlowInEasing) =
        androidx.compose.animation.core.tween<T>(ms(durationMs), ms(delay), easing)

    /** 待機・コンパクト → コンパクト（幅）。Beta 4: 待機→横長のコンパクト ζ 0.82 / 0.505 秒 */
    val toCompactWidth = s(0.82f, 0.505f)

    /** 高さを島 1 つ分に戻す。上端が飛び出さないように弾ませない */
    val toCompactHeight = s(1.0f, 0.42f)

    /** コンパクト → 待機（幅）。弾まない */
    val toIdleWidth = s(1.0f, 0.42f)

    /** カメラ穴から出てくる（一度目の表示）。上に跳ねないよう高さは弾ませない */
    val appearWidth = s(0.86f, 0.45f)
    val appearHeight = s(1.0f, 0.34f)

    /**
     * 待機・一時表示 → 展開。Beta 5 の電池残量低下（高さ 1H → 2.8H、幅 3.58H → 11.14H）:
     * 幅 ζ 0.84 / 0.485 秒、高さ ζ 0.81 / 0.51 秒（誤差 1.3pt・0.6pt）
     */
    val fromIdleExpandWidth = s(0.84f, 0.485f)
    val fromIdleExpandHeight = s(0.81f, 0.51f)

    /** 展開 → 待機。Beta 5: 幅 ζ 0.87 / 0.465 秒、高さ ζ 0.92 / 0.45 秒（誤差 1.6pt・0.7pt） */
    val toIdleCollapseWidth = s(0.87f, 0.465f)
    val toIdleCollapseHeight = s(0.92f, 0.45f)

    /** コンパクト → 展開。Beta 4: 幅 ζ 0.79 / 0.605 秒、高さ ζ 0.83 / 0.365 秒 */
    val toExpandedWidth = s(0.79f, 0.605f)
    val toExpandedHeight = s(0.83f, 0.365f)

    /** 展開 → コンパクト。Beta 4: 幅 ζ 0.78 / 0.53 秒、高さ ζ 0.79 / 0.525 秒 */
    val fromExpandedWidth = s(0.78f, 0.53f)
    val fromExpandedHeight = s(0.79f, 0.525f)

    /**
     * 2 つ同時になるとき（iOS 26 の見本が無いので WWDC23 の見本の値を 0.65 倍の時間にしたもの）:
     * 本体の左端はゆっくり大きく弾む。右の島は塊ごと行き過ぎてからちぎれる
     */
    val splitLead = s(0.312f, 1.334f * SPLIT_TIME)
    val detachedPosition = s(0.356f, 0.907f * SPLIT_TIME)

    /** 右の島の幅は弾む（見本でも動いている間は止まったときより横に広い: 1.23H → 1.18H） */
    val detachedWidth = s(0.339f, 0.928f * SPLIT_TIME)

    /** 右の島の高さは弾ませない（見本では右側の高さが終始本体と同じ） */
    val detachedHeight = s(1.0f, 0.3f * SPLIT_TIME)

    /** 右の島が本体に吸い込まれるとき（見本に無いので弾ませない） */
    val detachedAbsorb = s(0.9f, 0.5f * SPLIT_TIME)

    /** 押したときの膨らみ（Beta 4: 長押しで展開する前の 0.2 秒で幅 +13%・高さ +15%）と、離したときの戻り */
    val pressIn = tween<Float>(PRESS_MS, easing = androidx.compose.animation.core.LinearOutSlowInEasing)
    val pressOut = s(0.62f, 0.32f)

    companion object {
        const val SPLIT_TIME = 0.65f

        /**
         * 右の島との首。見本では、始めのうちはくびれのない 1 本の島として右へ伸び、
         * そこからくびれて（太さ 3 割）、ぷつりとちぎれる（WWDC23 の見本の 0.65 倍の時間）
         */
        const val NECK_HOLD_MS = 234
        const val NECK_THIN_MS = 442
        const val NECK_MS = 455
        const val NECK_THIN = 0.3f
        const val NECK_SMOOTH = 0.35f

        const val PRESS_GROW_W = 0.13f
        const val PRESS_GROW_H = 0.15f
        const val PRESS_MS = 260
    }
}

private fun Slot.isExpandedShape() = this is ExpandedSlot || (this is AlertSlot && alert.isExpanded())

private fun motionFor(from: Slot, to: Slot, im: IslandMotion): Motion = when {
    from == HiddenSlot && to != HiddenSlot ->
        if (to.isExpandedShape()) Motion(im.fromIdleExpandWidth, im.appearHeight) else Motion(im.appearWidth, im.appearHeight)
    to.isExpandedShape() && !from.isExpandedShape() ->
        // 待機・一時表示からは速く、コンパクトからは少しゆっくり開く（iOS 26 の見本どおり）。
        // イヤホンの電池を押して内訳を開くのは、コンパクトからと同じ
        if (from is CompactSlot || (from is AlertSlot && from.alert.expandable())) Motion(im.toExpandedWidth, im.toExpandedHeight)
        else Motion(im.fromIdleExpandWidth, im.fromIdleExpandHeight)
    from.isExpandedShape() && !to.isExpandedShape() ->
        if (to is CompactSlot) Motion(im.fromExpandedWidth, im.fromExpandedHeight)
        else Motion(im.toIdleCollapseWidth, im.toIdleCollapseHeight)
    to is CompactSlot && to.split && !(from is CompactSlot && from.split) ->
        Motion(im.toCompactWidth, im.toCompactHeight, left = im.splitLead)
    (to is IdleSlot || to == HiddenSlot) && from !is IdleSlot -> Motion(im.toIdleWidth, im.toCompactHeight)
    else -> Motion(im.toCompactWidth, im.toCompactHeight)
}

// ---- 本体 ----

/** 中身から今の動きの速さを読む（錠前が開く動きなど） */
val LocalIslandMotion = staticCompositionLocalOf { IslandMotion() }

@Composable
fun IslandRoot(
    presentation: IslandPresentation,
    visible: Boolean,
    idleVisible: Boolean,
    metrics: IslandMetrics,
    callbacks: IslandCallbacks,
    animationSpeed: Float = 1f,
) {
    val im = remember(animationSpeed) { IslandMotion(animationSpeed) }
    val slot: Slot = when {
        !visible -> HiddenSlot
        presentation.alert != null -> AlertSlot(presentation.alert)
        // スワイプでしまったものがあるときは、設定で待機時の島を消していても島を残す（iPhone と同じく、そこから戻せる）
        presentation.primary == null -> if (idleVisible || presentation.hidden > 0) IdleSlot else HiddenSlot
        presentation.expanded -> ExpandedSlot(presentation.primary)
        else -> CompactSlot(presentation.primary, split = presentation.secondary != null)
    }
    val bubbleActivity = if (slot is CompactSlot && slot.split) presentation.secondary else null

    val sizes = remember { mutableStateMapOf<String, IntSize>() }
    val target = targetGeo(slot, sizes[slot.id], metrics)
    val anim = remember { GeoAnim(targetGeo(HiddenSlot, null, metrics)) }

    val previous = remember { mutableStateOf<Slot>(HiddenSlot) }
    val lastMotion = remember { mutableStateOf<Motion?>(null) }
    val scope = rememberCoroutineScope()
    val pressJob = remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(target, im) {
        // 膨らんだまま展開などに移るときは、膨らんだ大きさから続けて動かす
        if (!slot.pressable() && anim.press.value > 0.001f) {
            pressJob.value?.cancel()
            anim.absorbPress()
        }
        // 中身の大きさで形が決まる状態は、実寸が測れるまで 1 コマ待ってから動き出す
        // （見積もりで動き出して次のコマで目標が変わると、軌道に折れ目が入る）
        val needsMeasure = slot is AlertSlot || slot is ExpandedSlot || (slot is CompactSlot && !slot.split)
        if (needsMeasure && sizes[slot.id] == null) return@LaunchedEffect
        // 同じ状態のまま目標だけ変わる（中身の実寸が 1 コマ遅れて届くなど）ときは、その遷移のバネを使い続ける。
        // ここでコンパクト用のバネに切り替えると、展開の途中で動きが変わる
        val motion = lastMotion.value.takeIf { previous.value.id == slot.id }
            ?: motionFor(previous.value, slot, im).also { lastMotion.value = it }
        previous.value = slot
        anim.animateTo(target, motion, im)
    }

    // 触れる範囲を窓に伝える。島の外は下のアプリに素通しする。
    // 待機時の島も触れるようにする（素通しにすると、長押しがステータスバーに届いて通知シェードが開いてしまう）
    val slotState = rememberUpdatedState(slot)
    LaunchedEffect(Unit) {
        snapshotFlow {
            val f = anim.frame(metrics.height)
            val s = slotState.value
            if (s == HiddenSlot || f.alpha < 0.5f) {
                emptyList()
            } else buildList {
                add(Rect(f.left.toInt(), f.top.toInt(), f.right.roundToInt(), f.bottom.roundToInt()))
                if (f.bubbleHalfH > metrics.height * 0.3f) {
                    add(
                        Rect(
                            (f.bubbleX - f.bubbleHalfW).toInt(), (f.bubbleY - f.bubbleHalfH).toInt(),
                            (f.bubbleX + f.bubbleHalfW).roundToInt(), (f.bubbleY + f.bubbleHalfH).roundToInt(),
                        ),
                    )
                }
            }
        }.distinctUntilChanged().collect { callbacks.onTouchRegion(it) }
    }

    // 押している間の膨らみ。長押しが決まる瞬間に膨らみきるように、押してから少し待って膨らませ始める
    val longPressMs = LocalViewConfiguration.current.longPressTimeoutMillis
    val onPress: (Boolean) -> Unit = remember(im, longPressMs) {
        { down ->
            pressJob.value?.cancel()
            pressJob.value = scope.launch {
                if (down && slotState.value.pressable()) {
                    delay((longPressMs - IslandMotion.PRESS_MS).coerceAtLeast(0))
                    anim.press.animateTo(1f, im.pressIn)
                } else if (anim.press.value != 0f) {
                    anim.press.animateTo(0f, im.pressOut)
                }
            }
        }
    }

    val act = remember(callbacks) { ContentActions(callbacks.onAction, callbacks.onTarget) }
    val haptic = LocalHapticFeedback.current
    val currentPrimary = rememberUpdatedState(presentation.primary)
    val expandedState = rememberUpdatedState(presentation.expanded)
    val hiddenState = rememberUpdatedState(presentation.hidden)
    CompositionLocalProvider(LocalIslandMotion provides im) {
        Box(Modifier.fillMaxSize()) {
            IslandShapeCanvas(Modifier.fillMaxSize(), metrics.density) { anim.frame(metrics.height) }

            // 本体。押している間の膨らみは、中身ごと拡大して見せる
            Box(
                Modifier
                    .animatedBounds { GeoRect(anim.l.value, anim.t.value, anim.r.value, anim.b.value) }
                    .graphicsLayer {
                        val p = anim.press.value
                        scaleX = 1 + IslandMotion.PRESS_GROW_W * p
                        scaleY = 1 + IslandMotion.PRESS_GROW_H * p
                        transformOrigin = TransformOrigin.Center
                        alpha = anim.alpha.value
                        clip = true
                        shape = SquircleShape(anim.radius.value, anim.exponent.value.coerceAtLeast(2f))
                    }
                    .pointerInput(slot.id) {
                        islandGestures(
                            activity = { if (slotState.value is AlertSlot) null else currentPrimary.value },
                            expanded = { expandedState.value || slotState.value.isExpandedShape() },
                            onDown = callbacks.onTouched,
                            onPress = onPress,
                            onTap = { a ->
                                val s = slotState.value
                                when {
                                    s is AlertSlot -> callbacks.onAlertTap(s.alert)
                                    a != null -> callbacks.onTap(a)
                                    s is IdleSlot && hiddenState.value > 0 -> callbacks.onRestore()
                                }
                            },
                            onLongPress = {
                                val s = slotState.value
                                if (s is AlertSlot) {
                                    // 開ける一時表示（イヤホンの電池）は、長押しでもタップと同じく開く
                                    if (s.alert.expandable()) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        callbacks.onAlertTap(s.alert)
                                    }
                                } else if (!expandedState.value && it != null) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    callbacks.onLongPress(it)
                                }
                            },
                            // 一時表示は横にも上にもスワイプで引っ込める。しまったあとの待機時の島を左右にスワイプすると戻る
                            onSwipe = { a ->
                                val s = slotState.value
                                when {
                                    s is AlertSlot -> callbacks.onCollapse()
                                    a != null -> callbacks.onSwipe(a)
                                    s is IdleSlot && hiddenState.value > 0 -> callbacks.onRestore()
                                }
                            },
                            onSwipeUp = callbacks.onCollapse,
                            onSwipeDown = callbacks.onPullDown,
                        )
                    },
            ) {
                AnimatedContent(
                    targetState = slot,
                    contentKey = { it.id },
                    contentAlignment = Alignment.TopCenter,
                    // iOS 26 の見本: 新しい中身は動き出しと同時に強くぼけた状態で現れ、島と一緒に大きくなりながら
                    // 0.27 秒でぼけが消える。古い中身はぼけながらすぐ消える
                    transitionSpec = {
                        (
                            fadeIn(im.tween(150, easing = LinearEasing)) togetherWith
                                fadeOut(im.tween(110, easing = LinearEasing))
                            ).using(null)
                    },
                    modifier = Modifier.wrapContentSize(Alignment.TopCenter, unbounded = true),
                    label = "island",
                ) { entry ->
                    // 同じ活動の中身の更新（再生→停止など）は入れ替えずに最新の値で描く
                    val s = if (entry.id == slot.id) slot else entry
                    val entering = transition.targetState == EnterExitState.Visible
                    val blur by transition.animateFloat(
                        transitionSpec = { if (targetState == EnterExitState.Visible) im.tween(270, easing = LinearEasing) else im.tween(110) },
                        label = "blur",
                    ) { if (it == EnterExitState.Visible) 0f else BLUR_DP }
                    Box(
                        Modifier
                            .onSizeChanged { sizes[s.id] = it }
                            .graphicsLayer {
                                // 島が小さい間は中身も一緒に縮める（島と一緒に大きくなりながら現れる）。
                                // 消えていく中身は、島が大きくなるならそれに合わせて引き伸ばす
                                val cw = anim.r.value - anim.l.value
                                val ch = anim.b.value - anim.t.value
                                val sc = if (size.width > 0 && size.height > 0) {
                                    val fit = minOf(cw / size.width, ch / size.height)
                                    if (entering) fit.coerceIn(0.3f, 1f) else fit.coerceIn(0.3f, 1.4f)
                                } else 1f
                                scaleX = sc
                                scaleY = sc
                                transformOrigin = TransformOrigin(0.5f, 0f)
                                renderEffect = if (blur > 0.5f) BlurEffect(blur * density, blur * density) else null
                            },
                    ) {
                        when (s) {
                            HiddenSlot, IdleSlot -> Spacer(Modifier.size(1.dp))
                            is CompactSlot -> if (s.split) MinimalAttachedContent(s.a, metrics) else CompactContent(s.a, metrics)
                            is AlertSlot -> AlertContent(s.alert, metrics)
                            is ExpandedSlot -> ExpandedContent(s.a, metrics, act)
                        }
                    }
                }
            }

            // 右に離れた島（2 つ目の活動）
            val lastBubble = remember { mutableStateOf<IslandActivity?>(null) }
            if (bubbleActivity != null) lastBubble.value = bubbleActivity
            val shownBubble = lastBubble.value
            if (shownBubble != null) {
                val bubbleState = rememberUpdatedState(shownBubble)
                Box(
                    Modifier
                        .animatedBounds {
                            val hw = metrics.detachedWidth / 2
                            val hh = metrics.height / 2
                            GeoRect(anim.bx.value - hw, anim.by.value - hh, anim.bx.value + hw, anim.by.value + hh)
                        }
                        .graphicsLayer {
                            val s = (anim.bh.value / (metrics.height / 2)).coerceIn(0f, 1f)
                            scaleX = s
                            scaleY = s
                            alpha = anim.alpha.value * s
                            clip = true
                            shape = RoundedCornerShape(50)
                        }
                        .pointerInput(shownBubble.key) {
                            islandGestures(
                                activity = { bubbleState.value },
                                expanded = { false },
                                onDown = callbacks.onTouched,
                                onPress = {},
                                onTap = { a -> if (a != null) callbacks.onTap(a) },
                                onLongPress = {
                                    if (it != null) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        callbacks.onLongPress(it)
                                    }
                                },
                                onSwipe = { a -> if (a != null) callbacks.onSwipe(a) },
                                onSwipeUp = {},
                                onSwipeDown = callbacks.onPullDown,
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(
                        targetState = shownBubble,
                        contentKey = { it.key },
                        transitionSpec = { (fadeIn(im.tween(200)) togetherWith fadeOut(im.tween(120))).using(null) },
                        label = "bubble",
                    ) { entry ->
                        val latest = if (entry.key == bubbleActivity?.key) bubbleActivity else entry
                        MinimalContent(latest, metrics)
                    }
                }
            }
        }
    }
}

/** 中身が入れ替わるときのぼかしの強さ（dp） */
private const val BLUR_DP = 14f

/** 窓の中の任意の矩形に置く。値はアニメーション中に毎フレーム読むので、再コンポーズせず配置だけやり直す */
private fun Modifier.animatedBounds(rect: () -> GeoRect) =
    this
        .offset { val r = rect(); IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
        .layout { measurable, _ ->
            val r = rect()
            val w = r.width.roundToInt().coerceAtLeast(0)
            val h = r.height.roundToInt().coerceAtLeast(0)
            val p = measurable.measure(Constraints.fixed(w, h))
            layout(w, h) { p.place(0, 0) }
        }

/**
 * iPhone と同じ操作: タップで開く、長押しで展開、左右スワイプでしまう、展開中の上スワイプで畳む。
 * 下へのスワイプは通知シェードを開く（島がステータスバーの上にあるので、そのままでは島の所から引き下ろせない）。
 * 中のボタンが消費した操作には反応しない。島の上で始まった操作はすべてここで受け、下のステータスバーには渡さない。
 */
private suspend fun PointerInputScope.islandGestures(
    activity: () -> IslandActivity?,
    expanded: () -> Boolean,
    onDown: () -> Unit,
    onPress: (Boolean) -> Unit,
    onTap: (IslandActivity?) -> Unit,
    onLongPress: (IslandActivity?) -> Unit,
    onSwipe: (IslandActivity?) -> Unit,
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit,
) {
    val slop = viewConfiguration.touchSlop
    val swipeDistance = 36.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onDown()
        onPress(true)
        val a = activity()
        var total = Offset.Zero
        // 0: 未定, 1: タップ, 2: ドラッグ, 3: 子が消費, 4: 指が消えた
        var outcome = 0
        try {
            withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val ev = awaitPointerEvent()
                    val c = ev.changes.firstOrNull { it.id == down.id }
                    if (c == null) { outcome = 4; break }
                    if (c.isConsumed) { outcome = 3; break }
                    total += c.positionChange()
                    if (total.getDistance() > slop) { outcome = 2; break }
                    if (!c.pressed) { outcome = 1; break }
                }
            }
            // 離した・動かしたときはすぐ膨らみを戻す。長押しは指を離すまで膨らんだまま
            // （展開するときは、膨らんだ大きさがそのまま展開の始まりになる: GeoAnim.absorbPress）
            if (outcome != 0) onPress(false)
            when (outcome) {
                0 -> {
                    onLongPress(a)
                    // 指が離れるまで何もしない
                    while (true) {
                        val ev = awaitPointerEvent()
                        ev.changes.forEach { it.consume() }
                        if (ev.changes.none { it.pressed }) break
                    }
                }
                1 -> onTap(a)
                2 -> {
                    // 下へ引き始めたら、離すのを待たずに通知シェードを開く。上端から下へ引く指は、
                    // 30px ほど動いたところでシステムに横取りされる（こちらには取り消しが届き、シェードも開かない）
                    if (!expanded() && total.y > abs(total.x)) {
                        onSwipeDown()
                        while (true) {
                            val ev = awaitPointerEvent()
                            if (ev.changes.none { it.pressed }) break
                        }
                        return@awaitEachGesture
                    }
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (c.isConsumed) return@awaitEachGesture
                        total += c.positionChange()
                        if (!c.pressed) break
                    }
                    when {
                        abs(total.x) > swipeDistance && abs(total.x) > abs(total.y) -> onSwipe(a)
                        total.y < -swipeDistance && expanded() -> onSwipeUp()
                    }
                }
            }
        } finally {
            // 指を離した・取り消された（膨らみがすでに形へ移っていれば何もしない）
            onPress(false)
        }
    }
}
