package dev.ryunosuke.island.ui.island.content

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ryunosuke.island.island.CallActivity
import dev.ryunosuke.island.island.IslandAction
import dev.ryunosuke.island.island.IslandActivity
import dev.ryunosuke.island.island.Kind
import dev.ryunosuke.island.island.LiveActivity
import dev.ryunosuke.island.island.MediaActivity
import dev.ryunosuke.island.island.NavigationActivity
import dev.ryunosuke.island.island.ReferenceActivity
import dev.ryunosuke.island.island.RingingActivity
import dev.ryunosuke.island.island.StopwatchActivity
import dev.ryunosuke.island.island.TimeFormat
import dev.ryunosuke.island.island.TimerActivity
import dev.ryunosuke.island.ui.island.IslandColors
import dev.ryunosuke.island.ui.island.IslandIcons
import dev.ryunosuke.island.ui.island.IslandMetrics
import dev.ryunosuke.island.ui.island.IslandText
import kotlin.math.roundToInt

/** 島の中身に渡す操作 */
class ContentActions(
    val onAction: (IslandAction) -> Unit,
    val onTarget: (dev.ryunosuke.island.island.ActionTarget) -> Unit,
)

// ---- 配置の骨組み ----

/**
 * コンパクト表示の骨組み。左はカメラの左、右はカメラの右に寄せ、幅は広い方に揃えて左右対称にする
 * （島はカメラを中心に置くので、対称でないとカメラがずれて見える）。
 * 設定で幅を決めていれば、その幅の両端に寄せる（[IslandMetrics.compactWidth]）。
 */
@Composable
fun CompactRow(m: IslandMetrics, leading: @Composable () -> Unit, trailing: @Composable () -> Unit) {
    Layout(contents = listOf(leading, trailing)) { (lm, tm), _ ->
        val loose = Constraints()
        val lp = lm.map { it.measure(loose) }
        val tp = tm.map { it.measure(loose) }
        val side = maxOf(lp.maxOfOrNull { it.width } ?: 0, tp.maxOfOrNull { it.width } ?: 0)
        val pad = m.sidePad.roundToInt()
        val h = m.height.roundToInt()
        val w = m.compactWidth((side + pad).toFloat()).roundToInt()
        layout(w, h) {
            lp.forEach { it.place(pad, (h - it.height) / 2) }
            tp.forEach { it.place(w - pad - it.width, (h - it.height) / 2) }
        }
    }
}

@Composable
fun IslandMetrics.heightDp(): Dp = with(LocalDensity.current) { height.toDp() }

@Composable
fun IslandMetrics.expandedWidthDp(): Dp = with(LocalDensity.current) { expandedWidth.toDp() }

/** コンパクト時の小さな絵の大きさ。HIG の寸法図では中身の高さが 0.53H */
@Composable
fun IslandMetrics.glyphDp(): Dp = (heightDp() * 0.55f).coerceIn(16.dp, 26.dp)

// ---- コンパクト ----

@Composable
fun CompactContent(a: IslandActivity, m: IslandMetrics) {
    val g = m.glyphDp()
    when (a) {
        is MediaActivity -> CompactRow(
            m,
            leading = { Artwork(a.art, g, 6.dp) },
            trailing = { Waveform(Color(a.accent), a.playing, Modifier.size(g, g * 0.72f), audio = true) },
        )
        is CallActivity -> if (a.incoming) {
            CompactRow(
                m,
                leading = { Glyph(IslandIcons.Phone, IslandColors.Green, g * 0.85f) },
                trailing = {
                    Text(a.name, style = IslandText.compact, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 96.dp))
                },
            )
        } else {
            CompactRow(
                m,
                leading = { CallLeading(a, g) },
                trailing = { Waveform(IslandColors.Green, true, Modifier.size(g, g * 0.72f)) },
            )
        }
        is TimerActivity -> CompactRow(
            m,
            leading = { TimerRing(a, g) },
            trailing = { TimerTime(a) },
        )
        is StopwatchActivity -> CompactRow(
            m,
            leading = { Glyph(IslandIcons.Stopwatch, IslandColors.Orange, g) },
            trailing = { StopwatchTime(a) },
        )
        is RingingActivity -> CompactRow(
            m,
            leading = { Glyph(if (a.isTimer) IslandIcons.Timer else IslandIcons.Alarm, IslandColors.Orange, g) },
            trailing = { Text(a.detail ?: a.title, style = IslandText.compact, color = IslandColors.Orange, maxLines = 1) },
        )
        is NavigationActivity -> CompactRow(
            m,
            leading = { ManeuverIcon(a, g) },
            trailing = {
                Text(a.distance ?: "", style = IslandText.compact, maxLines = 1, modifier = Modifier.widthIn(max = 80.dp))
            },
        )
        is LiveActivity -> CompactRow(
            m,
            leading = { LiveLeading(a, g) },
            trailing = { LiveTrailing(a, g) },
        )
        is ReferenceActivity -> {
            // 見本と同じ幅になるように、左右に空きだけを置く
            val side = with(LocalDensity.current) {
                ((a.compactWidthH * m.height - m.sensorWidth) / 2 - m.sidePad).coerceAtLeast(0f).toDp()
            }
            CompactRow(m, leading = { Spacer(Modifier.size(side, 1.dp)) }, trailing = { Spacer(Modifier.size(side, 1.dp)) })
        }
    }
}

