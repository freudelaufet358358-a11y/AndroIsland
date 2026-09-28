package dev.ryunosuke.island.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log
import android.view.Display
import dev.ryunosuke.island.IslandApp
import dev.ryunosuke.island.island.CallActivity
import dev.ryunosuke.island.island.DemoController
import dev.ryunosuke.island.island.IslandActivity
import dev.ryunosuke.island.island.IslandAlert
import dev.ryunosuke.island.island.MediaActivity
import dev.ryunosuke.island.source.ShizukuShell
import kotlinx.coroutines.launch

/**
 * 実機確認のスクリプト（verify-on-device.sh）から島を操作する入口。
 * DUMP 権限で守っているので adb shell からしか送れない（他のアプリが偽の着信を出すことはできない）。
 *
 *   adb shell am broadcast -n dev.ryunosuke.island/.debug.ShellCommandReceiver -a dev.ryunosuke.island.SHELL --es cmd "demo Media"
 *
 * cmd: demo <Type> / alert <charging|battery|silent|ring|dnd|device|unlock> / clear /
 *      expand / collapse / media start|stop / state / idle on|off / refonly on|off / refseq / refseq26 /
 *      tone <Hz> <秒>（ごく小さな音を鳴らし、波形の解析結果をログに出す） /
 *      playraw <名前>（アプリ専用フォルダの 48kHz・モノラル・16bit の生データを、試験用の MediaSession を立てて鳴らす） /
 *      shizuku（Shizuku の状態と、Shizuku で読んだ最近のタスクのアプリを "shizuku=…" の 1 行でログに出す） /
 *      statusbar（ステータスバーの空きの状態と、端末が今報告しているカメラ穴の上端の矩形を "statusbar=…" の 1 行でログに出す）
 */
class ShellCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val g = IslandApp.graph
        val args = intent.getStringExtra("cmd").orEmpty().trim().split(Regex("\\s+"))
        when (args.firstOrNull()) {
            "demo" -> args.getOrNull(1)?.let { name ->
                DemoController.Type.entries.firstOrNull { it.name.equals(name, true) }?.let(g.demo::show)
            }
            "alert" -> alertOf(args.getOrNull(1))?.let(g.arbiter::postAlert)
            "clear" -> g.demo.clear()
            "expand" -> g.arbiter.presentation.value.primary?.let { g.arbiter.expand(it.key) }
            "collapse" -> g.arbiter.collapse()
            "media" -> if (args.getOrNull(1) == "stop") TestMediaSession.stop() else TestMediaSession.start(context.applicationContext)
            "state" -> Unit
            "windows" -> Log.i(TAG, "windows ${dev.ryunosuke.island.overlay.IslandOverlayService.debugInfo}")
            "idle" -> g.forceIdle.value = args.getOrNull(1) != "off"
            "autosize" -> g.forceAutoSize.value = args.getOrNull(1) != "off"
            "visible" -> g.forceVisible.value = args.getOrNull(1) != "off"
            "refonly" -> g.referenceOnly.value = args.getOrNull(1) != "off"
            "tone" -> DebugTone.play(args.getOrNull(1)?.toFloatOrNull() ?: 200f, args.getOrNull(2)?.toFloatOrNull() ?: 2f)
            "playraw" -> DebugTone.playRaw(context.applicationContext, args.getOrNull(1) ?: "wave.raw")
            "refseq" -> ReferenceSequence.run()
            "refseq26" -> ReferenceSequence.runIos26()
            "shizuku" -> {
                val app = context.applicationContext
                ShizukuShell.handler.post {
                    val tasks = if (ShizukuShell.isReady()) {
                        runCatching { ShizukuShell.recentTaskPackages().sorted().toString() }.getOrElse { "error:${it.javaClass.simpleName}" }
                    } else "-"
                    Log.i(TAG, "shizuku=${ShizukuShell.status(app)} uid=${ShizukuShell.uid()} tasks=$tasks")
                }
            }
            "statusbar" -> {
                val rect = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                    ?.cutout?.boundingRectTop?.toShortString()
                Log.i(
                    TAG,
                    "statusbar=${g.statusBarGap.state.value} on=${g.settings.value.shiftStatusBar} cutout=$rect " +
                        "lockscreenPending=${g.statusBarGap.lockScreenPending.value}",
                )
            }
        }
        Log.i(TAG, "cmd=${args.joinToString(" ")} state=${describe()}")
    }

    private fun alertOf(name: String?): IslandAlert? = when (name) {
        "charging" -> IslandAlert.Charging(76)
        "battery" -> IslandAlert.LowBattery(10)
        "silent" -> IslandAlert.Ringer(AudioManager.RINGER_MODE_SILENT)
        "vibrate" -> IslandAlert.Ringer(AudioManager.RINGER_MODE_VIBRATE)
        "ring" -> IslandAlert.Ringer(AudioManager.RINGER_MODE_NORMAL)
        "dnd" -> IslandAlert.Dnd(true)
        "device" -> IslandAlert.Device("AirPods Pro", 82, wired = false)
        "unlock" -> IslandAlert.Unlock
        else -> null
    }

    companion object {
        const val TAG = "IslandShell"

        /** スクリプトが grep で確かめられる 1 行の要約 */
        fun describe(): String {
            val p = IslandApp.graph.arbiter.presentation.value
            fun d(a: IslandActivity?) = when (a) {
                null -> "-"
                is MediaActivity -> "Media(${a.title},playing=${a.playing})"
                is CallActivity -> "Call(${a.name},incoming=${a.incoming})"
                else -> "${a.kind}(${a.key})"
            }
            val region = dev.ryunosuke.island.overlay.OverlayHost.lastRegion.joinToString(";") { "${it.left},${it.top},${it.right},${it.bottom}" }
            return "primary=${d(p.primary)} secondary=${d(p.secondary)} expanded=${p.expanded} hidden=${p.hidden} alert=${p.alert?.type ?: "-"} region=${region.ifEmpty { "-" }} recents=${IslandApp.graph.recents.isOpenOrJustClosed()}"
        }
    }
}

