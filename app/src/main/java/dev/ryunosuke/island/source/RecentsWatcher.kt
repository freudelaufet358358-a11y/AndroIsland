package dev.ryunosuke.island.source

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
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
 */
class RecentsWatcher(private val context: Context) {
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
        open = nowOpen
    }

    /** Recents を開いている（閉じてから少しの間も含める。払ってからアプリが止まるまで少し遅れるので） */
    fun isOpenOrJustClosed(): Boolean = open || SystemClock.elapsedRealtime() - closedAt < GRACE_MS

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
        private const val GRACE_MS = 1_500L
    }
}
