package dev.ryunosuke.island.ui.island.content

import android.os.SystemClock
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ryunosuke.island.island.ActionRole
import dev.ryunosuke.island.island.ActionTarget
import dev.ryunosuke.island.island.CallActivity
import dev.ryunosuke.island.island.IslandAction
import dev.ryunosuke.island.island.IslandActivity
import dev.ryunosuke.island.island.LiveActivity
import dev.ryunosuke.island.island.MediaActivity
import dev.ryunosuke.island.island.MediaCommand
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
import kotlinx.coroutines.delay

@Composable
fun ExpandedContent(a: IslandActivity, m: IslandMetrics, act: ContentActions) {
    val w = m.expandedWidthDp()
    Box(Modifier.width(w)) {
        when (a) {
            is MediaActivity -> MediaExpanded(a, act)
            is CallActivity -> CallExpanded(a, act)
            is TimerActivity -> ClockExpanded(
                label = a.label ?: "タイマー",
                time = { ChronoText(a.chrono, IslandColors.Orange, IslandText.bigTime) { TimeFormat.countdown(it) } },
                actions = a.actions,
                act = act,
            )
            is StopwatchActivity -> ClockExpanded(
                label = if (a.lapCount > 0) "ストップウォッチ · ラップ ${a.lapCount}" else "ストップウォッチ",
                time = { ChronoText(a.chrono, IslandColors.Orange, IslandText.bigTime, fine = true) { if (a.preciseFraction) TimeFormat.stopwatch(it) else TimeFormat.clock(it) } },
                actions = a.actions,
                act = act,
            )
            is RingingActivity -> RingingExpanded(a, act)
            is NavigationActivity -> NavigationExpanded(a)
            is LiveActivity -> LiveExpanded(a, act)
            is ReferenceActivity -> Spacer(Modifier.height(with(LocalDensity.current) { (a.expandedHeightH * m.height).toDp() }))
        }
    }
}

// ---- 音楽 ----

@Composable
private fun MediaExpanded(a: MediaActivity, act: ContentActions) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(a.art, 56.dp, 12.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    a.title, style = IslandText.title, maxLines = 1,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1500),
                )
                if (a.artist.isNotBlank()) {
                    Text(a.artist, style = IslandText.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(10.dp))
            Waveform(Color(a.accent), a.playing, Modifier.size(28.dp, 20.dp), bars = 6, audio = true)
        }
        Spacer(Modifier.height(14.dp))
        SeekBar(a, act)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(48.dp)) {
            Row(
                Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                fun media(c: MediaCommand) = ActionTarget.Media(a.key, c)
                if (a.skipByTime) {
                    TransportButton(IslandIcons.Rewind, "15秒戻る", 30.dp) { act.onTarget(media(MediaCommand.Rewind)) }
                } else {
                    TransportButton(IslandIcons.Previous, "前の曲", 32.dp, enabled = a.canPrevious) { act.onTarget(media(MediaCommand.Previous)) }
                }
                TransportButton(if (a.playing) IslandIcons.Pause else IslandIcons.Play, if (a.playing) "一時停止" else "再生", 40.dp) {
                    act.onTarget(media(MediaCommand.PlayPause))
                }
                if (a.skipByTime) {
                    TransportButton(IslandIcons.Forward, "15秒進む", 30.dp) { act.onTarget(media(MediaCommand.Forward)) }
                } else {
                    TransportButton(IslandIcons.Next, "次の曲", 32.dp, enabled = a.canNext) { act.onTarget(media(MediaCommand.Next)) }
                }
            }
            Box(Modifier.align(Alignment.CenterEnd)) {
                TransportButton(IslandIcons.Output, "出力先", 22.dp, tint = IslandColors.Secondary) {
                    act.onTarget(ActionTarget.Media(a.key, MediaCommand.Output))
                }
            }
        }
    }
}

