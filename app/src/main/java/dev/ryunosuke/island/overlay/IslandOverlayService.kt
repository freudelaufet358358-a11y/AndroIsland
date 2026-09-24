package dev.ryunosuke.island.overlay

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import dev.ryunosuke.island.IslandApp
import dev.ryunosuke.island.island.IslandAlert
import dev.ryunosuke.island.source.BatterySaver
import dev.ryunosuke.island.source.SystemEventSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 島を描くアクセシビリティサービス。TYPE_ACCESSIBILITY_OVERLAY の窓は
 * ステータスバー・通知シェード・ロック画面より上に出せる（一般アプリのオーバーレイでは届かない）。
 * 画面の中身（ノード）は読まず、窓の一覧だけを全画面・通知シェードの判定に使う。
 */
class IslandOverlayService : AccessibilityService() {

    private val g get() = IslandApp.graph
    private var scope: CoroutineScope? = null
    private var host: OverlayHost? = null
    private var events: SystemEventSource? = null
    private val main = Handler(Looper.getMainLooper())

    private data class Chrome(
        /** 窓の一覧から見たステータスバーの有無。一度も見つけていなければ null（判定に使えない） */
        val statusBarWindow: Boolean? = null,
        /** 窓の insets から見たステータスバーの有無。一度も「見えている」を受け取っていなければ null */
        val statusBarInsets: Boolean? = null,
        val shadeOpen: Boolean = false,
        val landscape: Boolean = false,
    )

    private val chrome = MutableStateFlow(Chrome())
    private val visible = MutableStateFlow(true)
    private var sawStatusBarWindow = false
    private var sawStatusBarInsets = false

    private val checkWindows = Runnable { inspectWindows() }

    override fun onServiceConnected() {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        chrome.value = chrome.value.copy(landscape = isLandscape(resources.configuration))
        events = SystemEventSource(this, g.settings, onBatterySaverChanged = ::onBatterySaverChanged) { g.arbiter.postAlert(it) }
            .also { it.start() }
        onBatterySaverChanged(BatterySaver.isOn(this))
        host = OverlayHost(
            this, g, visible,
            onStatusBarsVisible = { shown ->
                // 島の窓はステータスバーより上にあるので、insets が届かない端末もある。
                // 一度でも「見えている」を受け取れたときだけ判定に使う
                if (shown) sawStatusBarInsets = true
                val v = if (sawStatusBarInsets) shown else null
                if (v != chrome.value.statusBarInsets) chrome.value = chrome.value.copy(statusBarInsets = v)
            },
            // 島がステータスバーの上に被さっているので、島の所から引き下ろしたときは代わりに開く
            onPullDown = {
                val ok = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                Log.d(TAG, "島を下へスワイプ → 通知シェード ok=$ok")
            },
        ).also { it.attach() }
        s.launch {
            combine(g.settings, chrome) { st, c ->
                // 窓の一覧で判定できるならそれを、できなければ insets を使う。どちらも使えなければ隠さない
                val statusBarShown = c.statusBarWindow ?: c.statusBarInsets ?: true
                val fullscreen = !statusBarShown
                !(st.hideFullscreen && fullscreen) &&
                    !(st.hideLandscape && c.landscape) &&
                    !(st.hideShade && c.shadeOpen)
            }.collect { visible.value = it }
        }
        running.value = true
        inspectWindows()
    }

    private fun onBatterySaverChanged(on: Boolean) {
        g.batterySaverOn.value = on
        // 島から入れた省電力が設定画面・充電などで切れたら、元のスケジュールに戻す
        BatterySaver.onModeChanged(this)
        // 電池残量低下の表示から切り替えたら、変わった色を見せるために少し長く出しておく
        val a = g.arbiter.currentAlert
        if (a is IslandAlert.LowBattery) g.arbiter.postAlert(a, durationMs = 3_000L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            g.recents.onWindowStateChanged(event.packageName, event.text, event.contentChangeTypes)
        }
        // 窓の出入りはまとめて届くので少し待ってから一度だけ調べる
        main.removeCallbacks(checkWindows)
        main.postDelayed(checkWindows, 60)
    }

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        chrome.value = chrome.value.copy(landscape = isLandscape(newConfig))
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        running.value = false
        main.removeCallbacks(checkWindows)
        host?.detach()
        host = null
        events?.stop()
        events = null
        scope?.cancel()
        scope = null
    }

    private fun isLandscape(c: Configuration) = c.orientation == Configuration.ORIENTATION_LANDSCAPE

    /**
     * ステータスバーの窓（上端・横いっぱい・低い）と、通知シェード（上端から画面の半分以上）を探す。
     * ロック画面ではキーガードが通知シェードの窓に描かれるので、シェード扱いしない。
     */
    private fun inspectWindows() {
        val list = runCatching { windows }.getOrNull() ?: return
        val dm = resources.displayMetrics
        // パンチホールのある端末はステータスバーが高い（Pixel 6a は 50dp）
        val sbMax = (80 * dm.density).toInt()
        var statusBar = false
        var shade = false
        val r = Rect()
        for (w in list) {
            if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
            w.getBoundsInScreen(r)
            if (r.top <= 0 && r.height() in 1..sbMax && r.width() >= dm.widthPixels * 0.8f) statusBar = true
            if (r.top <= 0 && r.height() >= dm.heightPixels / 2) shade = true
        }
        if (statusBar) sawStatusBarWindow = true
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val next = chrome.value.copy(
            statusBarWindow = if (sawStatusBarWindow) statusBar else null,
            shadeOpen = shade && !locked,
        )
        debugInfo = "chrome=$next visible=${visible.value} windows=${describe(list)}"
        if (next != chrome.value) {
            Log.d(TAG, debugInfo)
            chrome.value = next
        }
    }

    private fun describe(list: List<AccessibilityWindowInfo>): String {
        val r = Rect()
        return list.joinToString { w ->
            w.getBoundsInScreen(r)
            "[t=${w.type} ${w.title} ${r.toShortString()}]"
        }
    }

    companion object {
        private const val TAG = "IslandOverlay"
        private val running = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> get() = running

        /** 実機確認スクリプト用: 直近の窓の判定 */
        @Volatile
        var debugInfo: String = ""
            private set
    }
}
