package dev.ryunosuke.island.ui.island.content

import android.media.AudioManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import dev.ryunosuke.island.ui.island.LocalIslandMotion
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ryunosuke.island.island.IslandAlert
import dev.ryunosuke.island.island.PodsBattery
import dev.ryunosuke.island.ui.island.IslandColors
import dev.ryunosuke.island.ui.island.IslandIcons
import dev.ryunosuke.island.ui.island.IslandMetrics
import dev.ryunosuke.island.ui.island.IslandText
import kotlinx.coroutines.delay

/** 省電力（バッテリー セーバー）が今入っているか。島の外（OverlayHost）から渡す */
val LocalBatterySaverOn = androidx.compose.runtime.staticCompositionLocalOf { false }

/** 充電・消音などの一時表示。コンパクトより少し横に広い形で出す（電池残量低下と、イヤホンの電池の内訳は展開した形） */
@Composable
fun AlertContent(alert: IslandAlert, m: IslandMetrics) {
    val g = m.glyphDp()
    when (alert) {
        // iOS 26: 左に「充電中」、右に緑の残量と電池（灰色の器に緑の中身）
        is IslandAlert.Charging -> CompactRow(
            m,
            leading = { Text("充電中", style = IslandText.compact) },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${alert.level}%", style = IslandText.compact, color = IslandColors.Green)
                    Spacer(Modifier.width(6.dp))
                    BatteryGlyph(alert.level, IslandColors.Green)
                }
            },
        )
        is IslandAlert.LowBattery -> LowBatteryContent(alert, m)
        is IslandAlert.Ringer -> {
            val (icon, color, label) = when (alert.mode) {
                AudioManager.RINGER_MODE_SILENT -> Triple(IslandIcons.BellOff, IslandColors.Red, "サイレント")
                AudioManager.RINGER_MODE_VIBRATE -> Triple(IslandIcons.Vibrate, IslandColors.Orange, "バイブレーション")
                else -> Triple(IslandIcons.Bell, Color.White, "着信音")
            }
            CompactRow(
                m,
                leading = { Glyph(icon, color, g) },
                trailing = { Text(label, style = IslandText.compact, color = color) },
            )
        }
        is IslandAlert.Dnd -> CompactRow(
            m,
            leading = { Glyph(IslandIcons.Moon, IslandColors.Indigo, g * 0.9f) },
            trailing = {
                Text(
                    if (alert.on) "おやすみ オン" else "おやすみ オフ",
                    style = IslandText.compact,
                    color = if (alert.on) IslandColors.Indigo else IslandColors.Secondary,
                )
            },
        )
        is IslandAlert.Device -> if (alert.detail && alert.pods != null) {
            // 左右とケースの内訳（コンパクトをタップ・長押しすると開く）
            PodsContent(alert, alert.pods, m)
        } else {
            // iPhone と同じく、左にイヤホン、右に電池。電池が分からないときだけ右に名前を出す
            CompactRow(
                m,
                leading = { Glyph(IslandIcons.Headphones, Color.White, g * 0.9f) },
                trailing = {
                    val b = alert.battery
                    if (b != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("$b%", style = IslandText.compact, color = IslandColors.Green)
                            Spacer(Modifier.width(6.dp))
                            ProgressRing(b / 100f, IslandColors.Green, Modifier.size(g * 0.85f))
                        }
                    } else {
                        Text(alert.name, style = IslandText.compact, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 96.dp))
                    }
                },
            )
        }
        IslandAlert.Unlock -> CompactRow(
            m,
            leading = { UnlockGlyph(g) },
            trailing = { Spacer(Modifier.size(0.dp)) },
        )
    }
}

/**
 * イヤホンの電池の内訳（左・右・ケース）。つながったときの一時表示をタップ・長押しすると、展開した形でこれを出す。
 * iPhone の島は AirPods の内訳を出さないので、形は展開した島の決まり（カメラの横には絵と短い値だけ、文字はカメラ穴の下から）に、
 * 中身は iOS の「バッテリー」ウィジェット（残量の輪と数字）に寄せた
 */
