package dev.ryunosuke.island.source

import android.graphics.drawable.Icon
import dev.ryunosuke.island.island.ActionRole
import dev.ryunosuke.island.island.ActionTarget
import dev.ryunosuke.island.island.ActionTarget.NotificationIntent.Which
import dev.ryunosuke.island.island.CallActivity
import dev.ryunosuke.island.island.Chrono
import dev.ryunosuke.island.island.IslandAction
import dev.ryunosuke.island.island.IslandActivity
import dev.ryunosuke.island.island.Kind
import dev.ryunosuke.island.island.LiveActivity
import dev.ryunosuke.island.island.NavigationActivity
import dev.ryunosuke.island.island.RingingActivity
import dev.ryunosuke.island.island.StopwatchActivity
import dev.ryunosuke.island.island.TimerActivity

data class SnapshotAction(val title: String, val hasRemoteInput: Boolean)

/** 独自レイアウト（RemoteViews）の通知を展開して拾えたもの。Google 時計など */
data class CustomContent(
    /** Chronometer の基準（壁時計に換算済み）。なければ null */
    val chronometerBaseWall: Long?,
    val chronometerCountDown: Boolean,
    val texts: List<String>,
)

/**
 * StatusBarNotification から、判定に要るものだけを抜いた素のデータ。
 * Android の実物を持たないので JVM のテストで組み立てられる。
 */
data class NotificationSnapshot(
    val key: String,
    val packageName: String,
    val postTime: Long,
    val whenTime: Long,
    val category: String? = null,
    val channelId: String? = null,
    /** EXTRA_TEMPLATE。"android.app.Notification$CallStyle" など */
    val template: String? = null,
    val ongoing: Boolean = false,
    /** FLAG_PROMOTED_ONGOING か、アプリが昇格を求めている（Android 16 の Live Updates） */
    val promoted: Boolean = false,
    val groupSummary: Boolean = false,
    val title: String? = null,
    val text: String? = null,
    val subText: String? = null,
    val shortCriticalText: String? = null,
    val showChronometer: Boolean = false,
    val chronometerCountDown: Boolean = false,
    val progress: Int = 0,
    val progressMax: Int = 0,
    val progressIndeterminate: Boolean = false,
    /** EXTRA_CALL_TYPE。1 = 着信, 2 = 通話中, 3 = スクリーニング */
    val callType: Int = 0,
    val callerName: String? = null,
    val callIsVideo: Boolean = false,
    val hasAnswer: Boolean = false,
    val hasDecline: Boolean = false,
    val hasHangUp: Boolean = false,
    val hasFullScreenIntent: Boolean = false,
    val hasContentIntent: Boolean = false,
    /** MediaStyle か、メディアセッションを持つ（音楽は MediaSession 側で扱う） */
    val isMedia: Boolean = false,
    val color: Int = 0,
    val actions: List<SnapshotAction> = emptyList(),
    val custom: CustomContent? = null,
    val smallIcon: Icon? = null,
    val largeIcon: Icon? = null,
    val callerIcon: Icon? = null,
)

data class ParserOptions(
    val calls: Boolean = true,
    val clock: Boolean = true,
    val navigation: Boolean = true,
    val otherLive: Boolean = true,
    val ownPackage: String = "dev.ryunosuke.island",
    val excludedPackages: Set<String> = emptySet(),
)

object NotificationParser {

    private const val CATEGORY_CALL = "call"
    private const val CATEGORY_ALARM = "alarm"
    private const val CATEGORY_STOPWATCH = "stopwatch"
    private const val CATEGORY_NAVIGATION = "navigation"
    private const val CALL_STYLE = "\$CallStyle"
    private const val PROGRESS_STYLE = "\$ProgressStyle"

    val clockPackages = setOf(
        "com.google.android.deskclock",
        "com.android.deskclock",
        "org.lineageos.deskclock",
        "com.sec.android.app.clockpackage",
        "com.oneplus.deskclock",
    )