// ---- 2 つ同時のとき ----

/** 右に離れた島（幅 1.24H × 高さ H）の中身 */
@Composable
fun MinimalContent(a: IslandActivity, m: IslandMetrics) {
    val d = LocalDensity.current
    Box(
        Modifier.size(with(d) { m.detachedWidth.toDp() }, m.heightDp()),
        contentAlignment = Alignment.Center,
    ) { MinimalGlyph(a, m.heightDp()) }
}

/**
 * 主の島（右端をカメラ部分にそろえ、左へ少し伸びた形）。伸びた部分に小さな印を出す。
 * time なら印の右に時間も並べ（[showsTime] のもの）、島はその分だけ左へ伸びる（[IslandMetrics.splitLead]）
 */
@Composable
fun MinimalAttachedContent(a: IslandActivity, m: IslandMetrics, time: Boolean = false) {
    if (time) {
        val g = m.glyphDp()
        Layout(content = { MarkAndTime(a, g) }) { measurables, _ ->
            val p = measurables.map { it.measure(Constraints()) }
            val content = p.maxOfOrNull { it.width } ?: 0
            val pad = m.sidePad.roundToInt()
            val h = m.height.roundToInt()
            // 中身の右にカメラ部分をあける。島の形はこの幅に合わせる（IslandRoot の targetGeo）
            val w = (m.splitLead(content.toFloat()) + m.sensorWidth).roundToInt()
            layout(w, h) {
                p.forEach { it.place(pad, (h - it.height) / 2) }
            }
        }
    } else {
        val d = LocalDensity.current
        val lead = with(d) { m.minimalLead.toDp() }
        Box(Modifier.size(with(d) { (m.sensorWidth + m.minimalLead).toDp() }, m.heightDp())) {
            Box(Modifier.size(lead + 4.dp, m.heightDp()).padding(start = 4.dp), contentAlignment = Alignment.Center) {
                MinimalGlyph(a, minOf(lead, m.heightDp()))
            }
        }
    }
}

/**
 * コンパクトで時間（経過・残り）を出しているもの。2 つ同時で主の島が狭くなっているときも、
 * 設定でオンなら印の右にこの時間を出す（iPhone は印だけ）
 */
fun IslandActivity.showsTime(): Boolean = when (this) {
    is StopwatchActivity, is TimerActivity -> true
    is CallActivity -> !incoming && chrono != null
    // コンパクトの右側は短い文・進捗・時間の順に出すので、時間を出しているときだけ
    is LiveActivity -> shortText.isNullOrBlank() && progress == null && !indeterminate && chrono != null
    else -> false
}

/** 2 つ同時のとき主の島に出す、印と時間。どちらもコンパクトと同じもの（通話中はコンパクトの左側そのまま） */
@Composable
private fun MarkAndTime(a: IslandActivity, g: Dp) {
    when (a) {
        is CallActivity -> CallLeading(a, g)
        is TimerActivity -> MarkWithTime({ TimerRing(a, g) }) { TimerTime(a) }
        is StopwatchActivity -> MarkWithTime({ Glyph(IslandIcons.Stopwatch, IslandColors.Orange, g) }) { StopwatchTime(a) }
        is LiveActivity -> MarkWithTime({ LiveLeading(a, g) }) { LiveTrailing(a, g) }
        else -> Spacer(Modifier.size(1.dp))
    }
}

/** 印の右に時間を並べる（通話中のコンパクトの左側と同じ間隔） */
@Composable
private fun MarkWithTime(mark: @Composable () -> Unit, time: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        mark()
        Spacer(Modifier.width(4.dp))
        time()
    }
}