@Composable
private fun PodsContent(alert: IslandAlert.Device, pods: PodsBattery, m: IslandMetrics) {
    val zone = m.cameraZone()
    val w = m.expandedWidthDp()
    val pad = 18.dp
    val top = 14.dp
    Column(Modifier.width(w).padding(horizontal = pad, vertical = top)) {
        // カメラの横の行: 左にイヤホン、右に全体の残量（コンパクトの右に出ていたのと同じ値）
        Row(
            Modifier.fillMaxWidth().heightIn(min = (zone.bottom - top).coerceAtLeast(0.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(IslandIcons.Headphones, Color.White, 28.dp)
            Spacer(Modifier.weight(1f))
            alert.battery?.let {
                Text(
                    "$it%", style = IslandText.title, color = IslandColors.Green, maxLines = 1,
                    modifier = Modifier.widthIn(max = (w - pad - zone.right).coerceAtLeast(MinBesideCamera)),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(alert.name, style = IslandText.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            PodsPart("左", pods.left)
            PodsPart("右", pods.right)
            PodsPart("ケース", pods.case)
        }
    }
}

/** 残量の輪と数字。充電中は稲妻を付け、充電していなくて 20% 以下なら赤。分からないものは空の輪と「—」 */
@Composable
private fun PodsPart(label: String, part: PodsBattery.Part?) {
    val color = when {
        part == null -> IslandColors.Gray
        !part.charging && part.level <= 20 -> IslandColors.Red
        else -> IslandColors.Green
    }
    val text = if (part == null) IslandColors.Secondary else Color.White
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            LevelRing(part?.level, color, Modifier.fillMaxSize(), stroke = 5.dp)
            Text(label, style = IslandText.caption, color = text, maxLines = 1)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (part?.charging == true) Glyph(IslandIcons.Bolt, IslandColors.Green, 14.dp)
            Text(part?.let { "${it.level}%" } ?: "—", style = IslandText.compact.copy(fontSize = 14.sp), color = text)
        }
    }
}

/** 残量の輪。[level] が null なら器だけ */
@Composable
private fun LevelRing(level: Int?, color: Color, modifier: Modifier, stroke: Dp) {
    Canvas(modifier) {
        val s = stroke.toPx()
        val inset = Offset(s / 2, s / 2)
        val arc = Size(size.width - s, size.height - s)
        drawArc(color.copy(alpha = 0.3f), 0f, 360f, false, inset, arc, style = Stroke(s))
        if (level != null && level > 0) {
            drawArc(color, -90f, 3.6f * level.coerceIn(0, 100), false, inset, arc, style = Stroke(s, cap = StrokeCap.Round))
        }
    }
}

/**
 * 錠前が開く。Face ID で解除したときの動き（SF Symbols の lock.fill → lock.open.fill）。
 * 胴の上に U 字の掛け金が差さっていて、開くときは掛け金が持ち上がり、右足を軸に左足が外へ振り上がる。
 */
@Composable
private fun UnlockGlyph(glyph: Dp) {
    val im = LocalIslandMotion.current
    val open = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(im.ms(160).toLong())
        open.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 420f * im.speed * im.speed))
    }
    Canvas(Modifier.size(glyph)) {
        val w = size.width
        // 胴: 幅 0.74w・高さ 0.52w、下にそろえる
        val bodyW = w * 0.74f
        val bodyH = w * 0.52f
        val bodyL = (w - bodyW) / 2
        val bodyT = w - bodyH - w * 0.02f
        val stroke = w * 0.12f
        // 掛け金: 足の中心どうしの幅 0.46w、足は胴の中まで差し込む
        val sw = w * 0.46f
        val sl = (w - sw) / 2
        val sr = sl + sw
        val archTop = w * 0.04f + stroke / 2
        val legBottom = bodyT + stroke
        val shackle = Path().apply {
            moveTo(sl, legBottom)
            lineTo(sl, archTop + sw / 2)
            arcTo(Rect(sl, archTop, sr, archTop + sw), 180f, 180f, false)
            lineTo(sr, legBottom)
        }
        val p = open.value
        withTransform({
            translate(top = -w * 0.08f * p)
            // 右足の根元を軸に時計回り（左足が上がる向き）
            rotate(degrees = 22f * p, pivot = Offset(sr, bodyT))
        }) {
            drawPath(shackle, Color.White, style = Stroke(stroke, cap = StrokeCap.Butt))
        }
        drawRoundRect(Color.White, Offset(bodyL, bodyT), Size(bodyW, bodyH), CornerRadius(w * 0.11f))
    }
}

