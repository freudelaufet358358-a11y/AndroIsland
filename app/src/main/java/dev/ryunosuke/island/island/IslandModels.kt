package dev.ryunosuke.island.island

import android.graphics.Bitmap
import android.graphics.drawable.Icon

/**
 * 島に出す「進行中のもの」の種類。数字が大きいほど主役になりやすい。
 * 並びは iPhone の優先度に寄せている（着信 > 通話 > 鳴っているもの > ナビ > タイマー > …）。
 */
enum class Kind(val priority: Int) {
    IncomingCall(100),
    OngoingCall(90),
    Ringing(80),
    Navigation(70),
    Timer(60),
    Stopwatch(50),
    Recording(40),
    Media(30),
    Progress(20),
}

/**
 * 経過時間・残り時間の表示元。基準はすべて壁時計（System.currentTimeMillis）で、通知の `when` と揃えている。
 *
 * - 数え上げで動作中: baseWall は開始時刻
 * - カウントダウンで動作中: baseWall は終了時刻
 * - 停止中: frozenMs をそのまま出す
 */
data class Chrono(
    val running: Boolean,
    val baseWall: Long,
    val frozenMs: Long,
    val countDown: Boolean,
) {
    fun valueAt(nowWall: Long): Long = when {
        !running -> frozenMs
        countDown -> (baseWall - nowWall).coerceAtLeast(0)
        else -> (nowWall - baseWall).coerceAtLeast(0)
    }
}

/** ボタンの意味。見た目（アイコンと色）と、押したあと島を閉じるかどうかを決める */
enum class ActionRole {
    Play, Pause, Lap, Reset, Stop, Snooze, AddTime, Cancel,
    Answer, Decline, HangUp, Speaker, Mute, Generic;

    /** 押したら島を畳むもの（iPhone でも応答・拒否・停止のあとは閉じる） */
    val collapsesIsland: Boolean
        get() = this == Answer || this == Decline || this == HangUp || this == Stop ||
            this == Snooze || this == Cancel
}

enum class MediaCommand { PlayPause, Next, Previous, Forward, Rewind, Output }

enum class ClockCommand {
    StopwatchToggle, StopwatchLap, StopwatchReset,
    TimerToggle, TimerCancel, TimerAddMinute, TimerStopRinging, TimerRepeat,
}

/** 押されたときの行き先。PendingIntent などは持たず、実行時に ActionRunner が引き直す（テストできるように） */
sealed interface ActionTarget {
    data class NotificationAction(val notifKey: String, val index: Int) : ActionTarget
    data class NotificationIntent(val notifKey: String, val which: Which) : ActionTarget {
        enum class Which { Content, Answer, Decline, HangUp }
    }
    data class Media(val sessionKey: String, val command: MediaCommand) : ActionTarget
    data class MediaSeek(val sessionKey: String, val positionMs: Long) : ActionTarget
    data class MediaOpen(val sessionKey: String) : ActionTarget
    data class Builtin(val command: ClockCommand) : ActionTarget
    data class Launch(val packageName: String) : ActionTarget
    data class Demo(val command: String) : ActionTarget
    /** 省電力（バッテリー セーバー）を入れる・切る */
    data object BatterySaverToggle : ActionTarget
}

data class IslandAction(val role: ActionRole, val label: String, val target: ActionTarget)

sealed interface IslandActivity {
    /** 同じものを指し続ける識別子（通知のキー、セッションなど） */
    val key: String
    val kind: Kind
    val packageName: String

    /** スワイプでしまったあと、これが変わったら再び出す */
    val revision: Any

    /** タップで開く先 */
    val open: ActionTarget?
}

data class MediaActivity(
    override val key: String,
    override val packageName: String,
    val title: String,
    val artist: String,
    val art: Bitmap?,
    /** ジャケットから取った波形の色 */
    val accent: Int,
    val playing: Boolean,
    val durationMs: Long,
    val positionMs: Long,
    /** positionMs を測った時刻（elapsedRealtime） */
    val positionAtElapsed: Long,
    val speed: Float,
    val canPrevious: Boolean,
    val canNext: Boolean,
    val canSeek: Boolean,
    /** 曲送りがなく早送り・巻き戻しだけのアプリ（ポッドキャストなど）。±15 秒のボタンを出す */
    val skipByTime: Boolean,
    override val open: ActionTarget?,
) : IslandActivity {
    override val kind get() = Kind.Media
    override val revision: Any get() = "$title\u0000$artist"

    fun positionAt(elapsedNow: Long): Long {
        if (!playing) return positionMs
        val p = positionMs + ((elapsedNow - positionAtElapsed) * speed).toLong()
        return if (durationMs > 0) p.coerceIn(0, durationMs) else p.coerceAtLeast(0)
    }
}

data class CallActivity(
    override val key: String,
    override val packageName: String,
    val incoming: Boolean,
    val name: String,
    val detail: String?,
    val avatar: Icon?,
    val chrono: Chrono?,
    val video: Boolean,
    val actions: List<IslandAction>,
    val postTime: Long,
    override val open: ActionTarget?,
) : IslandActivity {
    override val kind get() = if (incoming) Kind.IncomingCall else Kind.OngoingCall
    override val revision: Any get() = incoming
}

data class TimerActivity(
    override val key: String,
    override val packageName: String,
    val label: String?,
    /** countDown = true の Chrono */
    val chrono: Chrono,
    /** 全体の長さ。分からなければ null（リングの代わりにアイコンを出す） */
    val totalMs: Long?,
    val actions: List<IslandAction>,
    val postTime: Long,
    override val open: ActionTarget?,
) : IslandActivity {
    override val kind get() = Kind.Timer
    override val revision: Any get() = chrono.running
}