@Composable
private fun TransportButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    size: Dp,
    enabled: Boolean = true,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box(
        Modifier
            .size(size + 16.dp)
            .clip(CircleShape)
            .semantics {
                contentDescription = label
                role = Role.Button
            }
            .pointerInput(enabled, onClick) {
                if (enabled) detectTapGestures {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.VirtualKey)
                    onClick()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Glyph(icon, if (enabled) tint else tint.copy(alpha = 0.3f), size)
    }
}

/** 再生位置。ドラッグで動かし、離したところにシークする。つまんでいる間は太くなる */
@Composable
private fun SeekBar(a: MediaActivity, act: ContentActions) {
    val now by rememberElapsedClock(250, a.playing)
    var drag by remember { mutableStateOf<Float?>(null) }
    // シーク直後は新しい位置が届くまで指を離した位置を出しておく
    var pending by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(a.positionMs, a.positionAtElapsed) { pending = null }
    LaunchedEffect(pending) {
        if (pending != null) {
            delay(1_500)
            pending = null
        }
    }
    val dur = a.durationMs
    val pos = drag?.let { (it * dur).toLong() } ?: pending ?: a.positionAt(maxOf(now, SystemClock.elapsedRealtime()))
    val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val barH by animateDpAsState(if (drag != null) 10.dp else 6.dp, label = "seekH")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(TimeFormat.media(pos), style = IslandText.caption, modifier = Modifier.width(42.dp))
        Box(
            Modifier
                .weight(1f)
                .height(24.dp)
                .pointerInput(a.canSeek, dur) {
                    if (!a.canSeek || dur <= 0) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { drag = (it.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            drag?.let {
                                val target = (it * dur).toLong()
                                pending = target
                                act.onTarget(ActionTarget.MediaSeek(a.key, target))
                            }
                            drag = null
                        },
                        onDragCancel = { drag = null },
                    ) { change, _ ->
                        change.consume()
                        drag = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Canvas(Modifier.fillMaxWidth().height(barH)) {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(IslandColors.Track, cornerRadius = r)
                drawRoundRect(Color.White, size = Size(size.width * frac, size.height), cornerRadius = r)
            }
        }
        Text(
            if (dur > 0) TimeFormat.media(dur - pos, remaining = true) else "",
            style = IslandText.caption,
            textAlign = TextAlign.End,
            modifier = Modifier.width(46.dp),
        )
    }
}

// ---- 通話 ----

@Composable
private fun CallExpanded(a: CallActivity, act: ContentActions) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(a, if (a.incoming) 48.dp else 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                a.detail ?: if (a.incoming) "着信" else "通話中",
                style = IslandText.caption, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(a.name, style = IslandText.title.copy(fontSize = 17.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!a.incoming && a.chrono != null) {
                ChronoText(a.chrono, IslandColors.Green, IslandText.caption.copy(color = IslandColors.Green)) { TimeFormat.clock(it) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            // 着信は 拒否(赤)・応答(緑)、通話中は その他のボタン・終了(赤) の順に並べる
            val ordered = a.actions.sortedBy {
                when (it.role) {
                    ActionRole.Decline, ActionRole.HangUp -> 1
                    ActionRole.Answer -> 2
                    else -> 0
                }
            }
            for (x in ordered.take(4)) ActionCircle(x, act, size = if (a.incoming) 48.dp else 44.dp)
        }
    }
}

@Composable
private fun Avatar(a: CallActivity, size: Dp) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    BitmapOr(rememberIconBitmap(a.avatar, px), size, shape = CircleShape) {
        Box(
            Modifier.size(size).clip(CircleShape).background(Color(0xFF636366)),
            contentAlignment = Alignment.Center,
        ) {
            Text(a.name.take(1), color = Color.White, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Medium)
        }
    }
}

// ---- 時計 ----

@Composable
private fun ClockExpanded(label: String, time: @Composable () -> Unit, actions: List<IslandAction>, act: ContentActions) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // iPhone と同じく、止める系（×・リセット）を左、一時停止/再開を右に
            val ordered = actions.sortedBy { if (it.role == ActionRole.Pause || it.role == ActionRole.Play) 1 else 0 }
            for (x in ordered.take(3)) ActionCircle(x, act, size = 46.dp)
        }
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(label, style = IslandText.caption.copy(color = IslandColors.Orange), maxLines = 1)
            time()
        }
    }
}

