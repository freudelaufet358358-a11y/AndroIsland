package dev.ryunosuke.island.source

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadata
import android.media.MediaRouter2
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.scale
import androidx.palette.graphics.Palette
import dev.ryunosuke.island.data.IslandSettings
import dev.ryunosuke.island.island.ActionTarget
import dev.ryunosuke.island.island.MediaActivity
import dev.ryunosuke.island.island.MediaCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 再生中のメディアセッションを追いかけて、島に出す MediaActivity を作る。
 * getActiveSessions は通知リスナーが有効なときしか使えないので、リスナーの接続で attach される。
 */
class MediaSource(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: StateFlow<IslandSettings>,
    /** 今の一時停止は Recents からアプリを払ったせいか（それならすぐ消す） */
    private val dismissedByRecents: () -> Boolean = { false },
) {
    private val main = Handler(Looper.getMainLooper())
    private val msm = context.getSystemService(MediaSessionManager::class.java)

    private inner class Tracked(val controller: MediaController) {
        /** 再生→停止になった時刻。-1 は「見ている間に一度も再生していない」 */
        var pausedSince = if (isPlaying(controller)) 0L else -1L
        val callback: MediaController.Callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) = pick()

            override fun onMetadataChanged(metadata: MediaMetadata?) = pick()
            override fun onSessionDestroyed() {
                tracked.remove(controller.sessionToken)?.let { runCatching { controller.unregisterCallback(it.callback) } }
                pick()
            }
        }
    }

    private val tracked: LinkedHashMap<MediaSession.Token, Tracked> = LinkedHashMap()
    private var currentToken: MediaSession.Token? = null
    private var expiryJob: Job? = null

    /**
     * ジャケットは getMetadata() のたびに Binder 越しの別オブジェクトになるので、同一性ではなく
     * 大きさと画素の標本で同じ絵かを見る
     */
    private data class Art(val key: Long, val scaled: Bitmap?, val accent: Int)
    private var art: Art? = null
    private var artJob: Job? = null
    private var artPending: Long? = null

    private val _state = MutableStateFlow<MediaActivity?>(null)
    val state: StateFlow<MediaActivity?> = _state

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        onSessions(list.orEmpty())
    }

    init {
        // 設定（表示のオン・オフ、一時停止後の秒数）が変わったら選び直す
        scope.launch {
            settings.distinctUntilChangedBy { it.media to it.mediaPausedTimeoutSec }.drop(1).collect { pick() }
        }
    }

    fun attach(listener: ComponentName) {
        runCatching {
            msm.addOnActiveSessionsChangedListener(sessionsListener, listener, main)
            onSessions(msm.getActiveSessions(listener))
        }.onFailure { Log.w(TAG, "セッション一覧を取れない", it) }
    }

    fun detach() {
        runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
        onSessions(emptyList())
    }

    fun controller(sessionKey: String): MediaController? =
        tracked.values.firstOrNull { keyOf(it.controller) == sessionKey }?.controller

    private fun keyOf(c: MediaController) = "media:${c.packageName}"

    private fun onSessions(list: List<MediaController>) = main.post {
        val tokens = list.map { it.sessionToken }.toSet()
        val gone = tracked.keys - tokens
        for (t in gone) tracked.remove(t)?.let { runCatching { it.controller.unregisterCallback(it.callback) } }
        // 並びはシステムの優先順（直近に操作されたものが先）に合わせる
        val next = LinkedHashMap<MediaSession.Token, Tracked>()
        for (c in list) {
            val t = tracked[c.sessionToken] ?: Tracked(c).also { c.registerCallback(it.callback, main) }
            next[c.sessionToken] = t
        }
        tracked.clear()
        tracked.putAll(next)
        pick()
    }

    /**
     * 再生→停止の時刻を記録する。コールバックの順番（メタデータが先に届くことがある）に頼らず、
     * 選び直すたびに今の状態から決める
     */
    private fun Tracked.notePaused(now: Long) {
        if (isPlaying(controller)) {
            pausedSince = 0L
        } else if (pausedSince == 0L) {
            // Recents から払って止まったなら、一度も再生していないのと同じ扱い（すぐ消す。また再生すれば出る）
            pausedSince = if (dismissedByRecents()) -1L else now
            if (pausedSince < 0) Log.i(TAG, "${controller.packageName} は Recents から終わらせたので消す")
        }
    }

    private fun pick() {
        expiryJob?.cancel()
        val now = SystemClock.elapsedRealtime()
        tracked.values.forEach { it.notePaused(now) }
        val s = settings.value
        val candidates = tracked.values.filter { titleOf(it.controller) != null }
        val chosen = candidates.firstOrNull { isPlaying(it.controller) }
            ?: candidates.firstOrNull { it.controller.sessionToken == currentToken }
            ?: candidates.firstOrNull()
        if (chosen == null || !s.media) {
            _state.value = null
            return
        }
        if (!isPlaying(chosen.controller)) {
            if (chosen.pausedSince < 0) {
                _state.value = null
                return
            }
            // 一時停止してからしばらくは出しておき、そのあと退場させる（iPhone と同じ）
            val left = chosen.pausedSince + s.mediaPausedTimeoutSec * 1000L - now
            if (left <= 0) {
                _state.value = null
                return
            }
            expiryJob = scope.launch {
                delay(left)
                pick()
            }
        }
        currentToken = chosen.controller.sessionToken
        _state.value = build(chosen.controller)
    }

    private fun build(c: MediaController): MediaActivity {
        val md = c.metadata
        val ps = c.playbackState
        val bmp = md?.let {
            it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                ?: it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART)
        }
        val artId = bmp?.let(::artKey)
        val a = art?.takeIf { it.key == artId }
        if (a == null) processArt(bmp, artId)
        val actions = ps?.actions ?: 0L
        val declared = actions != 0L
        fun can(flag: Long) = !declared || (actions and flag) != 0L
        val canNext = can(PlaybackState.ACTION_SKIP_TO_NEXT)
        val timeSkip = declared && (actions and (PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND)) != 0L
        val key = keyOf(c)
        return MediaActivity(
            key = key,
            packageName = c.packageName,
            title = titleOf(c).orEmpty(),
            artist = md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                ?: md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: "",
            art = a?.scaled,
            accent = a?.accent ?: Color.WHITE,
            playing = isPlaying(c),
            durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0) ?: 0,
            positionMs = ps?.position?.coerceAtLeast(0) ?: 0,
            positionAtElapsed = ps?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            speed = ps?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            canPrevious = can(PlaybackState.ACTION_SKIP_TO_PREVIOUS),
            canNext = canNext,
            canSeek = declared && (actions and PlaybackState.ACTION_SEEK_TO) != 0L,
            skipByTime = timeSkip && (actions and PlaybackState.ACTION_SKIP_TO_NEXT) == 0L,
            open = ActionTarget.MediaOpen(key),
        )
    }

    /** ジャケットを小さくし、波形に使う色を取る。重いので裏で */
    private fun processArt(src: Bitmap?, key: Long?) {
        if (src != null && key == artPending && artJob?.isActive == true) return
        artJob?.cancel()
        artPending = key
        if (src == null || key == null) {
            art = null
            return
        }
        artJob = scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val max = 320
                    val scaled = if (src.width > max || src.height > max) {
                        val r = max.toFloat() / maxOf(src.width, src.height)
                        src.scale((src.width * r).toInt().coerceAtLeast(1), (src.height * r).toInt().coerceAtLeast(1))
                    } else src
                    Art(key, scaled, accentOf(scaled))
                }.getOrNull()
            }
            art = result ?: Art(key, null, Color.WHITE)
            pick()
        }
    }

    fun command(sessionKey: String, cmd: MediaCommand) {
        val c = controller(sessionKey) ?: return
        val t = c.transportControls
        val pos = state.value?.positionAt(SystemClock.elapsedRealtime()) ?: 0
        when (cmd) {
            MediaCommand.PlayPause -> if (isPlaying(c)) t.pause() else t.play()
            MediaCommand.Next -> t.skipToNext()
            MediaCommand.Previous -> t.skipToPrevious()
            MediaCommand.Forward ->
                if (state.value?.canSeek == true) t.seekTo(pos + 15_000) else t.fastForward()
            MediaCommand.Rewind ->
                if (state.value?.canSeek == true) t.seekTo((pos - 15_000).coerceAtLeast(0)) else t.rewind()
            MediaCommand.Output -> showOutputSwitcher(c.packageName)
        }
    }

    fun seek(sessionKey: String, positionMs: Long) {
        controller(sessionKey)?.transportControls?.seekTo(positionMs)
    }

    /**
     * 出力先の切り替え（iPhone の AirPlay ボタン）。システムの「メディア出力」ダイアログ（通知シェードの
     * メディアの出力ボタンと同じもの）を、SystemUI への broadcast で、再生中のアプリを指定して開く。
     * MediaRouter2.showSystemOutputSwitcher() は自分のアプリの出力しか扱えないので使えない。
     */
    private fun showOutputSwitcher(packageName: String) {
        val dialog = Intent(ACTION_MEDIA_OUTPUT_DIALOG)
            .setPackage(SYSTEMUI)
            .putExtra(EXTRA_PACKAGE_NAME, packageName)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        val receivers = context.packageManager.queryBroadcastReceivers(dialog, 0)
        if (receivers.isNotEmpty()) {
            context.sendBroadcast(dialog)
            return
        }
        // SystemUI に受け口が無い端末: 設定のパネル（古い版）を試す
        val panel = Intent(ACTION_MEDIA_OUTPUT_PANEL)
            .putExtra(EXTRA_PANEL_PACKAGE_NAME, packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(panel) }.onFailure {
            Log.w(TAG, "出力先のダイアログを開けない", it)
            runCatching { MediaRouter2.getInstance(context).showSystemOutputSwitcher() }
        }
    }

    companion object {
        private const val TAG = "IslandMedia"

        private const val SYSTEMUI = "com.android.systemui"
        private const val ACTION_MEDIA_OUTPUT_DIALOG = "com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG"
        private const val EXTRA_PACKAGE_NAME = "package_name"
        private const val ACTION_MEDIA_OUTPUT_PANEL = "com.android.settings.panel.action.MEDIA_OUTPUT"
        private const val EXTRA_PANEL_PACKAGE_NAME = "com.android.settings.panel.extra.PACKAGE_NAME"

        fun isPlaying(c: MediaController) = c.playbackState?.isActive == true

        /** 大きさと 4×4 の画素の標本から作る、絵の同一性の目安 */
        fun artKey(b: Bitmap): Long {
            var h = b.width * 31L + b.height
            // HARDWARE の Bitmap は画素を読めない
            if (b.isRecycled || b.config == Bitmap.Config.HARDWARE) return h
            for (y in 1..4) for (x in 1..4) {
                h = h * 1_000_003L + b.getPixel((b.width * x / 5).coerceIn(0, b.width - 1), (b.height * y / 5).coerceIn(0, b.height - 1))
            }
            return h
        }

        private fun titleOf(c: MediaController): String? = c.metadata?.let {
            it.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: it.getString(MediaMetadata.METADATA_KEY_TITLE)
        }?.takeIf { it.isNotBlank() }

        /** 黒地で映える色。暗すぎる色は明るくする */
        fun accentOf(bmp: Bitmap): Int {
            val p = Palette.from(bmp).maximumColorCount(16).generate()
            val base = p.vibrantSwatch?.rgb ?: p.lightVibrantSwatch?.rgb ?: p.dominantSwatch?.rgb ?: Color.WHITE
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(base, hsl)
            hsl[2] = hsl[2].coerceIn(0.58f, 0.8f)
            return ColorUtils.HSLToColor(hsl)
        }
    }
}
