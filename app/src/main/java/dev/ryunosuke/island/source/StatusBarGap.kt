package dev.ryunosuke.island.source

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.PowerManager
import android.util.Log
import android.view.Display
import android.view.Surface
import androidx.core.content.edit
import dev.ryunosuke.island.data.IslandSettings
import dev.ryunosuke.island.ui.island.HostInfo
import dev.ryunosuke.island.ui.island.IslandMetrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * ステータスバーの時計とアイコンを島の外へ押し出す（root で起動した Shizuku が要る）。
 *
 * ステータスバーは、カメラ穴の外接矩形（システムの設定値 `config_mainBuiltInDisplayCutoutRectApproximation`。
 * Pixel 6a は幅 145px）の幅だけ真ん中を空け、左に時計と通知アイコン、右に電池などを並べる。
 * この値を島が入る幅の矩形に差し替える fabricated overlay（その場で作るリソースの上書き）を
 * `cmd overlay fabricate` で作って有効にする。fabricate は root でしか呼べない（adb の shell には許されていない）。
 *
 * - カメラ穴の形（`config_mainBuiltInDisplayCutout`）は変えないので、穴の縁取りと島の位置（穴の形から決める）は変わらない
 * - 高さは今の矩形のまま（ステータスバーの高さ・アプリの上端の余白は変わらない）。横向きでは穴が左右の端に来るので、
 *   アプリが避ける幅は変わらず、避ける範囲が縦に長くなるだけ
 * - overlay は端末の設定として残る（再起動しても、Shizuku が止まっても効いたまま）。戻すにはスイッチを切る。
 *   オンのままアプリを消したときのために、入れ直して最初に Shizuku が使えたとき、一度だけ切っておく
 * - システムの overlay を切り替えると、開いているアプリの画面がすべて作り直される（壁紙の色を変えたときと同じ）。
 *   なので島の今の幅には合わせられず、ふだん出ているコンパクト（音楽など）の大きさをいつも空けておく
 * - ただし、両脇には時計と通知アイコン 2 つ・5G とアンテナと電池が入る幅を残す（[IslandMetrics.maxStatusBarGapDp]）。
 *   空けすぎると、システムは入りきらないアイコンを「•」にまとめてしまう
 *
 * 状態は端末の本当の値（[Display.getCutout] の上端の矩形）と比べて決める。違っていたら作り直し、
 * 10 秒たっても変わらなければ overlay を切って諦める（同じ中身では、スイッチを入れ直すまで試さない）。
 *
 * ロック画面のステータスバー（SystemUI の KeyguardStatusBarView）は、カメラ穴に合わせた並びを最初の 1 回しか作らず、
 * あとで穴の幅が変わっても古い幅のまま残る（Android 16。ホーム画面のステータスバーは付いてくる）。
 * なので空きを変えたら、そのときの SystemUI を覚えておき、次に画面が消えたときに root で再起動して作り直させる
 * （画面が点いているうちに再起動すると、画面上部がちらついてロックもかかるので）。
 */
class StatusBarGap(private val app: Context, private val scope: CoroutineScope, settings: StateFlow<IslandSettings>) {

    sealed interface State {
        /** スイッチが切れていて、何も上書きしていない */
        data object Off : State

        /** 島の窓が無い（アクセシビリティの Island が止まっている）ので、空ける幅を決められない */
        data object NoIsland : State

        /** Shizuku が動いていない・Island に使う許可が無い */
        data object NoShizuku : State

        /** Shizuku が adb（ワイヤレスデバッグ）の権限で動いていて、overlay を作れない */
        data object NeedsRoot : State
        data object Applying : State
        data class Applied(val widthDp: Int) : State
        data class Failed(val reason: String) : State

        /** スイッチは切ったが、Shizuku が使えないのでまだ元に戻せていない */
        data object CannotRestore : State
    }

    /** 島の窓から見た画面。窓が無いときは null（IslandOverlayService が入れる） */
    val host = MutableStateFlow<HostInfo?>(null)

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> get() = _state

    private val prefs = app.getSharedPreferences("status_bar_gap", Context.MODE_PRIVATE)
    private val shizuku by lazy { ShizukuShell.handler.asCoroutineDispatcher() }

    /** 最後に作った overlay の中身（同じものを何度も作らない） */
    private var sentSpec: String? = null

    private val _lockScreenPending = MutableStateFlow(prefs.getInt(KEY_STALE_SYSTEMUI_PID, 0) > 0)