/**
 * Apple の WWDC23「Design dynamic Live Activities」11:10（670 秒）からの島の見本と同じ順番・同じ間隔で
 * 島を動かす。録画して見本と 1 フレームずつ重ねるため。時刻は見本の映像の 670 秒からの経過。
 *
 * 時刻は見本の動き出しに合わせて当てはめたもの。
 *  0.935 待機 → コンパクト（5.97H）   2.977 → 待機   3.364 → 展開（高さ 3.50H）
 *  5.645 → 待機   6.294 → 2 つ同時（主は左に伸び、右に離れた島）   8.50 → 待機
 */
object ReferenceSequence {
    private const val A = "ref:a"
    private const val B = "ref:b"
    private var job: kotlinx.coroutines.Job? = null

    fun run() {
        val g = IslandApp.graph
        job?.cancel()
        job = g.scope.launch {
            g.demo.clear()
            g.referenceOnly.value = true
            g.forceAutoSize.value = true
            g.forceIdle.value = true
            val start = SystemClock.elapsedRealtime()
            suspend fun at(sec: Double) {
                val wait = (start + (sec * 1000).toLong()) - SystemClock.elapsedRealtime()
                if (wait > 0) kotlinx.coroutines.delay(wait)
            }
            val compact = dev.ryunosuke.island.island.ReferenceActivity(A, compactWidthH = 5.97f, expandedHeightH = 3.50f)
            Log.i(ShellCommandReceiver.TAG, "refseq start")
            at(0.935); Log.i(ShellCommandReceiver.TAG, "refseq t=0.935 compact"); g.demo.put(compact)
            at(2.977); Log.i(ShellCommandReceiver.TAG, "refseq t=2.977 idle"); g.demo.remove(A)
            at(3.364); Log.i(ShellCommandReceiver.TAG, "refseq t=3.364 expanded"); g.demo.put(compact); g.arbiter.expand(A)
            at(5.645); Log.i(ShellCommandReceiver.TAG, "refseq t=5.645 idle"); g.arbiter.collapse(); g.demo.remove(A)
            at(6.294); Log.i(ShellCommandReceiver.TAG, "refseq t=6.294 split")
            g.demo.put(dev.ryunosuke.island.island.ReferenceActivity(B, 5.97f, 3.50f))
            g.demo.put(compact)
            at(8.50); Log.i(ShellCommandReceiver.TAG, "refseq t=8.50 idle"); g.demo.clear()
            at(9.50); g.forceIdle.value = false; g.forceAutoSize.value = false; g.referenceOnly.value = false
            Log.i(ShellCommandReceiver.TAG, "refseq end")
        }
    }

