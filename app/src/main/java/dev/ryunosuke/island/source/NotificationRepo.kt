package dev.ryunosuke.island.source

import android.service.notification.StatusBarNotification
import dev.ryunosuke.island.data.IslandSettings
import dev.ryunosuke.island.island.IslandActivity
import dev.ryunosuke.island.island.LiveActivity
import dev.ryunosuke.island.island.TimerActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * 通知リスナーが受けた通知を持っておき、島の活動に変換して流す。
 * ボタンを押したときに PendingIntent を引き直せるよう、実物の StatusBarNotification も持つ。
 */
class NotificationRepo(
    settings: StateFlow<IslandSettings>,
    private val ownPackage: String,
) {
    private val sbns = ConcurrentHashMap<String, StatusBarNotification>()
    private val snapshots = MutableStateFlow<Map<String, NotificationSnapshot>>(emptyMap())

    /** タイマーの全長は通知から分からないので、見えた残り時間の最大値で代用する */
    private val timerTotals = ConcurrentHashMap<String, Long>()

    /** 「その他の進行中のもの」を出したことのあるアプリ。設定の除外リストに並べる */
    val seenLivePackages = MutableStateFlow<Set<String>>(emptySet())

    /** 直近の生データ。実機で判定を合わせ込むときに設定画面から書き出す */
    val rawSnapshots: StateFlow<Map<String, NotificationSnapshot>> get() = snapshots

    val activities: Flow<List<IslandActivity>> = combine(snapshots, settings) { snaps, s ->
        val opts = ParserOptions(
            calls = s.calls,
            clock = s.clockMirror,
            navigation = s.navigation,
            otherLive = s.otherLive,
            ownPackage = ownPackage,
            excludedPackages = s.excludedPackages,
        )
        snaps.values
            .sortedByDescending { it.postTime }
            .mapNotNull { NotificationParser.parse(it, opts) }
            .map { a ->
                if (a is LiveActivity) seenLivePackages.update { it + a.packageName }
                if (a is TimerActivity) a.copy(totalMs = estimateTotal(a)) else a
            }
    }

    private fun estimateTotal(t: TimerActivity): Long {
        val atPost = if (t.chrono.running) t.chrono.baseWall - t.postTime else t.chrono.frozenMs
        return timerTotals.merge(t.key, atPost.coerceAtLeast(1)) { a, b -> maxOf(a, b) }!!
    }

    fun get(key: String): StatusBarNotification? = sbns[key]

    fun reset(all: List<Pair<StatusBarNotification, NotificationSnapshot>>) {
        sbns.clear()
        all.forEach { (sbn, _) -> sbns[sbn.key] = sbn }
        snapshots.value = all.associate { (sbn, snap) -> sbn.key to snap }
        timerTotals.keys.retainAll(snapshots.value.keys)
    }

    fun posted(sbn: StatusBarNotification, snap: NotificationSnapshot) {
        sbns[sbn.key] = sbn
        snapshots.update { it + (sbn.key to snap) }
    }

    fun removed(key: String) {
        sbns.remove(key)
        timerTotals.remove(key)
        snapshots.update { it - key }
    }
}
