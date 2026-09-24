package dev.ryunosuke.island.ui.island.content

import android.content.Context
import android.graphics.drawable.Icon
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import dev.ryunosuke.island.island.ActionRole
import dev.ryunosuke.island.island.Chrono
import dev.ryunosuke.island.source.AudioSpectrum
import dev.ryunosuke.island.ui.island.IslandColors
import dev.ryunosuke.island.ui.island.IslandIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---- 時間 ----

@Composable
fun rememberElapsedClock(periodMs: Long, running: Boolean): State<Long> =
    produceState(SystemClock.elapsedRealtime(), periodMs, running) {
        value = SystemClock.elapsedRealtime()
        if (!running) return@produceState
        while (isActive) {
            delay(periodMs)
            value = SystemClock.elapsedRealtime()
        }
    }

/**
 * Chrono の値を ms で。秒表示なら表示が変わる瞬間（次の秒の境目）にだけ起きる。
 * fine（1/100 秒表示）は約 30fps で更新する。
 */
@Composable
fun chronoValue(chrono: Chrono, fine: Boolean = false): Long {
    val v by produceState(chrono.valueAt(System.currentTimeMillis()), chrono, fine) {
        value = chrono.valueAt(System.currentTimeMillis())
        if (!chrono.running) return@produceState
        while (isActive) {
            if (fine) {
                delay(FINE_FRAME_MS)
            } else {
                val cur = chrono.valueAt(System.currentTimeMillis())
                // カウントダウンは切り上げ表示なので、端数が尽きたときに変わる
                val wait = if (chrono.countDown) (cur % 1000).let { if (it == 0L) 1000L else it } else 1000 - cur % 1000
                delay(wait + 5)
            }
            value = chrono.valueAt(System.currentTimeMillis())
        }
    }
    return v
}

/** 常に動き続ける表示（波形・1/100 秒）の更新間隔。毎フレーム描くと窓全体の合成が走り続けるので 30fps に抑える */
private const val FINE_FRAME_MS = 33L

// ---- 絵 ----

/** 実際の音の強さ。設定でオンのときだけ島の外から渡される（オフ・許可なしなら null） */
val LocalAudioSpectrum = staticCompositionLocalOf<AudioSpectrum?> { null }

/**
 * iPhone の再生中の波形。audio = true（音楽）で、設定でオンなら実際に出ている音に合わせる
 * （低い音が左、高い音が右）。音が取れないときは、周波数の違う sin を掛け合わせてそれらしく揺らす。
 * 止まっているときは点に縮めて、描き直しも止める。
 */
@Composable
fun Waveform(
    color: Color,
    playing: Boolean,
    modifier: Modifier = Modifier.size(22.dp, 16.dp),
    bars: Int = 5,
    audio: Boolean = false,
) {
    val spectrum = if (audio && playing) LocalAudioSpectrum.current else null
    if (spectrum != null) {
        DisposableEffect(spectrum) {
            spectrum.acquire()
            onDispose { spectrum.release() }
        }
    }
    // 時刻は描画の中でだけ読む。コンポーズし直さず描き直すだけにする（音楽の再生中はずっと動くので）
    val t = produceState(0L, playing) {
        if (!playing) return@produceState
        val start = SystemClock.elapsedRealtime()
        while (isActive) {
            value = SystemClock.elapsedRealtime() - start
            delay(FINE_FRAME_MS)
        }
    }
    val shown = remember(bars) { FloatArray(bars) }
    val target = remember(bars) { FloatArray(bars) }
    val lastFrame = remember { longArrayOf(0L) }
    Canvas(modifier) {
        val ms = t.value
        val sec = ms / 1000f
        val now = SystemClock.elapsedRealtime()
        if (spectrum != null && spectrum.isLive(now)) {
            spectrum.levels(bars, target)
        } else {
            for (i in 0 until bars) target[i] = if (playing) waveLevel(i, sec) else 0f
        }
        // 20 回/秒で届く値を、描くたびに少しずつ追いかける（段々に見えないように）
        val dt = ((now - lastFrame[0]) / 1000f).coerceIn(0f, 0.1f)
        lastFrame[0] = now
        // 止まっているときは描き直しが来ないので、追いかけずにすぐ点にする
        val k = if (playing) 1f - kotlin.math.exp(-dt / 0.05f) else 1f
        for (i in 0 until bars) shown[i] += (target[i] - shown[i]) * k
        drawBars(color, shown)
    }
}