@Composable
private fun RingingExpanded(a: RingingActivity, act: ContentActions) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(if (a.isTimer) IslandIcons.Timer else IslandIcons.Alarm, IslandColors.Orange, 30.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.title, style = IslandText.caption.copy(color = IslandColors.Orange), maxLines = 1)
            Text(a.detail ?: "", style = IslandText.bigTime.copy(fontSize = 30.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val ordered = a.actions.sortedBy { if (it.role == ActionRole.Stop) 1 else 0 }
            for (x in ordered.take(3)) ActionCircle(x, act, size = 46.dp)
        }
    }
}

// ---- ナビ ----

@Composable
private fun NavigationExpanded(a: NavigationActivity) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ManeuverIcon(a, 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                if (!a.distance.isNullOrBlank()) {
                    Text(a.distance, style = IslandText.title.copy(fontSize = 24.sp, fontFeatureSettings = "tnum"), maxLines = 1)
                }
                Text(a.instruction, style = IslandText.title.copy(fontWeight = FontWeight.Normal), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!a.detail.isNullOrBlank()) {
            Text(a.detail, style = IslandText.caption, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

// ---- その他 ----

@Composable
private fun LiveExpanded(a: LiveActivity, act: ContentActions) {
    val color = readableColor(a.color)
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val px = with(LocalDensity.current) { 36.dp.roundToPx() }
            BitmapOr(rememberIconBitmap(a.largeIcon, px), 36.dp, shape = RoundedCornerShape(8.dp)) {
                BitmapOr(rememberAppIcon(a.packageName, px), 36.dp, shape = CircleShape) { LiveIcon(a, 28.dp) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(a.title, style = IslandText.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!a.text.isNullOrBlank()) Text(a.text, style = IslandText.subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            when {
                !a.shortText.isNullOrBlank() -> Text(a.shortText, style = IslandText.title, color = color, modifier = Modifier.padding(start = 8.dp))
                a.chrono != null -> Box(Modifier.padding(start = 8.dp)) {
                    ChronoText(a.chrono, color, IslandText.title.copy(fontFeatureSettings = "tnum")) { TimeFormat.clock(it) }
                }
            }
        }
        if (a.progress != null || a.indeterminate) {
            Spacer(Modifier.height(12.dp))
            LinearBar(a.progress, color)
        }
        if (a.actions.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (x in a.actions) PillButton(x.label) { act.onAction(x) }
            }
        }
    }
}

@Composable
private fun LinearBar(progress: Float?, color: Color) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "bar")
    val sweep by t.animateFloat(
        0f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1200)),
        label = "sweep",
    )
    Canvas(Modifier.fillMaxWidth().height(6.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(IslandColors.Track, cornerRadius = r)
        if (progress != null) {
            drawRoundRect(color, size = Size(size.width * progress.coerceIn(0f, 1f), size.height), cornerRadius = r)
        } else {
            val w = size.width * 0.3f
            drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset((size.width + w) * sweep - w, 0f), size = Size(w, size.height), cornerRadius = r)
        }
    }
}

@Composable
private fun PillButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(IslandColors.ButtonFill)
            .pointerInput(onClick) { detectTapGestures { onClick() } }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, style = IslandText.caption.copy(color = Color.White, fontSize = 13.sp), maxLines = 1)
    }
}

/** ボタンの意味に応じた丸ボタン。意味が分からないものは文字のボタンにする */
@Composable
private fun ActionCircle(x: IslandAction, act: ContentActions, size: Dp) {
    if (x.role == ActionRole.Generic) {
        PillButton(x.label) { act.onAction(x) }
        return
    }
    val s = styleOf(x.role)
    CircleButton(s.icon, x.label, s.background, s.tint, size = size) { act.onAction(x) }
}
