package dev.ryunosuke.island.source

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 「最近使ったアプリ」（Recents）が開いているかを、ランチャーの画面切り替えのイベントから見る。
 *
 * 音楽アプリを Recents から払って終わらせても、Apple Music などは一時停止するだけで
 * プロセスもメディアセッションも残る（システムから見ると、アプリの中で一時停止したのと区別がつかない）。
 * iPhone ではアプリを終わらせると再生中の表示も消えるので、Recents を開いている間に止まった再生は
 * 「アプリを終わらせた」とみなして、一時停止後の猶予を待たずに島から消す。
 *
 * 判定には、ランチャーが Recents を開いたときに流す TYPE_WINDOW_STATE_CHANGED の文言
 * （ランチャー自身の accessibility_recent_apps という文字列。日本語なら「最近使ったアプリ」）だけを使う。
 *
 * Shizuku が使えるときは、どのアプリが払われたかまで見る。Recents を開いたときの最近のタスクの一覧と、
 * 開いている間（と閉じた直後）に取り直した一覧を比べ、消えたタスクのアプリを払われたとみなす。
 * 一時停止してから払った音楽は、再生の状態が何も変わらないのでこれでしか分からない。
 */
class RecentsWatcher(
    private val context: Context,
    /** Recents から払われたアプリ（Shizuku が使えるときだけ分かる）。メインスレッドで呼ぶ */
    private val onAppsRemoved: (Set<String>) -> Unit = {},
) {
    private var launcher: String? = null
    private var label: String? = null
    private var resolved = false

    @Volatile
    private var open = false

    @Volatile
    private var closedAt = 0L

    fun onWindowStateChanged(packageName: CharSequence?, text: List<CharSequence>, contentChangeTypes: Int) {
        resolve()
        val pkg = packageName?.toString()
        // Recents を閉じるときも同じ文言で「その区画が消えた」（PANE_DISAPPEARED）が届く
        val disappeared = contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED != 0
        val nowOpen = !disappeared && pkg != null && pkg == launcher && label != null && text.any { it.toString() == label }
        if (open && !nowOpen) closedAt = SystemClock.elapsedRealtime()
        if (!open && nowOpen) watchTasks()
        open = nowOpen
    }

    /** Recents を開いている（閉じてから少しの間も含める。払ってからアプリが止まるまで少し遅れるので） */
    fun isOpenOrJustClosed(): Boolean = open || SystemClock.elapsedRealtime() - closedAt < GRACE_MS

    private val main = Handler(Looper.getMainLooper())

    /** 前に見た最近のタスクのアプリ。Shizuku のスレッドだけで触る */
    private var tasksBefore: Set<String>? = null

    /** Recents を開いたら、閉じた少しあとまで最近のタスクを見比べ続ける */
    private fun watchTasks() {
        if (!ShizukuShell.isReady()) return
        val h = ShizukuShell.handler
        h.post {
            // 閉じてすぐ開き直したときの見比べが残っていれば止める（同じスレッドで止めないと、走っている最中のものが次を予約する）
            h.removeCallbacks(pollTasks)
            tasksBefore = readTasks()
            if (tasksBefore != null) h.postDelayed(pollTasks, POLL_MS)
        }
    }

    private val pollTasks = object : Runnable {
        override fun run() {
            val before = tasksBefore ?: return
            val now = readTasks()
            if (now != null) {
                val removed = before - now
                tasksBefore = now
                if (removed.isNotEmpty()) {
                    Log.i(TAG, "Recents から払われた: $removed")
                    main.post { onAppsRemoved(removed) }
                }
            }
            if (now != null && isOpenOrJustClosed()) ShizukuShell.handler.postDelayed(this, POLL_MS) else tasksBefore = null
        }
    }

    private fun readTasks(): Set<String>? =
        runCatching { ShizukuShell.recentTaskPackages() }.onFailure { Log.w(TAG, "最近のタスクを読めない", it) }.getOrNull()

    // ランチャー（別のアプリ）の文字列を名前で引くので、リソースの反射は避けられない
    @SuppressLint("DiscouragedApi")
    private fun resolve() {
        if (resolved) return
        resolved = true
        val pm = context.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val pkg = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName ?: return
        launcher = pkg
        label = runCatching {
            val res = pm.getResourcesForApplication(pkg)
            val id = res.getIdentifier("accessibility_recent_apps", "string", pkg)
            if (id != 0) res.getString(id) else null
        }.getOrNull()
    }

    companion object {
        private const val TAG = "IslandRecents"
        private const val GRACE_MS = 1_500L

        /** Recents を開いている間に最近のタスクを取り直す間隔 */
        private const val POLL_MS = 400L
    }
}