data class StopwatchActivity(
    override val key: String,
    override val packageName: String,
    val chrono: Chrono,
    /**
     * 1/100 秒まで正しいか。Google 時計は一時停止中の通知を「00:03」という文字だけにするので、
     * そこから読んだ値は秒までしか分からない
     */
    val preciseFraction: Boolean = true,
    val lapCount: Int,
    val actions: List<IslandAction>,
    val postTime: Long,
    override val open: ActionTarget?,
) : IslandActivity {
    override val kind get() = Kind.Stopwatch
    override val revision: Any get() = chrono.running
}

/** アラームの鳴動、タイマーの終了 */
data class RingingActivity(
    override val key: String,
    override val packageName: String,
    val title: String,
    val detail: String?,
    val isTimer: Boolean,
    val actions: List<IslandAction>,
    val postTime: Long,
    override val open: ActionTarget?,
) : IslandActivity {
    override val kind get() = Kind.Ringing
    override val revision: Any get() = postTime
}

data class NavigationActivity(
    override val key: String,
    override val packageName: String,
    /** 曲がる方向の矢印（マップは largeIcon に入れてくる） */
    val maneuver: Icon?,
    val distance: String?,
    val instruction: String,
    val detail: String?,
    override val open: ActionTarget?,
) : IslandActivity {
    override val kind get() = Kind.Navigation
    override val revision: Any get() = instruction
}

/** 上のどれにも当たらない進行中の通知（配達、DL、画面収録、録音など） */
data class LiveActivity(
    override val key: String,
    override val packageName: String,
    override val kind: Kind,
    val smallIcon: Icon?,
    val largeIcon: Icon?,
    val color: Int,
    val title: String,
    val text: String?,
    val shortText: String?,
    /** 0..1。不明なら null */
    val progress: Float?,
    val indeterminate: Boolean,
    val chrono: Chrono?,
    val actions: List<IslandAction>,
    val postTime: Long,
    override val open: ActionTarget?,
) : IslandActivity {
    override val revision: Any get() = title
}

/**
 * Apple の映像の見本と同じ寸法の、中身のない島。実機の録画と見本を重ねて比べるためだけに使う。
 * 大きさは島の高さ H を単位にする（映像: コンパクト 5.97H、展開 3.515H）。
 */
data class ReferenceActivity(
    override val key: String,
    val compactWidthH: Float,
    val expandedHeightH: Float,
) : IslandActivity {
    override val kind get() = Kind.Progress
    override val packageName get() = "dev.ryunosuke.island"
    override val revision: Any get() = key
    override val open: ActionTarget? get() = null
}

/**
 * 左右が分かれたイヤホン（AirPods など）の電池。分からない部分は null
 * （ケースは、イヤホンを入れて蓋を開けたときしか分からないことが多い）。
 * ヘッドホン型（AirPods Max など）は [main] だけ
 */
data class PodsBattery(
    val left: Part? = null,
    val right: Part? = null,
    val case: Part? = null,
    val main: Part? = null,
) {
    /** level は 0〜100 */
    data class Part(val level: Int, val charging: Boolean)

    /** 左右とケースの内訳がある */
    val hasParts: Boolean get() = left != null || right != null || case != null

    /** 島のコンパクトに出す 1 つの値。左右の低い方（BtHelper が全体の残量として知らせるのと同じ）。左右が分からなければ全体の値 */
    val headset: Int? get() = listOfNotNull(left?.level, right?.level).minOrNull() ?: main?.level
}

/** 一瞬だけ出して引っ込める知らせ（充電、消音など） */
sealed interface IslandAlert {
    /** 同じ種類が続いたら差し替える */
    val type: String
    val durationMs: Long get() = 2_500

    data class Charging(val level: Int) : IslandAlert { override val type get() = "charging" }
    /**
     * 電池残量の低下（iOS 26 Beta 5 と同じく展開した形で出し、タップで省電力を入れる・切る）。
     * 省電力が入っているか（黄色の表示）は、出した時点の値ではなく今の状態を表示側が読む
     */
    data class LowBattery(val level: Int) : IslandAlert {
        override val type get() = "battery"
        override val durationMs get() = 6_000L
    }
    /** mode は AudioManager.RINGER_MODE_* */
    data class Ringer(val mode: Int) : IslandAlert { override val type get() = "ringer" }
    data class Dnd(val on: Boolean) : IslandAlert { override val type get() = "dnd" }

    /**
     * イヤホン・ヘッドホンがつながった。battery は全体の残量（左右が分かれたものは低い方）。
     * pods は左右とケースの内訳（Evolution X の BtHelper が書いたものを Shizuku で読めたときだけ）。
     * detail は内訳を展開した形で出しているか（コンパクトをタップ・長押しすると開く）
     */
    data class Device(
        val name: String,
        val battery: Int?,
        val wired: Boolean,
        /** Bluetooth のアドレス。あとから届いた電池で、出している表示を書き換えるのに使う */
        val address: String? = null,
        val pods: PodsBattery? = null,
        val detail: Boolean = false,
    ) : IslandAlert {
        override val type get() = "device"
        override val durationMs get() = if (detail) 6_000L else 3_500L

        /** タップ・長押しで左右とケースの内訳を開ける */
        val expandable: Boolean get() = !detail && pods?.hasParts == true
    }
    data object Unlock : IslandAlert {
        override val type get() = "unlock"
        override val durationMs get() = 1_300L
    }
}

/** 島が今どう見えるべきか */
data class IslandPresentation(
    val primary: IslandActivity? = null,
    val secondary: IslandActivity? = null,
    val alert: IslandAlert? = null,
    /** primary を展開しているか */
    val expanded: Boolean = false,
    /** スワイプでしまっていて、まだ続いている活動の数（島をタップすると戻る） */
    val hidden: Int = 0,
)