    /**
     * iOS 26 の見本（test-data/reference-ios26-short.csv）と同じ 5 つの遷移を順に起こす。
     * 待機 → 電池残量低下（展開した形、2.8H）→ 待機 → コンパクト（8.57H）→ 展開（3.81H）→ 待機
     */
    fun runIos26() {
        val g = IslandApp.graph
        job?.cancel()
        job = g.scope.launch {
            g.demo.clear()
            g.referenceOnly.value = true
            g.forceAutoSize.value = true
            g.forceIdle.value = true
            val start = SystemClock.elapsedRealtime()
            suspend fun at(sec: Double) {
                val wait = (start + (sec * 1000).toLong()) - SystemClock.elapsedRealtime()
                if (wait > 0) kotlinx.coroutines.delay(wait)
            }
            val compact = dev.ryunosuke.island.island.ReferenceActivity(A, compactWidthH = 8.57f, expandedHeightH = 3.81f)
            Log.i(ShellCommandReceiver.TAG, "refseq26 start")
            at(0.5); g.arbiter.postAlert(dev.ryunosuke.island.island.IslandAlert.LowBattery(10))
            at(2.5); g.arbiter.dismissAlert()
            at(4.0); g.demo.put(compact)
            at(6.0); g.arbiter.expand(A)
            at(8.0); g.arbiter.collapse(); g.demo.remove(A)
            // 本物の活動を隠すのは録画のスクリプトが「refonly off」で戻す（ここで戻すと録画の終わりに島が動く）
            at(9.5)
            Log.i(ShellCommandReceiver.TAG, "refseq26 end")
        }
    }
}

/**
 * 本物の MediaSession を自分で立てる。MediaSessionManager 経由の取得・曲送り・シークまで
 * 通しで確かめるため（他の音楽アプリを用意しなくてよい）。音は鳴らさない。
 */
object TestMediaSession {
    private var session: MediaSession? = null
    private var track = 0
    private var playing = true
    private var position = 0L
    private var positionAt = 0L

    fun start(context: Context) {
        if (session != null) return
        session = MediaSession(context, "island-test").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = log("play").also { setPlaying(true) }
                override fun onPause() = log("pause").also { setPlaying(false) }
                override fun onSkipToNext() = log("next").also { track++; position = 0; publish() }
                override fun onSkipToPrevious() = log("previous").also { track = maxOf(0, track - 1); position = 0; publish() }
                override fun onSeekTo(pos: Long) = log("seek $pos").also { position = pos; positionAt = SystemClock.elapsedRealtime(); publish() }
            })
            isActive = true
        }
        playing = true
        position = 30_000
        positionAt = SystemClock.elapsedRealtime()
        publish()
    }

    fun stop() {
        session?.release()
        session = null
    }

    private fun setPlaying(p: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (playing) position += now - positionAt
        positionAt = now
        playing = p
        publish()
    }

    private fun publish() {
        val s = session ?: return
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "テスト曲 ${track + 1}")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Island テスト")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 180_000)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art(track))
                .build(),
        )
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO,
                )
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, position, 1f, positionAt)
                .build(),
        )
    }

    private fun art(i: Int): Bitmap {
        val colors = listOf(intArrayOf(0xFF0A84FF.toInt(), 0xFF30D158.toInt()), intArrayOf(0xFFFF375F.toInt(), 0xFFFF9F0A.toInt()))[i % 2]
        val b = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        Canvas(b).drawRect(0f, 0f, 200f, 200f, Paint().apply { shader = LinearGradient(0f, 0f, 200f, 200f, colors, null, Shader.TileMode.CLAMP) })
        return b
    }

    private fun log(what: String) {
        Log.i(ShellCommandReceiver.TAG, "media-callback $what")
    }
}

/**
 * 波形の解析（AudioSpectrum）を実機で確かめるための音。振幅数 % のごく小さな音で、
 * 薄いノイズ（音楽の地の部分）の上に、hz の音を 0.4 秒ごとに 80ms だけ鳴らす（キックやハイハットの代わり）。
 * 鳴らしている間の 5 本の棒の高さを 50ms ごとに見て、最大と平均をログに出す
 * （低い音なら左の帯、高い音なら右の帯だけが大きく跳ねるはず）。
 */
