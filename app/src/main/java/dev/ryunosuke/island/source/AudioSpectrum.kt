package dev.ryunosuke.island.source

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin

/**
 * 端末から出ている音（全体の出力のミックス = オーディオセッション 0）の周波数ごとの強さ。
 * 音楽の波形を実際の音に合わせるのに使う。録音はしない（Visualizer から直近 21ms ぶんの波形を受け取って解析するだけ）。
 *
 * 一般的なイコライザと同じく、棒ごとに受け持つ周波数を決めてある（EqLayout）。一番左は 30〜80Hz の低音だけ。
 *
 * 波形が画面に出ている間だけ動かす（acquire / release の数を数える）。
 * RECORD_AUDIO が無い・Visualizer が作れない端末では何もせず、呼ぶ側が擬似的な波に戻す。
 */
class AudioSpectrum(private val context: Context) {
    private val thread by lazy { HandlerThread("island-spectrum").apply { start() } }
    private val handler by lazy { Handler(thread.looper) }

    private var visualizer: Visualizer? = null
    private var users = 0

    /** 棒の本数ごとの解析。読むのは描画のスレッドなので、結果は配列ごと差し替える */
    private val analyzers = EqLayout.ALL.map { EqAnalyzer(it) }

    @Volatile
    private var current: Map<Int, FloatArray> = emptyMap()

    /** 最後に音の情報が届いた時刻。届かなくなったら擬似的な波に戻す */
    @Volatile
    var updatedAt = 0L
        private set

    private var lastCapture = 0L

    @Volatile
    private var soundAt = 0L

    fun permitted() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** 使い始める。音の情報を取れるなら true */
    @Synchronized
    fun acquire(): Boolean {
        users++
        if (users == 1) handler.post(::start)
        return permitted()
    }

    @Synchronized
    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) handler.post(::stop)
    }

    /**
     * 実際の音で描けるか。解析が届いていて、しばらく無音が続いていないこと
     * （音量 0 のときや、Visualizer に音が届かない端末では擬似的な波に戻す）
     */
    fun isLive(now: Long = SystemClock.elapsedRealtime()) = now - updatedAt < STALE_MS && now - soundAt < SILENT_FALLBACK_MS

    /** bars 本の棒の高さ（左が低い音）。棒の本数に合った周波数の割り当て（EqLayout）で解析したもの */
    fun levels(bars: Int, out: FloatArray) {
        val src = current[EqLayout.forBars(bars).bars]
        for (i in 0 until bars) out[i] = src?.getOrNull(i) ?: 0f
    }

    private fun start() {
        if (visualizer != null || !permitted()) return
        runCatching {
            val v = Visualizer(0)
            v.enabled = false
            val range = Visualizer.getCaptureSizeRange()
            v.captureSize = CAPTURE_SIZE.coerceIn(range[0], range[1])
            v.scalingMode = Visualizer.SCALING_MODE_NORMALIZED
            // Visualizer の FFT は窓をかけていないので、150〜250Hz の音が一番低い帯に漏れる。波形を受け取って自分で解析する
            v.setDataCaptureListener(listener, Visualizer.getMaxCaptureRate(), true, false)
            v.enabled = true
            visualizer = v
            Log.i(TAG, "開始 size=${v.captureSize} rate=${Visualizer.getMaxCaptureRate() / 1000}Hz")
        }.onFailure { Log.w(TAG, "Visualizer を作れない", it) }
    }

    private fun stop() {
        val v = visualizer ?: return
        visualizer = null
        runCatching { v.enabled = false }
        v.release()
        current = emptyMap()
        analyzers.forEach { it.reset() }
        updatedAt = 0L
        lastCapture = 0L
        Log.i(TAG, "停止")
    }

    private val listener = object : Visualizer.OnDataCaptureListener {
        override fun onWaveFormDataCapture(v: Visualizer, waveform: ByteArray, samplingRate: Int) = process(waveform, samplingRate)

        override fun onFftDataCapture(v: Visualizer, fft: ByteArray, samplingRate: Int) = Unit
    }

    private fun process(waveform: ByteArray, samplingRate: Int) {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastCapture == 0L) 0.05f else ((now - lastCapture) / 1000f).coerceIn(0.01f, 0.5f)
        lastCapture = now
        val power = EqSpectrum.power(waveform)
        val sampleRateHz = samplingRate / 1000f
        current = analyzers.associate { it.layout.bars to it.update(power, sampleRateHz, dt) }
        if (!analyzers.first().silent) soundAt = now
        updatedAt = now
    }

    companion object {
        private const val TAG = "IslandSpectrum"
        private const val CAPTURE_SIZE = 1024
        private const val STALE_MS = 600L
        private const val SILENT_FALLBACK_MS = 1_500L
    }
}

/**
 * 棒ごとに受け持つ周波数と、伸び始める強さ（しきい値）。一般的なイコライザと同じく固定で、曲や直近の音には合わせない。
 *
 * 強さは「フルスケールの正弦波 = 0dB」の dB。Visualizer は音量に関係なく音を目いっぱいまで引き伸ばして渡してくるので、
 * そのときいちばん大きい音（ヒップホップならたいてい 808 やキック）に対して何 dB か、になる。
 * しきい値は、音楽ではふつう高い周波数ほどエネルギーが小さいので、右の棒ほど低い。
 * 一番左は 30〜80Hz だけを見て、しきい値 −14dB。低音を 110Hz で切った曲では上位 5% のコマでも −14.5dB を超えず、
 * 棒は伸びない（ボーカルやベースの上の方の「中途半端な低音」は 2 本目が受け持つ）。
 * 値は 5 曲と合成したヒップホップのビートを、Visualizer と同じ正規化・8bit で再現して合わせた。
 */
