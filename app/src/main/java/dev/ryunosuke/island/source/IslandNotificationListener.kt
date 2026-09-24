package dev.ryunosuke.island.source

import android.app.Notification
import android.app.Person
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.TextView
import dev.ryunosuke.island.IslandApp

class IslandNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        val g = IslandApp.graph
        val all = runCatching { activeNotifications?.toList().orEmpty() }.getOrDefault(emptyList())
        g.notifications.reset(all.map { it to snapshot(this, it) })
        g.media.attach(ComponentName(this, IslandNotificationListener::class.java))
        connected = true
    }

    override fun onListenerDisconnected() {
        connected = false
        IslandApp.graph.media.detach()
        IslandApp.graph.notifications.reset(emptyList())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        // 島から省電力を入れた直後にシステムが出す「バッテリー セーバーが ON になっています」は、
        // 島の表示が黄色に変わるので要らない（iPhone でも出ない）。島から入れたときだけ消す
        if (sbn.packageName == "android" && sbn.tag == BATTERY_SAVER_NOTICE_TAG && BatterySaver.justTurnedOnByIsland()) {
            cancelNotification(sbn.key)
            return
        }
        val snap = snapshot(this, sbn)
        // 判定の合わせ込み用。既定では出さない（adb shell setprop log.tag.IslandListener DEBUG で有効）
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "posted $snap")
        IslandApp.graph.notifications.posted(sbn, snap)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?, reason: Int) {
        IslandApp.graph.notifications.removed(sbn.key)
    }

    companion object {
        private const val TAG = "IslandListener"
        private const val BATTERY_SAVER_NOTICE_TAG = "BatterySaverStateMachine"

        @Volatile
        var connected = false
            private set

        // Android 16 の Live Updates。API 36 未満では存在しない値なので文字列と数値で持つ
        private const val FLAG_PROMOTED_ONGOING = 0x00040000
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
        private const val EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText"

        @Suppress("DEPRECATION")
        fun snapshot(context: Context, sbn: StatusBarNotification): NotificationSnapshot {
            val n = sbn.notification
            val e = n.extras
            val template = e.getString(Notification.EXTRA_TEMPLATE)
            val person = e.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)
            val promoted = (n.flags and FLAG_PROMOTED_ONGOING) != 0 ||
                e.getBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, false)
            val isMedia = template?.contains("MediaStyle") == true ||
                e.containsKey(Notification.EXTRA_MEDIA_SESSION)
            val wantsCustom = n.contentView != null && (
                sbn.packageName in NotificationParser.clockPackages ||
                    n.category == Notification.CATEGORY_STOPWATCH ||
                    n.category == Notification.CATEGORY_ALARM
                )
            return NotificationSnapshot(
                key = sbn.key,
                packageName = sbn.packageName,
                postTime = sbn.postTime,
                whenTime = n.`when`,
                category = n.category,
                channelId = n.channelId,
                template = template,
                ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0,
                promoted = promoted,
                groupSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
                title = (e.getCharSequence(Notification.EXTRA_TITLE_BIG) ?: e.getCharSequence(Notification.EXTRA_TITLE))?.toString(),
                text = (e.getCharSequence(Notification.EXTRA_TEXT) ?: e.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString(),
                subText = e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
                shortCriticalText = e.getCharSequence(EXTRA_SHORT_CRITICAL_TEXT)?.toString(),
                showChronometer = e.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false),
                chronometerCountDown = e.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false),
                progress = e.getInt(Notification.EXTRA_PROGRESS, 0),
                progressMax = e.getInt(Notification.EXTRA_PROGRESS_MAX, 0),
                progressIndeterminate = e.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false),
                callType = e.getInt(Notification.EXTRA_CALL_TYPE, 0),
                callerName = person?.name?.toString(),
                callIsVideo = e.getBoolean(Notification.EXTRA_CALL_IS_VIDEO, false),
                hasAnswer = e.containsKey(Notification.EXTRA_ANSWER_INTENT),
                hasDecline = e.containsKey(Notification.EXTRA_DECLINE_INTENT),
                hasHangUp = e.containsKey(Notification.EXTRA_HANG_UP_INTENT),
                hasFullScreenIntent = n.fullScreenIntent != null,
                hasContentIntent = n.contentIntent != null,
                isMedia = isMedia,
                color = n.color,
                actions = n.actions.orEmpty().map {
                    SnapshotAction(it.title?.toString().orEmpty(), !it.remoteInputs.isNullOrEmpty())
                },
                custom = if (wantsCustom) extractCustom(context, n) else null,
                smallIcon = n.smallIcon,
                largeIcon = n.getLargeIcon(),
                callerIcon = person?.icon,
            )
        }

        /**
         * 独自レイアウトの通知（Google 時計は RemoteViews の Chronometer で時間を出す）を
         * 自分のプロセスで一度展開し、中の Chronometer と文字を拾う。
         */
        @Suppress("DEPRECATION")
        private fun extractCustom(context: Context, n: Notification): CustomContent? {
            val rv = n.contentView ?: return null
            val root = try {
                rv.apply(context, FrameLayout(context))
            } catch (t: Throwable) {
                Log.w(TAG, "RemoteViews を展開できなかった", t)
                return null
            }
            var chrono: Chronometer? = null
            val texts = mutableListOf<String>()
            fun walk(v: View) {
                when (v) {
                    is Chronometer -> if (chrono == null) chrono = v
                    is TextView -> v.text?.toString()?.takeIf { it.isNotBlank() }?.let(texts::add)
                    is ViewGroup -> for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
            }
            walk(root)
            val toWall = System.currentTimeMillis() - SystemClock.elapsedRealtime()
            val c = chrono
            return CustomContent(
                chronometerBaseWall = c?.base?.plus(toWall),
                chronometerCountDown = c?.isCountDown ?: false,
                texts = texts,
            )
        }
    }
}