object DebugTone {
    /**
     * 実際の曲で波形を見るため、アプリ専用フォルダ（/sdcard/Android/data/<パッケージ>/files/）に
     * adb で置いた生データ（48kHz・モノラル・16bit）を鳴らす。そのあいだ試験用の MediaSession を再生中にしておく
     */
    fun playRaw(context: Context, name: String) {
        val file = java.io.File(context.getExternalFilesDir(null), name)
        if (!file.exists()) {
            Log.w(ShellCommandReceiver.TAG, "playraw: ${file.path} が無い")
            return
        }
        TestMediaSession.start(context)
        Thread {
            val rate = 48_000
            val track = android.media.AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                )
                .setAudioFormat(
                    android.media.AudioFormat.Builder().setSampleRate(rate)
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build(),
                )
                .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(rate)
                .build()
            track.play()
            Log.i(ShellCommandReceiver.TAG, "playraw start ${file.length() / 2 / rate}s")
            // 鳴らしている間の 5 本の棒の高さ（島に出ている波形と同じ値）の最大と平均。始めの 1 秒は数えない
            val levels = FloatArray(5)
            val max = FloatArray(5)
            val sum = FloatArray(5)
            var count = 0
            val start = SystemClock.elapsedRealtime()
            val sampler = Thread {
                try {
                    while (true) {
                        Thread.sleep(50)
                        if (SystemClock.elapsedRealtime() - start < 1_000) continue
                        IslandApp.graph.spectrum.levels(5, levels)
                        for (i in 0 until 5) { max[i] = maxOf(max[i], levels[i]); sum[i] += levels[i] }
                        count++
                    }
                } catch (_: InterruptedException) {
                }
            }.also { it.start() }
            file.inputStream().buffered().use { input ->
                val buf = ByteArray(9_600)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    track.write(buf, 0, n)
                }
            }
            sampler.interrupt()
            sampler.join()
            track.stop()
            track.release()
            TestMediaSession.stop()
            fun f(a: FloatArray) = a.joinToString(" ") { "%.2f".format(it) }
            Log.i(ShellCommandReceiver.TAG, "playraw end max=${f(max)} avg=${f(FloatArray(5) { sum[it] / maxOf(count, 1) })}")
        }.start()
    }

    fun play(hz: Float, seconds: Float) {
        val g = IslandApp.graph
        Thread {
            val rate = 48_000
            val n = (rate * seconds).toInt()
            val rnd = java.util.Random(1)
            val period = (rate * 0.4).toInt()
            val burst = (rate * 0.08).toInt()
            val pcm = ShortArray(n) { i ->
                val noise = (rnd.nextFloat() * 2 - 1) * 0.01
                val k = i % period
                val hit = if (k < burst) 0.06 * kotlin.math.sin(2 * Math.PI * hz * i / rate) * (1.0 - k.toDouble() / burst) else 0.0
                (Short.MAX_VALUE * (noise + hit)).toInt().toShort()
            }
            val track = android.media.AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                )
                .setAudioFormat(
                    android.media.AudioFormat.Builder().setSampleRate(rate)
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build(),
                )
                .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(n * 2)
                .build()
            track.write(pcm, 0, n)
            g.spectrum.acquire()
            track.play()
            val out = FloatArray(5)
            val max = FloatArray(5)
            val sum = FloatArray(5)
            var count = 0
            val start = SystemClock.elapsedRealtime()
            val end = start + (seconds * 1000).toLong()
            while (SystemClock.elapsedRealtime() < end) {
                Thread.sleep(50)
                // 鳴り出しの 1 秒は「普段の強さ」を覚えている途中なので数えない
                if (SystemClock.elapsedRealtime() - start < 1_000) continue
                g.spectrum.levels(5, out)
                for (i in 0 until 5) { max[i] = maxOf(max[i], out[i]); sum[i] += out[i] }
                count++
            }
            track.stop()
            track.release()
            g.spectrum.release()
            fun f(a: FloatArray) = a.joinToString(" ") { "%.2f".format(it) }
            Log.i(ShellCommandReceiver.TAG, "tone ${hz}Hz max=${f(max)}")
            Log.i(ShellCommandReceiver.TAG, "tone ${hz}Hz avg=${f(FloatArray(5) { sum[it] / maxOf(count, 1) })}")
        }.start()
    }
}