/** 印そのもの。box は印を置ける正方形の一辺 */
@Composable
private fun MinimalGlyph(a: IslandActivity, box: Dp) {
    when (a) {
        is MediaActivity -> if (a.art != null) Artwork(a.art, box * 0.72f, box * 0.36f) else Waveform(Color(a.accent), a.playing, Modifier.size(box * 0.5f, box * 0.36f), audio = true)
        is CallActivity -> Glyph(IslandIcons.Phone, IslandColors.Green, box * 0.5f)
        is TimerActivity -> TimerRing(a, box * 0.62f)
        is StopwatchActivity -> Glyph(IslandIcons.Stopwatch, IslandColors.Orange, box * 0.55f)
        is RingingActivity -> Glyph(IslandIcons.Alarm, IslandColors.Orange, box * 0.55f)
        is NavigationActivity -> ManeuverIcon(a, box * 0.55f)
        is LiveActivity -> if (a.progress != null || a.indeterminate) {
            ProgressRing(a.progress, readableColor(a.color), Modifier.size(box * 0.6f))
        } else if (a.kind == Kind.Recording) {
            RecordingDot(IslandColors.Red)
        } else {
            LiveIcon(a, box * 0.5f)
        }
        is ReferenceActivity -> Spacer(Modifier.size(1.dp))
    }
}

// ---- 小物 ----

@Composable
fun ChronoText(
    chrono: dev.ryunosuke.island.island.Chrono,
    color: Color,
    style: androidx.compose.ui.text.TextStyle = IslandText.compact,
    fine: Boolean = false,
    format: (Long) -> String,
) {
    val v = chronoValue(chrono, fine)
    Text(format(v), style = style, color = color, maxLines = 1)
}

/** 通話中の緑の受話器と通話時間 */
@Composable
private fun CallLeading(a: CallActivity, g: Dp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Glyph(if (a.video) IslandIcons.Video else IslandIcons.Phone, IslandColors.Green, g * 0.75f)
        if (a.chrono != null) {
            Spacer(Modifier.width(4.dp))
            ChronoText(a.chrono, IslandColors.Green) { TimeFormat.clock(it) }
        }
    }
}

@Composable
private fun TimerTime(a: TimerActivity) {
    ChronoText(a.chrono, IslandColors.Orange) { TimeFormat.countdown(it) }
}

@Composable
private fun StopwatchTime(a: StopwatchActivity) {
    ChronoText(a.chrono, IslandColors.Orange, fine = true) { if (a.preciseFraction) TimeFormat.stopwatch(it) else TimeFormat.clock(it) }
}

@Composable
private fun LiveLeading(a: LiveActivity, g: Dp) {
    if (a.kind == Kind.Recording) RecordingDot(readableColor(a.color).takeIf { a.color != 0 } ?: IslandColors.Red)
    else LiveIcon(a, g * 0.85f)
}

@Composable
private fun TimerRing(a: TimerActivity, size: Dp) {
    val total = a.totalMs
    if (total == null || total <= 0) {
        Glyph(IslandIcons.Timer, IslandColors.Orange, size)
        return
    }
    val left = chronoValue(a.chrono)
    ProgressRing(left.toFloat() / total, IslandColors.Orange, Modifier.size(size), stroke = 3.dp)
}

@Composable
fun ManeuverIcon(a: NavigationActivity, size: Dp) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    BitmapOr(rememberIconBitmap(a.maneuver, px), size) {
        Glyph(IslandIcons.Navigation, IslandColors.Blue, size)
    }
}

@Composable
fun LiveIcon(a: LiveActivity, size: Dp) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    // 通知の小アイコンは単色の形なので通知の色で塗る
    BitmapOr(rememberIconBitmap(a.smallIcon, px), size, tint = readableColor(a.color)) {
        BitmapOr(rememberAppIcon(a.packageName, px), size, shape = CircleShape) {
            Glyph(IslandIcons.Download, readableColor(a.color), size)
        }
    }
}

@Composable
private fun LiveTrailing(a: LiveActivity, g: Dp) {
    val color = readableColor(a.color)
    when {
        !a.shortText.isNullOrBlank() ->
            Text(a.shortText, style = IslandText.compact, color = color, maxLines = 1, modifier = Modifier.widthIn(max = 88.dp))
        a.progress != null || a.indeterminate -> ProgressRing(a.progress, color, Modifier.size(g * 0.85f))
        a.chrono != null -> ChronoText(a.chrono, if (a.kind == Kind.Recording) IslandColors.Red else color) { TimeFormat.clock(it) }
        else -> Spacer(Modifier.size(0.dp))
    }
}

@Composable
fun RecordingDot(color: Color) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "rec")
    val a = t.animateFloat(
        1f, 0.35f,
        androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(700),
            androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "recAlpha",
    )
    Box(Modifier.size(10.dp).clip(CircleShape).background(color.copy(alpha = a.value)))
}