private fun waveLevel(i: Int, sec: Float): Float {
    val f1 = 5.1f + i * 1.7f
    val f2 = 2.3f + i * 0.9f
    val a = kotlin.math.sin(sec * f1 + i * 1.3f)
    val b = kotlin.math.sin(sec * f2 + i * 2.1f)
    return (0.5f + 0.5f * a * b + 0.15f * kotlin.math.sin(sec * 11f + i)).coerceIn(0.08f, 1f)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBars(color: Color, levels: FloatArray) {
    val n = levels.size
    val barW = size.width / (n * 2 - 1)
    val minH = barW
    for ((i, lv) in levels.withIndex()) {
        val h = minH + (size.height - minH) * lv.coerceIn(0f, 1f)
        drawRoundRect(
            color = color,
            topLeft = Offset(i * barW * 2, (size.height - h) / 2),
            size = Size(barW, h),
            cornerRadius = CornerRadius(barW / 2),
        )
    }
}

@Composable
fun ProgressRing(
    progress: Float?,
    color: Color,
    modifier: Modifier = Modifier.size(20.dp),
    stroke: Dp = 3.dp,
) {
    // 進み具合が不明なら回す
    val spin = if (progress == null) {
        val t = rememberInfiniteTransition(label = "spin")
        t.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "deg").value
    } else 0f
    Canvas(modifier) {
        val s = stroke.toPx()
        val inset = s / 2
        val arcSize = Size(size.width - s, size.height - s)
        drawArc(color.copy(alpha = 0.3f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(s))
        if (progress == null) {
            drawArc(color, spin - 90f, 90f, false, Offset(inset, inset), arcSize, style = Stroke(s, cap = StrokeCap.Round))
        } else {
            drawArc(color, -90f, 360f * progress.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize, style = Stroke(s, cap = StrokeCap.Round))
        }
    }
}

/**
 * iOS 26 の電池のアイコン。縁取りは無く、半透明の器の内側に、余白を空けて角の丸い中身が入る。
 * 右に小さな端子。寸法は iOS 26 の映像の電池（器 2.07:1、余白 0.18h、端子 0.13h × 0.34h）から。
 */
@Composable
fun BatteryGlyph(
    level: Int,
    color: Color,
    modifier: Modifier = Modifier.size(28.dp, 13.dp),
    body: Color = Color.White.copy(alpha = 0.3f),
) {
    Canvas(modifier) {
        val h = size.height
        val capW = h * 0.13f
        val gap = h * 0.08f
        val bodyW = size.width - capW - gap
        drawRoundRect(body, size = Size(bodyW, h), cornerRadius = CornerRadius(h * 0.36f))
        drawRoundRect(body, Offset(bodyW + gap, h * 0.33f), Size(capW, h * 0.34f), CornerRadius(capW / 2))
        val pad = h * 0.18f
        val fh = h - pad * 2
        val fw = ((bodyW - pad * 2) * (level.coerceIn(0, 100) / 100f)).coerceAtLeast(fh * 0.5f)
        drawRoundRect(color, Offset(pad, pad), Size(fw, fh), CornerRadius(minOf(fw, fh) * 0.45f))
    }
}

@Composable
fun Glyph(icon: ImageVector, color: Color, size: Dp, modifier: Modifier = Modifier) {
    M3Icon(icon, contentDescription = null, tint = color, modifier = modifier.size(size))
}

/** ジャケット。無ければ音符 */
@Composable
fun Artwork(art: android.graphics.Bitmap?, size: Dp, corner: Dp, modifier: Modifier = Modifier) {
    val img = remember(art) { art?.asImageBitmap() }
    if (img != null) {
        Image(img, null, contentScale = ContentScale.Crop, modifier = modifier.size(size).clip(RoundedCornerShape(corner)))
    } else {
        Box(modifier.size(size).clip(RoundedCornerShape(corner)).background(IslandColors.ButtonFill), contentAlignment = Alignment.Center) {
            Glyph(IslandIcons.Music, IslandColors.Gray, size * 0.55f)
        }
    }
}

// ---- ボタン ----

/**
 * 丸いボタン。押している間だけ少し縮む（iPhone の押し心地）。
 * タップは子で消費するので、島本体のタップ（アプリを開く）とは衝突しない。
 */
@Composable
fun CircleButton(
    icon: ImageVector,
    label: String,
    background: Color,
    tint: Color,
    size: Dp = 44.dp,
    iconSize: Dp = size * 0.5f,
    onClick: () -> Unit,
) {
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    Box(
        Modifier
            .size(size)
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .clip(CircleShape)
            .background(background)
            .semantics {
                contentDescription = label
                role = Role.Button
            }
            .pointerInput(onClick) {
                detectTapGestures(
                    onPress = {
                        scope.launch { scale.animateTo(0.88f, spring(stiffness = 900f)) }
                        tryAwaitRelease()
                        scope.launch { scale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 500f)) }
                    },
                    onTap = {
                        haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                        onClick()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Glyph(icon, tint, iconSize)
    }
}

/** ボタンの意味から、iPhone の時計・電話に近い見た目を選ぶ */
data class RoleStyle(val icon: ImageVector, val background: Color, val tint: Color)

fun styleOf(role: ActionRole, accent: Color = IslandColors.Orange): RoleStyle = when (role) {
    ActionRole.Play -> RoleStyle(IslandIcons.Play, accent.copy(alpha = 0.28f), accent)
    ActionRole.Pause -> RoleStyle(IslandIcons.Pause, accent.copy(alpha = 0.28f), accent)
    ActionRole.Lap -> RoleStyle(IslandIcons.Flag, IslandColors.ButtonFill, Color.White)
    ActionRole.Reset -> RoleStyle(IslandIcons.Reset, IslandColors.ButtonFill, Color.White)
    ActionRole.Stop -> RoleStyle(IslandIcons.Stop, accent, Color.Black)
    ActionRole.Snooze -> RoleStyle(IslandIcons.Snooze, IslandColors.ButtonFill, Color.White)
    ActionRole.AddTime -> RoleStyle(IslandIcons.Plus, IslandColors.ButtonFill, Color.White)
    ActionRole.Cancel -> RoleStyle(IslandIcons.Close, IslandColors.ButtonFill, Color.White)
    ActionRole.Answer -> RoleStyle(IslandIcons.Phone, IslandColors.Green, Color.White)
    ActionRole.Decline -> RoleStyle(IslandIcons.PhoneDown, IslandColors.Red, Color.White)
    ActionRole.HangUp -> RoleStyle(IslandIcons.PhoneDown, IslandColors.Red, Color.White)
    ActionRole.Speaker -> RoleStyle(IslandIcons.Speaker, IslandColors.ButtonFill, Color.White)
    ActionRole.Mute -> RoleStyle(IslandIcons.MicOff, IslandColors.ButtonFill, Color.White)
    ActionRole.Generic -> RoleStyle(IslandIcons.Plus, IslandColors.ButtonFill, Color.White)
}

// ---- 画像の読み込み ----

private val iconCache = LruCache<String, ImageBitmap>(48)

/** 通知のアイコン（android.graphics.drawable.Icon）を裏で読む */
@Composable
fun rememberIconBitmap(icon: Icon?, sizePx: Int): ImageBitmap? {
    val context = LocalContext.current
    val v by produceState<ImageBitmap?>(null, icon, sizePx) {
        value = if (icon == null) null else withContext(Dispatchers.IO) { loadIcon(context, icon, sizePx) }
    }
    return v
}

private fun loadIcon(context: Context, icon: Icon, sizePx: Int): ImageBitmap? = runCatching {
    icon.loadDrawable(context)?.toBitmap(sizePx, sizePx)?.asImageBitmap()
}.getOrNull()

@Composable
fun rememberAppIcon(packageName: String, sizePx: Int): ImageBitmap? {
    val context = LocalContext.current
    val key = "$packageName@$sizePx"
    val v by produceState(iconCache.get(key), key) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName).toBitmap(sizePx, sizePx).asImageBitmap()
            }.getOrNull()?.also { iconCache.put(key, it) }
        }
    }
    return v
}

@Composable
fun BitmapOr(bitmap: ImageBitmap?, size: Dp, tint: Color? = null, shape: androidx.compose.ui.graphics.Shape? = null, fallback: @Composable () -> Unit) {
    if (bitmap == null) {
        fallback()
        return
    }
    Image(
        bitmap,
        null,
        colorFilter = tint?.let { ColorFilter.tint(it) },
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(size).let { if (shape != null) it.clip(shape) else it },
    )
}

/** 通知の色は黒地で沈むことがあるので明るくする。0（未指定）は白 */
fun readableColor(argb: Int): Color {
    if (argb == 0 || android.graphics.Color.alpha(argb) < 32) return Color.White
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(argb, hsl)
    if (hsl[1] < 0.12f) return Color.White
    hsl[2] = hsl[2].coerceIn(0.55f, 0.78f)
    return Color(ColorUtils.HSLToColor(hsl))
}