    fun parse(s: NotificationSnapshot, o: ParserOptions): IslandActivity? {
        if (s.groupSummary || s.isMedia || s.packageName == o.ownPackage) return null
        if (isCall(s)) return if (o.calls) parseCall(s) else null
        if (isClockish(s)) return if (o.clock) parseClock(s) else null
        if (s.category == CATEGORY_NAVIGATION) return if (o.navigation) parseNavigation(s) else null
        if (!o.otherLive || s.packageName in o.excludedPackages) return null
        return parseLive(s)
    }

    // ---- 通話 ----

    private fun isCall(s: NotificationSnapshot): Boolean {
        if (s.template?.endsWith(CALL_STYLE) == true) return true
        if (s.category != CATEGORY_CALL) return false
        // category=call でも不在着信の通知は ongoing でも全画面でもない
        return s.ongoing || s.hasFullScreenIntent || s.callType != 0
    }

    private fun parseCall(s: NotificationSnapshot): CallActivity {
        val roles = s.actions.mapIndexed { i, a -> i to roleOf(a.title) }
        val incoming = when (s.callType) {
            1 -> true
            2, 3 -> false
            else -> s.hasAnswer || roles.any { it.second == ActionRole.Answer }
        }
        val actions = buildList {
            // 通知に付いている独自のボタン（スピーカーなど）。応答・拒否・終了は下で CallStyle の intent から作る
            for ((i, role) in roles) {
                val a = s.actions[i]
                if (a.hasRemoteInput) continue
                val covered = (role == ActionRole.Answer && s.hasAnswer) ||
                    (role == ActionRole.Decline && s.hasDecline) ||
                    (role == ActionRole.HangUp && s.hasHangUp)
                if (!covered) add(IslandAction(role, a.title, ActionTarget.NotificationAction(s.key, i)))
            }
            if (s.hasDecline) add(IslandAction(ActionRole.Decline, "拒否", ActionTarget.NotificationIntent(s.key, Which.Decline)))
            if (s.hasHangUp) add(IslandAction(ActionRole.HangUp, "終了", ActionTarget.NotificationIntent(s.key, Which.HangUp)))
            if (s.hasAnswer) add(IslandAction(ActionRole.Answer, "応答", ActionTarget.NotificationIntent(s.key, Which.Answer)))
        }
        val chrono = if (!incoming && s.whenTime > 0) Chrono(true, s.whenTime, 0, countDown = false) else null
        return CallActivity(
            key = s.key,
            packageName = s.packageName,
            incoming = incoming,
            name = s.callerName?.takeIf { it.isNotBlank() } ?: s.title.orEmpty(),
            detail = s.text,
            avatar = s.callerIcon ?: s.largeIcon,
            chrono = chrono,
            video = s.callIsVideo,
            actions = actions,
            postTime = s.postTime,
            open = contentTarget(s),
        )
    }

    // ---- 時計（タイマー・ストップウォッチ・アラーム） ----

    private fun isClockish(s: NotificationSnapshot) =
        s.packageName in clockPackages || s.category == CATEGORY_STOPWATCH || s.category == CATEGORY_ALARM

