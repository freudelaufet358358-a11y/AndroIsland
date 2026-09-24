package dev.ryunosuke.island.ui.island

import android.graphics.RuntimeShader
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * 島の黒い形。本体と右の島をどちらも「超楕円の角を持つ角丸矩形」の距離で表す。
 * 右の島が離れていくときは、間に細くなっていく「首」（横長のカプセル）を置き、smooth-min でなめらかにつなぐ。
 * 首が細りきるとちぎれる（iPhone の見本では分離の始めから 0.7 秒ほど）。
 * つなぎ目の k を大きくするだけだと継ぎ目の周りが上下に膨らんでしまうので、首は明示的に描く。
 *
 * 角: 半径 r（角が縁に沿って占める長さ）と指数 n。n = 2 で円弧（コンパクトの両端は半円）、
 * 展開時は n = 3.2（Apple の映像を実測。iOS の「なめらかな角」）。
 */
private const val ISLAND_SHADER = """
uniform float4 mainRect;
uniform float mainRadius;
uniform float mainExponent;
uniform float4 bubble;      // 中心 x, y と半分の幅・高さ
uniform float neck;         // 首の半分の太さ（0 なら首なし）
uniform float smoothK;
uniform float shadow;
uniform float shadowRadius;
uniform float alpha;

float sdSquircleRect(float2 p, float2 c, float2 h, float r, float n) {
    float2 q = abs(p - c) - h + r;
    if (q.x > 0.0 && q.y > 0.0) {
        return pow(pow(q.x, n) + pow(q.y, n), 1.0 / n) - r;
    }
    return max(q.x, q.y) - r;
}

float smin(float a, float b, float k) {
    if (k < 0.01) return min(a, b);
    float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
    return mix(b, a, h) - k * h * (1.0 - h);
}

// 横向きの線分 [x0, x1]（高さ y）からの距離 − 太さ
float sdCapsule(float2 p, float x0, float x1, float y, float r) {
    float x = clamp(p.x, x0, x1);
    return length(p - float2(x, y)) - r;
}

half4 main(float2 p) {
    float2 c = (mainRect.xy + mainRect.zw) * 0.5;
    float2 h = max((mainRect.zw - mainRect.xy) * 0.5, float2(0.0));
    float r = min(mainRadius, min(h.x, h.y));
    float d = sdSquircleRect(p, c, h, r, mainExponent);
    if (bubble.z > 0.5 && bubble.w > 0.5) {
        float br = min(bubble.z, bubble.w);
        float db = sdSquircleRect(p, bubble.xy, bubble.zw, br, 2.0);
        d = smin(d, db, smoothK);
        if (neck > 0.3) {
            // 本体の右端の丸の中心から、右の島の中心まで
            float x0 = mainRect.z - r;
            float dn = sdCapsule(p, min(x0, bubble.x), bubble.x, bubble.y, neck);
            d = smin(d, dn, smoothK);
        }
    }
    float fill = clamp(0.5 - d, 0.0, 1.0);
    float sh = shadow * 0.35 * exp(-max(d, 0.0) / shadowRadius) * (1.0 - fill);
    return half4(0.0, 0.0, 0.0, (fill + sh) * alpha);
}
"""

/** 描画中の形。すべて窓の座標のピクセル */
class ShapeFrame(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val radius: Float,
    val exponent: Float,
    val bubbleX: Float,
    val bubbleY: Float,
    val bubbleHalfW: Float,
    val bubbleHalfH: Float,
    /** 首の半分の太さ（px） */
    val neck: Float,
    /** つなぎ目のなめらかさ（px） */
    val smoothK: Float,
    val shadow: Float,
    val alpha: Float,
)

@Composable
fun IslandShapeCanvas(modifier: Modifier, density: Float, frame: () -> ShapeFrame) {
    val shader = remember { RuntimeShader(ISLAND_SHADER) }
    val brush = remember(shader) { ShaderBrush(shader) }
    Canvas(modifier) {
        val f = frame()
        val k = f.smoothK
        if (f.alpha <= 0.001f) return@Canvas
        val shadowR = 14f * density
        shader.setFloatUniform("mainRect", f.left, f.top, f.right, f.bottom)
        shader.setFloatUniform("mainRadius", f.radius)
        shader.setFloatUniform("mainExponent", f.exponent)
        shader.setFloatUniform("bubble", f.bubbleX, f.bubbleY, f.bubbleHalfW, f.bubbleHalfH)
        shader.setFloatUniform("smoothK", k)
        shader.setFloatUniform("neck", f.neck)
        shader.setFloatUniform("shadow", f.shadow)
        shader.setFloatUniform("shadowRadius", shadowR)
        shader.setFloatUniform("alpha", f.alpha)
        // 描く範囲は形と影が収まるところだけ
        val pad = if (f.shadow > 0f) shadowR * 4 else 2f * density + k / 4
        // 首のカプセルの丸い端は右の島より外に出ることがある
        val bxr = maxOf(f.bubbleHalfW, f.neck)
        val byr = maxOf(f.bubbleHalfH, f.neck)
        val l = minOf(f.left, f.bubbleX - bxr) - pad
        val t = minOf(f.top, f.bubbleY - byr) - pad
        val r = maxOf(f.right, f.bubbleX + bxr) + pad
        val b = maxOf(f.bottom, f.bubbleY + byr) + pad
        val l0 = l.coerceAtLeast(0f)
        val t0 = t.coerceAtLeast(0f)
        drawRect(brush, topLeft = Offset(l0, t0), size = Size((r - l0).coerceAtLeast(0f), (b - t0).coerceAtLeast(0f)))
    }
}

/** 中身を切り抜く形。シェーダと同じ超楕円の角（1 つの角を 12 点で近似） */
class SquircleShape(private val radius: Float, private val exponent: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = radius.coerceIn(0f, minOf(size.width, size.height) / 2)
        if (r <= 0.5f) return Outline.Rectangle(androidx.compose.ui.geometry.Rect(Offset.Zero, size))
        val p = Path()
        val steps = 12
        // 角の中心と、そこからの向き（右上から時計回り）
        val corners = listOf(
            Triple(size.width - r, r, -PI / 2),
            Triple(size.width - r, size.height - r, 0.0),
            Triple(r, size.height - r, PI / 2),
            Triple(r, r, PI),
        )
        var first = true
        for ((cx, cy, start) in corners) {
            for (i in 0..steps) {
                val a = start + (PI / 2) * i / steps
                val c = cos(a)
                val s = sin(a)
                // 超楕円 |x|^n + |y|^n = r^n
                val e = 2.0 / exponent
                val x = cx + r * sign(c) * abs(c).pow(e).toFloat()
                val y = cy + r * sign(s) * abs(s).pow(e).toFloat()
                if (first) { p.moveTo(x, y); first = false } else p.lineTo(x, y)
            }
        }
        p.close()
        return Outline.Generic(p)
    }

    private fun sign(v: Double) = if (v < 0) -1f else 1f
}
