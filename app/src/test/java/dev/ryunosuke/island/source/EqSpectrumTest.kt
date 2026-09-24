package dev.ryunosuke.island.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class EqSpectrumTest {
    private val rate = 48_000f
    private val n = 1024

    /** Visualizer と同じ形（符号なし 8bit、128 が 0）の正弦波 */
    private fun tone(hz: Float, amplitude: Float = 120f, offset: Int = 0) = ByteArray(n) {
        ((amplitude * sin(2 * PI * hz * (it + offset) / rate)).roundToInt() + 128).coerceIn(0, 255).toByte()
    }

    /** 同じ音を 1 秒ぶん（20 コマ）流したあとの棒の高さ */
    private fun bars(hz: Float, layout: EqLayout = EqLayout.FIVE): FloatArray {
        val a = EqAnalyzer(layout)
        var out = FloatArray(layout.bars)
        repeat(20) { out = a.update(EqSpectrum.power(tone(hz, offset = it * 2400)), rate, 0.05f) }
        return out
    }

    @Test
    fun fullScaleSineIsZeroDecibels() {
        // bin の真ん中（1kHz 付近の bin 21 = 984.4Hz）のフルスケールの正弦波。その bin だけを見ると 0dB
        val binHz = rate / n
        val p = EqSpectrum.power(tone(21 * binHz, amplitude = 127f))
        assertEquals(1f, p[21], 0.02f)
        assertEquals(0f, EqSpectrum.bandDb(p, rate, 20.5f * binHz, 21.5f * binHz), 0.1f)
    }

    @Test
    fun deepBassRaisesTheLeftBar() {
        val b = bars(55f)
        assertTrue("55Hz で左の棒が伸びる ${b.toList()}", b[0] > 0.8f)
        assertTrue("55Hz で右の 3 本は伸びない ${b.toList()}", b.drop(2).all { it < 0.1f })
    }

    @Test
    fun midBassDoesNotRaiseTheLeftBar() {
        // ラップの声やベースの上の方（150〜250Hz）は 2 本目。一番左は伸びない（前の作りでは伸びていた）
        for (hz in listOf(160f, 180f, 220f)) {
            val b = bars(hz)
            assertTrue("${hz}Hz で左の棒は伸びない ${b.toList()}", b[0] < 0.05f)
            assertTrue("${hz}Hz で 2 本目が伸びる ${b.toList()}", b[1] > 0.8f)
        }
        val six = bars(180f, EqLayout.SIX)
        assertTrue("6 本でも 180Hz で左の棒は伸びない ${six.toList()}", six[0] < 0.05f)
    }

    @Test
    fun eachBarHasItsOwnFrequency() {
        // 一般的なイコライザと同じく、棒ごとに周波数が決まっている（60 / 230 / 910 / 3.6k / 14k）
        assertEquals(2, bars(1_000f).indices.maxBy { bars(1_000f)[it] })
        assertEquals(3, bars(3_600f).indices.maxBy { bars(3_600f)[it] })
        assertEquals(4, bars(10_000f).indices.maxBy { bars(10_000f)[it] })
    }

    @Test
    fun silenceIsFlat() {
        val a = EqAnalyzer(EqLayout.FIVE)
        var out = FloatArray(5)
        repeat(10) { out = a.update(EqSpectrum.power(ByteArray(n) { 128.toByte() }), rate, 0.05f) }
        assertTrue(a.silent)
        assertTrue(out.all { it == 0f })
    }
}

/**
 * ヒップホップ風のビート（808・スネア・ハイハット・ラップ風の声）で、低音が鳴っていないときに一番左の棒が伸びないこと。
 * Visualizer と同じく 10ms ごとに音量を目いっぱいまで引き伸ばし、8bit にしてから 50ms ごとに直近 1024 サンプルを解析する。
 */
class EqHipHopTest {
    private val rate = 48_000

    private fun beat(with808: Boolean, seconds: Double = 10.0): DoubleArray {
        val n = (rate * seconds).toInt()
        val x = DoubleArray(n)
        val rnd = java.util.Random(7)
        val beat = 60.0 / 90 // 90BPM
        var b = 0.0
        while (b < seconds) {
            val i0 = (b * rate).toInt()
            if (with808) {
                // 808: 55Hz から少し下がりながら 0.45 秒で減衰
                var phase = 0.0
                for (j in 0 until minOf((0.45 * rate).toInt(), n - i0)) {
                    val t = j.toDouble() / rate
                    phase += 2 * Math.PI * 55 * kotlin.math.exp(-t * 0.6) / rate
                    x[i0 + j] += 0.8 * kotlin.math.sin(phase) * kotlin.math.exp(-t * 5)
                }
            }
            // ハイハット（8 分）
            for (h in 0..1) {
                val s = ((b + h * beat / 2) * rate).toInt()
                var prev = 0.0
                for (j in 0 until minOf((0.04 * rate).toInt(), n - s)) {
                    val v = rnd.nextGaussian()
                    x[s + j] += 0.12 * (v - prev) * kotlin.math.exp(-j.toDouble() / rate * 80)
                    prev = v
                }
            }
            b += beat
        }
        // スネア（2・4 拍）
        b = beat
        while (b < seconds) {
            val s = (b * rate).toInt()
            for (j in 0 until minOf((0.15 * rate).toInt(), n - s)) x[s + j] += 0.35 * rnd.nextGaussian() * kotlin.math.exp(-j.toDouble() / rate * 25)
            b += beat * 2
        }
        // ラップ風の声: 120〜180Hz の基音と倍音を 0.18 秒ごとの音節で
        var syl = 0.1
        while (syl < seconds) {
            val s = (syl * rate).toInt()
            val len = minOf((0.13 * rate).toInt(), n - s)
            val f0 = 120 + rnd.nextDouble() * 60
            for (j in 0 until len) {
                val t = j.toDouble() / rate
                var v = 0.0
                for (h in 1..11) v += kotlin.math.sin(2 * Math.PI * f0 * h * t) / h
                x[s + j] += 0.25 * v * kotlin.math.sin(Math.PI * j / len)
            }
            syl += 0.18
        }
        return x
    }

    /** Visualizer の波形キャプチャを再現して、左の棒の高さを並べる */
    private fun leftBar(x: DoubleArray): FloatArray {
        val y = DoubleArray(x.size)
        var s = 0
        while (s < x.size) {
            val e = minOf(s + 480, x.size)
            var m = 0.0
            for (i in s until e) m = maxOf(m, kotlin.math.abs(x[i]))
            for (i in s until e) y[i] = if (m > 1e-9) x[i] * 0.99 / m else 0.0
            s = e
        }
        val a = EqAnalyzer(EqLayout.FIVE)
        val out = ArrayList<Float>()
        var end = 1024
        while (end <= y.size) {
            val wave = ByteArray(1024) { i -> ((y[end - 1024 + i] * 128).toInt().coerceIn(-128, 127) + 128).toByte() }
            out += a.update(EqSpectrum.power(wave), rate.toFloat(), 0.05f)[0]
            end += 2400
        }
        return out.toFloatArray()
    }

    @Test
    fun leftBarStaysDownWithoutBass() {
        val v = leftBar(beat(with808 = false))
        assertTrue("808 なしで左の棒の最大 ${v.max()}", v.max() < 0.1f)
    }

    @Test
    fun leftBarPumpsWith808() {
        val v = leftBar(beat(with808 = true))
        assertTrue("808 ありで左の棒の最大 ${v.max()}", v.max() > 0.8f)
        assertTrue("808 ありで左の棒の平均 ${v.average()}", v.average() > 0.3)
    }
}