    private fun parseClock(s: NotificationSnapshot): IslandActivity? {
        val actions = mappedActions(s)
        val roles = actions.map { it.role }.toSet()

        // 鳴っているもの（アラーム、終わったタイマー）は全画面インテントを持つ
        if (s.hasFullScreenIntent && (s.category == CATEGORY_ALARM || s.packageName in clockPackages)) {
            val texts = allTexts(s)
            val isTimer = texts.any { looksLike(it, TIMER_WORDS) } || s.channelId.orEmpty().contains("timer", true)
            return RingingActivity(
                key = s.key,
                packageName = s.packageName,
                title = s.title ?: if (isTimer) "タイマー" else "アラーム",
                detail = s.text,
                isTimer = isTimer,
                actions = actions,
                postTime = s.postTime,
                open = contentTarget(s),
            )
        }

        val chrono = chronoOf(s, roles)
        val texts = allTexts(s)
        val channel = s.channelId.orEmpty()
        val isStopwatch = s.category == CATEGORY_STOPWATCH || channel.contains("stopwatch", true) ||
            texts.any { looksLike(it, STOPWATCH_WORDS) }
        val isTimer = !isStopwatch && (chrono?.countDown == true || channel.contains("timer", true) ||
            texts.any { looksLike(it, TIMER_WORDS) })

        return when {
            isStopwatch -> StopwatchActivity(
                key = s.key,
                packageName = s.packageName,
                chrono = chrono?.copy(countDown = false) ?: Chrono(false, 0, parseTimeText(texts) ?: 0, false),
                preciseFraction = chrono != null,
                lapCount = texts.firstNotNullOfOrNull { lapNumber(it) } ?: 0,
                actions = actions,
                postTime = s.postTime,
                open = contentTarget(s),
            )
            isTimer -> TimerActivity(
                key = s.key,
                packageName = s.packageName,
                label = texts.firstOrNull { parseTimeText(listOf(it)) == null && !looksLike(it, TIMER_WORDS) },
                chrono = chrono?.copy(countDown = true) ?: Chrono(false, 0, parseTimeText(texts) ?: 0, true),
                totalMs = null,
                actions = actions,
                postTime = s.postTime,
                open = contentTarget(s),
            )
            // 次のアラームの予告などは島に出さない
            else -> null
        }
    }

    /**
     * 通知から時計を組み立てる。標準のテンプレートなら showChronometer と when、
     * 独自レイアウトなら中の Chronometer を使う。動いているかはボタンから推し量る
     * （「一時停止」があれば動いている）。
     */
    private fun chronoOf(s: NotificationSnapshot, roles: Set<ActionRole>): Chrono? {
        val running = when {
            ActionRole.Pause in roles -> true
            ActionRole.Play in roles -> false
            else -> s.ongoing
        }
        if (s.showChronometer && s.whenTime > 0) {
            val cd = s.chronometerCountDown
            return if (running) {
                Chrono(true, s.whenTime, 0, cd)
            } else {
                Chrono(false, s.whenTime, frozen(s.whenTime, s.postTime, cd), cd)
            }
        }
        val c = s.custom ?: return null
        val base = c.chronometerBaseWall ?: return null
        val cd = c.chronometerCountDown
        return if (running) Chrono(true, base, 0, cd) else Chrono(false, base, frozen(base, s.postTime, cd), cd)
    }

    /** 止まっている時計は、投稿された時点で表示していた値のまま */
    private fun frozen(base: Long, postTime: Long, countDown: Boolean) =
        (if (countDown) base - postTime else postTime - base).coerceAtLeast(0)

    // ---- ナビ ----

    private fun parseNavigation(s: NotificationSnapshot): NavigationActivity? {
        val title = s.title ?: s.text ?: return null
        val distance = s.shortCriticalText ?: title.takeIf { DISTANCE.containsMatchIn(it) && it.length <= 12 }
        val instruction = if (distance == title) s.text ?: title else title
        return NavigationActivity(
            key = s.key,
            packageName = s.packageName,
            maneuver = s.largeIcon,
            distance = distance ?: DISTANCE.find(title)?.value,
            instruction = instruction,
            detail = s.subText ?: s.text?.takeIf { it != instruction },
            open = contentTarget(s),
        )
    }

    // ---- その他の進行中のもの ----

    private fun parseLive(s: NotificationSnapshot): LiveActivity? {
        if (!s.ongoing && !s.promoted) return null
        val hasProgress = s.progressMax > 0 || s.progressIndeterminate || s.template?.endsWith(PROGRESS_STYLE) == true
        // 常駐サービスの定型通知（「〜が実行中」など）は拾わない。進捗・時計・昇格のどれかを持つものだけ
        if (!s.promoted && !hasProgress && !s.showChronometer) return null
        val title = s.title?.takeIf { it.isNotBlank() } ?: return null
        val chrono = if (s.showChronometer && s.whenTime > 0) {
            Chrono(true, s.whenTime, 0, s.chronometerCountDown)
        } else null
        return LiveActivity(
            key = s.key,
            packageName = s.packageName,
            kind = if (chrono != null && !hasProgress) Kind.Recording else Kind.Progress,
            smallIcon = s.smallIcon,
            largeIcon = s.largeIcon,
            color = s.color,
            title = title,
            text = s.text,
            shortText = s.shortCriticalText,
            progress = if (s.progressMax > 0 && !s.progressIndeterminate) {
                (s.progress.toFloat() / s.progressMax).coerceIn(0f, 1f)
            } else null,
            indeterminate = s.progressIndeterminate,
            chrono = chrono,
            actions = mappedActions(s).take(3),
            postTime = s.postTime,
            open = contentTarget(s),
        )
    }

