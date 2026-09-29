package dev.ryunosuke.island.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.view.MotionEvent
import android.view.RoundedCorner
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import dev.ryunosuke.island.Graph
import dev.ryunosuke.island.island.ActionTarget
import dev.ryunosuke.island.island.IslandAlert
import dev.ryunosuke.island.ui.island.HostInfo
import dev.ryunosuke.island.ui.island.IslandCallbacks
import dev.ryunosuke.island.ui.island.IslandMetrics
import dev.ryunosuke.island.ui.island.IslandRoot
import dev.ryunosuke.island.ui.island.content.LocalAudioSpectrum
import dev.ryunosuke.island.ui.island.content.LocalBatterySaverOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 画面上部に置く 1 枚の透明な窓。大きさは展開時の最大で固定し、
 * 触れる範囲だけを島の形に合わせて setTouchableRegion で絞る（窓のリサイズはカクつくので使わない）。
 */
class OverlayHost(
    private val context: Context,
    private val g: Graph,
    /** 全画面・横向き・通知シェードなどで隠すべきとき false */
    private val visible: StateFlow<Boolean>,
    private val onStatusBarsVisible: (Boolean) -> Unit,
    /** 島を下へスワイプしたとき（通知シェードを開く） */
    private val onPullDown: () -> Unit,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val lifecycle = OverlayLifecycleOwner()
    private val hostInfo = MutableStateFlow(HostInfo(density = context.resources.displayMetrics.density))

    /** 窓から見た画面（幅・カメラ穴・ステータスバーの高さ）。窓の幅が分かるまでは widthPx = 0 */
    val info: StateFlow<HostInfo> get() = hostInfo
    private var root: TouchHost? = null

    /**
     * 外側を触ったら展開を畳む。窓の外は FLAG_WATCH_OUTSIDE_TOUCH の ACTION_OUTSIDE で届く。
     * 触れる範囲は矩形なので、展開した島の角の丸みの外側は窓の中に入ってしまう。
     * そこは島の中身が反応しない（Compose が受けない）ので、それも外側として扱う。
     */
    @SuppressLint("ViewConstructor")
    private class TouchHost(context: Context, val onOutside: () -> Unit) : FrameLayout(context) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                onOutside()
                return true
            }
            val handled = super.dispatchTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && !handled) onOutside()
            return handled
        }
    }

    fun attach() {
        if (root != null) return
        val host = TouchHost(context) {
            if (g.arbiter.presentation.value.expanded) g.arbiter.collapse()
        }
        val compose = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val pres by g.arbiter.presentation.collectAsState()
                val settings by g.settings.collectAsState()
                val info by hostInfo.collectAsState()
                val shown by visible.collectAsState()
                val forceIdle by g.forceIdle.collectAsState()
                val forceAuto by g.forceAutoSize.collectAsState()
                val forceVisible by g.forceVisible.collectAsState()
                val saverOn by g.batterySaverOn.collectAsState()
                val metrics = remember(info, settings, forceAuto) {
                    IslandMetrics.from(info, if (forceAuto) settings.copy(heightDp = 0f, centerWidthDp = 0f, compactWidthDp = 0f, expandedWidthDp = 0f) else settings)
                }
                if (info.widthPx > 0) CompositionLocalProvider(
                    LocalAudioSpectrum provides if (settings.audioWaveform) g.spectrum else null,
                    LocalBatterySaverOn provides saverOn,
                ) {
                    IslandRoot(
                        presentation = pres,
                        visible = shown || forceVisible,
                        idleVisible = settings.idleVisible || forceIdle,
                        metrics = metrics,
                        callbacks = callbacks,
                        animationSpeed = settings.animationSpeed,
                    )
                }
            }
        }
        host.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        lifecycle.attachTo(host)
        host.setOnApplyWindowInsetsListener { v, insets ->
            readInsets(v.width, insets)
            insets
        }
        host.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            v.rootWindowInsets?.let { readInsets(v.width, it) }
        }
        val density = context.resources.displayMetrics.density
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (WINDOW_HEIGHT_DP * density).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            title = "Island"
        }
        lifecycle.start()
        wm.addView(host, lp)
        root = host
        // 最初は何も出していないので全部素通し
        setTouchRegion(emptyList())
    }

    fun detach() {
        val r = root ?: return
        root = null
        runCatching { wm.removeViewImmediate(r) }
        lifecycle.destroy()
    }

    private val callbacks = IslandCallbacks(
        onTap = { a ->
            a.open?.let { g.actions.run(it) }
            g.arbiter.collapse()
        },
        onLongPress = { a -> g.arbiter.expand(a.key) },
        onSwipe = { a -> g.arbiter.swipeAway(a.key) },
        onCollapse = {
            if (g.arbiter.presentation.value.alert != null) g.arbiter.dismissAlert() else g.arbiter.collapse()
        },
        onTouched = { g.arbiter.touched() },
        onAction = { x ->
            g.actions.run(x.target)
            if (x.role.collapsesIsland) g.arbiter.collapse() else g.arbiter.touched()
        },
        onTarget = { t: ActionTarget ->
            g.actions.run(t)
            g.arbiter.touched()
        },
        onAlertTap = { a ->
            if (a is IslandAlert.LowBattery) g.actions.run(ActionTarget.BatterySaverToggle)
        },
        onPullDown = onPullDown,
        onRestore = { g.arbiter.restoreHidden() },
        onTouchRegion = { setTouchRegion(it) },
    )

    private fun setTouchRegion(rects: List<Rect>) {
        lastRegion = rects
        val r = root ?: return
        val region = Region()
        for (rect in rects) region.op(rect, Region.Op.UNION)
        // 空の Region を渡すと「どこも触れない」になる（null だと窓全体になるので注意）
        r.rootSurfaceControl?.setTouchableRegion(region)
    }

    private fun readInsets(width: Int, insets: WindowInsets) {
        val cutout = insets.displayCutout?.let { c ->
            c.cutoutPath?.let { path ->
                RectF().also { path.computeBounds(it, true) }.takeIf { !it.isEmpty }
            } ?: c.boundingRectTop.takeIf { !it.isEmpty }?.let { RectF(it) }
        }
        val corner = insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
        val sb = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
            .takeIf { it > 0 } ?: statusBarHeightFromResources()
        val next = HostInfo(
            widthPx = width,
            cutout = cutout,
            cornerRadiusPx = corner,
            statusBarPx = sb,
            density = context.resources.displayMetrics.density,
            fontScale = context.resources.configuration.fontScale,
        )
        if (next != hostInfo.value) hostInfo.value = next
        onStatusBarsVisible(insets.isVisible(WindowInsets.Type.statusBars()))
    }

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun statusBarHeightFromResources(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    companion object {
        /** 展開した音楽が収まる高さ */
        const val WINDOW_HEIGHT_DP = 300

        /** 今の島の触れる範囲（窓の座標 = 画面の座標）。実機確認スクリプトがタップ位置を決めるのに使う */
        @Volatile
        var lastRegion: List<Rect> = emptyList()
            private set
    }
}