    /** ロック画面のステータスバーがまだ古い幅のまま（次に画面が消えたとき、SystemUI を再起動して作り直す） */
    val lockScreenPending: StateFlow<Boolean> get() = _lockScreenPending

    init {
        // 画面が消えたら、少し待ってまだ消えていれば作り直す（すぐ点け直したときは次の機会に）
        app.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    scope.launch {
                        delay(SCREEN_OFF_DELAY_MS)
                        if (!app.getSystemService(PowerManager::class.java).isInteractive) {
                            runCatching { refreshLockScreen() }.onFailure { Log.w(TAG, "SystemUI を作り直せない", it) }
                        }
                    }
                }
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            Context.RECEIVER_EXPORTED,
        )
        scope.launch {
            combine(settings, host, ShizukuShell.changes) { s, h, _ -> s to h }
                // スライダーを動かしている間は待つ（作り直すたびに、開いているアプリの画面が作り直される）
                .collectLatest { (s, h) ->
                    delay(SETTLE_MS)
                    try {
                        update(s, h)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "ステータスバーの空きを見直せない", e)
                        _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
                    }
                }
        }
    }

    private val display: Display?
        get() = app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)

    private suspend fun update(s: IslandSettings, h: HostInfo?) {
        if (!s.shiftStatusBar) return restore()
        if (h == null || h.widthPx <= 0) {
            _state.value = State.NoIsland
            return
        }
        val d = display ?: return
        // 横向きでは穴が端に来て、島の窓の幅も変わる。縦に戻ると host が変わるので、そのとき見直す
        if (d.rotation != Surface.ROTATION_0) return
        @Suppress("DEPRECATION")
        val size = Point().also { d.getRealSize(it) }
        if (size.x != h.widthPx) return
        val now = d.cutout?.boundingRectTop?.takeIf { !it.isEmpty }
        if (now == null) {
            _state.value = State.Failed("カメラの穴の位置が分からない")
            return
        }

        val center = h.widthPx / 2f
        val holeHalf = h.cutout?.let { maxOf(abs(it.left - center), abs(it.right - center)) } ?: 0f
        // 設定値は、いちばん解像度の高いモードの画素で書く（Pixel 6a は 1080px のまま）
        val physicalWidth = d.supportedModes.maxByOrNull { it.physicalWidth.toLong() * it.physicalHeight }
            ?.physicalWidth ?: h.widthPx
        val want = Gap.of(IslandMetrics.from(h, s), holeHalf, s.statusBarGapDp, now.bottom, physicalWidth)
        if (want.matches(now.width(), now.bottom)) {
            sentSpec = want.spec
            prefs.edit { putBoolean(KEY_APPLIED, true) }
            _state.value = State.Applied(want.widthDp)
            // 前の版で空けたときは、ロック画面のステータスバーが古い幅のまま残っていることがあるので、一度だけ作り直す
            if (!prefs.getBoolean(KEY_LOCKSCREEN_CHECKED, false) && ShizukuShell.isReady()) markLockScreenStale()
            return
        }

        if (!ShizukuShell.isReady()) {
            _state.value = State.NoShizuku
            return
        }
        if (!ShizukuShell.isRoot()) {
            _state.value = State.NeedsRoot
            return
        }
        if (prefs.getString(KEY_FAILED_SPEC, null) == want.spec) {
            _state.value = State.Failed(prefs.getString(KEY_FAILED_REASON, null).orEmpty())
            return
        }
        // この値はシステムの中（system_server）で読まれ、読めない値だと端末ごと起動できなくなる。決まった形のものしか送らない
        if (!want.isWellFormed()) {
            _state.value = State.Failed("空ける矩形を決められない（${want.spec}）")
            return
        }

        _state.value = State.Applying
        if (sentSpec != want.spec) {
            Log.i(TAG, "ステータスバーの真ん中を ${want.widthDp}dp 空ける: ${want.spec}（今は ${now.toShortString()}）")
            // 送っている間に設定が変わって待ち直しても、同じものは送り直さない（送るたびにアプリの画面が作り直される）
            sentSpec = want.spec
            // 作りかけで落ちても、あとで切れるように先に（同期で）覚えておく
            prefs.edit(commit = true) { putBoolean(KEY_APPLIED, true) }
            // 今の SystemUI のロック画面は、これから変える前の幅で並んだまま残る
            markLockScreenStale()
            val error = withContext(shizuku) {
                runCatching {
                    // 同じ名前で作り直すと中身だけ入れ替わる（有効なままなら、そのまま効く）。0x03 は TypedValue.TYPE_STRING
                    ShizukuShell.exec("cmd", "overlay", "fabricate", "--target", "android", "--name", NAME, RESOURCE, "0x03", want.spec)
                    ShizukuShell.exec("cmd", "overlay", "enable", "--user", "0", OVERLAY)
                }.exceptionOrNull()
            }
            if (error != null) return fail(want.spec, "overlay を作れない: ${error.message}")
        }

        // システムがカメラ穴を読み直すまで待つ（アプリの画面の作り直しが混んでいると数秒かかる）
        val done = withTimeoutOrNull(VERIFY_MS) {
            while (display?.cutout?.boundingRectTop?.let { want.matches(it.width(), it.bottom) } != true) delay(POLL_MS)
        } != null
        if (done) {
            _state.value = State.Applied(want.widthDp)
        } else {
            val r = display?.cutout?.boundingRectTop
            fail(want.spec, "カメラ穴の矩形が変わらない（${r?.width()}×${r?.bottom}px のまま。${want.widthPx.roundToInt()}px にしたかった）")
        }
    }

    /** 効かなかった中身を覚え（スイッチを入れ直すまで試さない）、半端な overlay を残さないように切る */
    private suspend fun fail(spec: String, reason: String) {
        Log.w(TAG, "ステータスバーを空けられない: $reason")
        prefs.edit {
            putString(KEY_FAILED_SPEC, spec)
            putString(KEY_FAILED_REASON, reason)
        }
        sentSpec = null
        if (disable() != null) prefs.edit { putBoolean(KEY_APPLIED, false) }
        _state.value = State.Failed(reason)
    }

    private suspend fun restore() {
        sentSpec = null
        prefs.edit {
            remove(KEY_FAILED_SPEC)
            remove(KEY_FAILED_REASON)
        }
        val applied = prefs.getBoolean(KEY_APPLIED, false)
        // 作っていない。入れ直したあとの片付け（前に入れていた Island が残したものを切る）も済んでいる
        if (!applied && prefs.getBoolean(KEY_CHECKED, false)) {
            _state.value = State.Off
            return
        }
        val existed = disable()
        if (existed == null) {
            _state.value = if (applied) State.CannotRestore else State.Off
            return
        }
        // 切った overlay があった（前に入れていた Island が残したもの・切ったままのものも含む）なら、ロック画面も戻す
        if (existed) markLockScreenStale()
        prefs.edit {
            putBoolean(KEY_APPLIED, false)
            putBoolean(KEY_CHECKED, true)
        }
        _state.value = State.Off
    }

    /**
     * overlay を切る（切るのは root でなくてもできる）。true は overlay があった、false は無かった
     * （一度も作っていなければシステムに断られる）。Shizuku が使えず確かめられなかったときは null
     */
    private suspend fun disable(): Boolean? = withContext(shizuku) {
        if (!ShizukuShell.isReady()) return@withContext null
        val existed = runCatching { ShizukuShell.exec("cmd", "overlay", "disable", "--user", "0", OVERLAY) }
            .onFailure { Log.i(TAG, "overlay を切れない（作っていなければ問題ない）: ${it.message}") }
            .isSuccess
        if (ShizukuShell.isReady()) existed else null
    }

    /** 今動いている SystemUI を「ロック画面のステータスバーが古い」と覚える（次に画面が消えたとき再起動する） */
    private suspend fun markLockScreenStale() {
        val pid = withContext(shizuku) { runCatching { systemUiPid() }.getOrNull() } ?: return
        prefs.edit {
            putInt(KEY_STALE_SYSTEMUI_PID, pid)
            putBoolean(KEY_LOCKSCREEN_CHECKED, true)
        }
        _lockScreenPending.value = true
    }

    /**
     * 覚えた SystemUI がまだ動いていれば再起動する（SystemUI は落ちるとすぐに立ち上がり直し、ロック画面も今のカメラ穴で並べ直す）。
     * 端末の再起動などですでに入れ替わっていれば何もしない。root の Shizuku が無い・通話中なら、次に画面が消えたときに回す
     */
    private suspend fun refreshLockScreen() {
        val stale = prefs.getInt(KEY_STALE_SYSTEMUI_PID, 0)
        if (stale <= 0 || !ShizukuShell.isRoot()) return
        if (app.getSystemService(AudioManager::class.java).mode != AudioManager.MODE_NORMAL) return
        val done = withContext(shizuku) {
            val pid = runCatching { systemUiPid() }.getOrNull() ?: return@withContext false
            if (pid != stale) return@withContext true
            Log.i(TAG, "ロック画面のステータスバーを作り直すため SystemUI（$pid）を再起動する")
            runCatching { ShizukuShell.exec("kill", pid.toString()) }
            // 普通は SIGTERM で落ちる。同じプロセスが残っていたら強制的に止める
            delay(KILL_CHECK_MS)
            if (runCatching { systemUiPid() }.getOrNull() == pid) {
                runCatching { ShizukuShell.exec("kill", "-9", pid.toString()) }
                delay(KILL_CHECK_MS)
            }
            runCatching { systemUiPid() }.getOrNull() != pid
        }
        if (done) {
            prefs.edit { remove(KEY_STALE_SYSTEMUI_PID) }
            _lockScreenPending.value = false
        }
    }

    /** SystemUI のプロセス番号。Shizuku のスレッドで呼ぶ */
    private fun systemUiPid(): Int? = parsePid(ShizukuShell.exec("pidof", SYSTEMUI))

    /**
     * 空ける矩形。[halfPx]・[bottomPx] は設定値の画素（いちばん解像度の高いモード）で、[ratio] は画面の画素 1 つあたりのその画素数。
     * 矩形は画面の上端中央を原点に左右対称に置く（ステータスバーは空きを画面の中央に置くので、位置は幅ほど効かない）
     */
    data class Gap(val halfPx: Int, val bottomPx: Int, val ratio: Float, val density: Float) {
        /** 設定値の書き方（SVG のパス。原点は画面の上端中央） */
        val spec get() = "M -$halfPx,0 H $halfPx V $bottomPx H -$halfPx Z"

        /** 画面の画素での幅と下端 */
        val widthPx get() = 2 * halfPx / ratio
        val bottom get() = bottomPx / ratio
        val widthDp get() = (widthPx / density).roundToInt()

        /** 端末が今報告している上端の矩形の幅と下端が、これと同じか（丸めの分だけずれてよい） */
        fun matches(width: Int, bottom: Int) = abs(width - widthPx) <= TOLERANCE_PX && abs(bottom - this.bottom) <= TOLERANCE_PX

        /** 中身のある矩形で、[spec] が「M -整数,0 H 整数 V 整数 H -整数 Z」の形になっている */
        fun isWellFormed() = halfPx > 0 && bottomPx > 0 && WELL_FORMED.matches(spec)

        companion object {
            private val WELL_FORMED = Regex("""M -\d+,0 H \d+ V \d+ H -\d+ Z""")

            /** [bottom] は今の矩形の下端（高さは変えない）、[physicalWidth] は設定値の画素での画面の幅 */
            fun of(m: IslandMetrics, holeHalf: Float, gapDp: Float, bottom: Int, physicalWidth: Int): Gap {
                val ratio = if (m.width > 0f) physicalWidth / m.width else 1f
                val half = m.statusBarGapHalf(gapDp, holeHalf)
                return Gap((half * ratio).roundToInt(), (bottom * ratio).roundToInt(), ratio, m.density)
            }
        }
    }

    companion object {
        private const val TAG = "IslandStatusBar"
        private const val NAME = "IslandStatusBar"

        /** `cmd overlay fabricate` で作ると、持ち主は shell になる */
        const val OVERLAY = "com.android.shell:$NAME"
        private const val RESOURCE = "android:string/config_mainBuiltInDisplayCutoutRectApproximation"

        private const val SYSTEMUI = "com.android.systemui"

        private const val SETTLE_MS = 1_500L
        private const val VERIFY_MS = 10_000L
        private const val POLL_MS = 250L
        private const val TOLERANCE_PX = 3

        /** 画面が消えてから SystemUI を再起動するまで（消えるときの処理を邪魔しない・すぐ点け直したら見送る） */
        private const val SCREEN_OFF_DELAY_MS = 1_500L
        private const val KILL_CHECK_MS = 1_000L

        private const val KEY_APPLIED = "applied"
        private const val KEY_CHECKED = "checked"
        private const val KEY_FAILED_SPEC = "failed_spec"
        private const val KEY_FAILED_REASON = "failed_reason"
        private const val KEY_STALE_SYSTEMUI_PID = "stale_systemui_pid"
        private const val KEY_LOCKSCREEN_CHECKED = "lockscreen_checked"

        /** `pidof` の出力（空白区切りのプロセス番号）の最初の 1 つ */
        fun parsePid(out: String): Int? = out.trim().split(Regex("\\s+")).firstOrNull()?.toIntOrNull()?.takeIf { it > 0 }
    }
}