    // ---- 共通 ----

    private fun contentTarget(s: NotificationSnapshot): ActionTarget =
        if (s.hasContentIntent) ActionTarget.NotificationIntent(s.key, Which.Content) else ActionTarget.Launch(s.packageName)

    private fun mappedActions(s: NotificationSnapshot) = s.actions.mapIndexedNotNull { i, a ->
        if (a.hasRemoteInput || a.title.isBlank()) null
        else IslandAction(roleOf(a.title), a.title, ActionTarget.NotificationAction(s.key, i))
    }

    private fun allTexts(s: NotificationSnapshot) =
        listOfNotNull(s.title, s.text, s.subText).filter { it.isNotBlank() } + s.custom?.texts.orEmpty()

    private val STOPWATCH_WORDS = listOf("ストップウォッチ", "stopwatch")
    private val TIMER_WORDS = listOf("タイマー", "timer", "時間です", "time's up", "times up")
    private fun looksLike(text: String, words: List<String>) = words.any { text.contains(it, ignoreCase = true) }

    private val DISTANCE = Regex("""\d+(?:[.,]\d+)?\s?(?:km|m|mi|ft|キロ|メートル)""", RegexOption.IGNORE_CASE)
    private val TIME = Regex("""(?<![\d:])(?:(\d{1,3}):)?(\d{1,2}):(\d{2})(?:[.,](\d{1,2}))?(?![\d:])""")
    private val LAP = Regex("""(?:ラップ|Lap)\s*(\d+)""", RegexOption.IGNORE_CASE)

    /** "1:02:03" / "4:59" / "0:12.34" をミリ秒に。最初に見つかったものを使う */
    fun parseTimeText(texts: List<String>): Long? {
        for (t in texts) {
            val m = TIME.find(t) ?: continue
            val (h, mi, se, frac) = m.destructured
            var ms = ((h.toLongOrNull() ?: 0) * 3600 + mi.toLong() * 60 + se.toLong()) * 1000
            if (frac.isNotEmpty()) ms += frac.padEnd(3, '0').take(3).toLong()
            return ms
        }
        return null
    }

    private fun lapNumber(text: String) = LAP.find(text)?.groupValues?.get(1)?.toIntOrNull()

    /** ボタンの文言から意味を当てる。Google 時計・電話アプリの日本語と英語 */
    fun roleOf(title: String): ActionRole {
        val t = title.trim().lowercase()
        fun has(vararg w: String) = w.any { t.contains(it) }
        return when {
            has("一時停止", "pause") -> ActionRole.Pause
            has("再開", "開始", "スタート", "resume", "start", "続行") -> ActionRole.Play
            has("ラップ", "lap") -> ActionRole.Lap
            has("リセット", "reset") -> ActionRole.Reset
            has("スヌーズ", "snooze") -> ActionRole.Snooze
            has("+1", "+ 1", "1 分", "1分", "add 1", "1 min") -> ActionRole.AddTime
            has("応答", "answer", "出る") -> ActionRole.Answer
            has("拒否", "decline", "reject") -> ActionRole.Decline
            has("通話を終了", "切る", "hang up", "end call", "終話") -> ActionRole.HangUp
            has("スピーカー", "speaker") -> ActionRole.Speaker
            has("ミュート", "mute") -> ActionRole.Mute
            has("削除", "キャンセル", "delete", "cancel") -> ActionRole.Cancel
            has("停止", "解除", "stop", "dismiss", "終了") -> ActionRole.Stop
            else -> ActionRole.Generic
        }
    }
}