class EqLayout(
    val bars: Int,
    /** 棒ごとの [下端, 上端] Hz */
    val bandsHz: List<ClosedFloatingPointRange<Float>>,
    /** 伸び始める強さ（dB） */
    val thresholdDb: FloatArray,
    /** しきい値から何 dB 上で最大になるか */
    val rangeDb: FloatArray,
) {
    companion object {
        /** Android 標準の 5 バンド イコライザと同じ中心: 60Hz / 230Hz / 910Hz / 3.6kHz / 14kHz */
        val FIVE = EqLayout(
            5,
            listOf(30f..80f, 80f..450f, 450f..1_800f, 1_800f..7_000f, 7_000f..16_000f),
            floatArrayOf(-14f, -19f, -28f, -39f, -51f),
            floatArrayOf(12f, 10f, 10f, 11f, 12f),
        )

        /** 展開時の 6 本: 60Hz / 150Hz / 400Hz / 1kHz / 2.5kHz / 8kHz */
        val SIX = EqLayout(
            6,
            listOf(30f..80f, 80f..250f, 250f..630f, 630f..1_600f, 1_600f..4_000f, 4_000f..16_000f),
            floatArrayOf(-14f, -19f, -24f, -29f, -35f, -47f),
            floatArrayOf(12f, 11f, 10f, 11f, 10f, 11f),
        )

        val ALL = listOf(FIVE, SIX)

        fun forBars(bars: Int) = ALL.firstOrNull { it.bars == bars } ?: FIVE
    }
}

/** Visualizer の波形（符号なし 8bit）に窓をかけて FFT する。Android に依存しないので単体テストできる */
object EqSpectrum {
    private val cache = HashMap<Int, Tables>()

    private class Tables(n: Int) {
        val hann = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }
        val cos = FloatArray(n / 2) { cos(2 * PI * it / n).toFloat() }
        val sin = FloatArray(n / 2) { sin(2 * PI * it / n).toFloat() }
        val rev = IntArray(n).also { r ->
            val bits = Integer.numberOfTrailingZeros(n)
            for (i in 0 until n) r[i] = Integer.reverse(i) ushr (32 - bits)
        }
    }

    /**
     * 周波数ごとの強さ（0〜n/2 番目の bin）。フルスケールの正弦波が 1（0dB）になるように割ってある。
     * waveform の長さは 2 の累乗（Visualizer のキャプチャ長）
     */
    fun power(waveform: ByteArray): FloatArray {
        val n = waveform.size
        val t = synchronized(cache) { cache.getOrPut(n) { Tables(n) } }
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until n) {
            // 符号なし 8bit（128 が 0）
            re[t.rev[i]] = ((waveform[i].toInt() and 0xFF) - 128) * t.hann[i]
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                for (k in 0 until half) {
                    val c = t.cos[k * step]
                    val s = -t.sin[k * step]
                    val a = start + k
                    val b = a + half
                    val tr = re[b] * c - im[b] * s
                    val ti = re[b] * s + im[b] * c
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
                start += size
            }
            size *= 2
        }
        // フルスケール（振幅 127）の正弦波は、ハン窓で |X| = 127 × n / 4
        val full = 127f * n / 4f
        val norm = 1f / (full * full)
        return FloatArray(n / 2 + 1) { (re[it] * re[it] + im[it] * im[it]) * norm }
    }

    /** lo〜hi Hz の強さ（dB）。帯の端にかかる bin は、かかっている幅の割合で数える */
    fun bandDb(power: FloatArray, sampleRateHz: Float, lo: Float, hi: Float): Float {
        val n = (power.size - 1) * 2
        val binHz = sampleRateHz / n
        var sum = 0f
        var weight = 0f
        for (k in 1 until power.size - 1) {
            val a = maxOf(lo, (k - 0.5f) * binHz)
            val b = minOf(hi, (k + 0.5f) * binHz)
            if (b <= a) continue
            val w = (b - a) / binHz
            sum += power[k] * w
            weight += w
        }
        return 10f * log10(sum / maxOf(weight, 1e-9f) + 1e-12f)
    }
}

/** 1 つの EqLayout の棒の高さ（0〜1）。しきい値を超えた分だけ伸ばし、立ち上がりは速く落ちるのは少しゆっくりにする */
class EqAnalyzer(val layout: EqLayout) {
    private val smoothed = FloatArray(layout.bars)

    /** 直近の入力が無音だったか */
    var silent = true
        private set

    fun reset() {
        smoothed.fill(0f)
        silent = true
    }

    fun update(power: FloatArray, sampleRateHz: Float, dt: Float): FloatArray {
        val db = FloatArray(layout.bars) { layout.bandsHz[it].let { r -> EqSpectrum.bandDb(power, sampleRateHz, r.start, r.endInclusive) } }
        silent = db.max() < SILENCE_DB
        val out = FloatArray(layout.bars)
        for (i in 0 until layout.bars) {
            val target = if (silent) 0f else ((db[i] - layout.thresholdDb[i]) / layout.rangeDb[i]).coerceIn(0f, 1f)
            val tau = if (target > smoothed[i]) ATTACK_S else RELEASE_S
            smoothed[i] += (target - smoothed[i]) * (1f - exp(-dt / tau))
            out[i] = smoothed[i]
        }
        return out
    }

    companion object {
        /** これより小さい（全部の棒で）ときは無音 */
        private const val SILENCE_DB = -60f
        private const val ATTACK_S = 0.025f
        private const val RELEASE_S = 0.10f
    }
}