/**
 * iOS 26 Beta 5 の電池残量低下。高さ 2.8H の横長の形で、左に「バッテリー残量 10%」と
 * 「タップしてバッテリー セーバーをオン」、右に赤い丸いボタン（中に電池）。
 * タップで省電力をオンにすると、ボタンが黄色に、下の行が「バッテリー セーバー オン」に変わる。
 * 寸法はすべて映像の実測（島の高さ H を単位にする）。
 */
@Composable
private fun LowBatteryContent(alert: IslandAlert.LowBattery, m: IslandMetrics) {
    val d = LocalDensity.current
    val im = LocalIslandMotion.current
    fun h(x: Float) = with(d) { (m.height * x).toDp() }
    // 出した時点の値ではなく今の状態（先に省電力が入っていた・設定画面で切った、も反映する）
    val on = LocalBatterySaverOn.current
    val t = im.tween<Color>(320)
    val pill by animateColorAsState(if (on) LowPower.Yellow else IslandColors.Red.copy(alpha = 0.17f), t, label = "pill")
    val sub by animateColorAsState(if (on) LowPower.Yellow else IslandColors.Secondary, t, label = "sub")
    val onP by animateFloatAsState(if (on) 1f else 0f, im.tween(320), label = "on")
    Box(Modifier.size(m.expandedWidthDp(), h(2.8f))) {
        Column(
            Modifier.align(Alignment.CenterStart).padding(start = h(0.91f), end = h(3.3f), top = h(0.36f)),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text("バッテリー残量 ${alert.level}%", style = IslandText.title, maxLines = 1)
            Text(
                if (on) "バッテリー セーバー オン" else "タップしてバッテリー セーバーをオン",
                style = IslandText.subtitle, color = sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = h(0.78f))
                .size(h(2.33f), h(1.53f))
                .background(pill, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            LowBatteryGlyph(alert.level, onP, Modifier.size(h(1.52f), h(0.66f)))
        }
    }
}

private object LowPower {
    /** 映像のボタンの色（iOS の黄色より少し橙寄り） */
    val Yellow = Color(0xFFFDBE36)
    val Pink = Color(0xFFFE7C91)
    val Ring = Color(0xFFFF4F62)
}

/**
 * ボタンの中の電池。p = 0 は赤（器は半透明の赤、中身は桃色、中身の周りに赤い輪）、
 * p = 1 は省電力オン（黄色の地に黒い器、黄色の中身）
 */
@Composable
private fun LowBatteryGlyph(level: Int, p: Float, modifier: Modifier) {
    Canvas(modifier) {
        val hgt = size.height
        val capW = hgt * 0.13f
        val gap = hgt * 0.07f
        val bodyW = size.width - capW - gap
        val body = lerp(IslandColors.Red.copy(alpha = 0.5f), Color.Black, p)
        drawRoundRect(body, size = Size(bodyW, hgt), cornerRadius = CornerRadius(hgt * 0.38f))
        drawRoundRect(
            body,
            Offset(bodyW + gap, (hgt - hgt * 0.34f) / 2),
            Size(capW, hgt * 0.34f),
            CornerRadius(capW / 2),
        )
        // 中身: 器の内側 0.18h の余白、残量ぶんの長さ（少なくとも丸 1 つ）
        val pad = hgt * 0.18f
        val fh = hgt - pad * 2
        val fw = ((bodyW - pad * 2) * (level.coerceIn(0, 100) / 100f)).coerceAtLeast(fh * 0.53f)
        val fill = lerp(LowPower.Pink, LowPower.Yellow, p)
        if (p < 0.99f) {
            // 赤い輪。輪の内側は少し暗くして中身を浮かせる
            val cx = pad + fw / 2
            val cy = hgt / 2
            // 映像: 輪は器より上下にはみ出す（高さ 1.28h、幅は中身 + 0.8h）
            val rw = fw + hgt * 0.8f
            val rh = hgt * 1.28f
            val a = 1f - p
            drawRoundRect(Color.Black.copy(alpha = 0.35f * a), Offset(cx - rw / 2, cy - rh / 2), Size(rw, rh), CornerRadius(rh * 0.45f))
            drawRoundRect(
                LowPower.Ring.copy(alpha = a), Offset(cx - rw / 2, cy - rh / 2), Size(rw, rh), CornerRadius(rh * 0.45f),
                style = Stroke(hgt * 0.09f),
            )
        }
        drawRoundRect(fill, Offset(pad, pad), Size(fw, fh), CornerRadius(minOf(fw, fh) / 2))
    }
}
