package dev.ryunosuke.island

import android.app.Application
import dev.ryunosuke.island.builtin.ClockEngine
import dev.ryunosuke.island.data.SettingsStore
import dev.ryunosuke.island.island.ActionRunner
import dev.ryunosuke.island.island.DemoController
import dev.ryunosuke.island.island.IslandActivity
import dev.ryunosuke.island.island.IslandArbiter
import dev.ryunosuke.island.source.AudioSpectrum
import dev.ryunosuke.island.source.BatterySaver
import dev.ryunosuke.island.source.MediaSource
import dev.ryunosuke.island.source.NotificationRepo
import dev.ryunosuke.island.source.RecentsWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine

class IslandApp : Application() {
    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
    }

    companion object {
        lateinit var graph: Graph
            private set
    }
}

/** プロセスに一つずつの部品。DI は使わずここで組む */
class Graph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settingsStore = SettingsStore(app, scope)
    val settings = settingsStore.settings
    val notifications = NotificationRepo(settings, app.packageName)
    val recents = RecentsWatcher(app)
    val media = MediaSource(app, scope, settings, dismissedByRecents = recents::isOpenOrJustClosed)
    val clock = ClockEngine(app)
    val spectrum = AudioSpectrum(app)

    /** 省電力（バッテリー セーバー）が入っているか。電池残量低下の表示がこの値で色を変える */
    val batterySaverOn = kotlinx.coroutines.flow.MutableStateFlow(BatterySaver.isOn(app))
    val demo = DemoController(scope)

    /** 実機比較用: 設定に関係なく待機時の島を出す */
    val forceIdle = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** 実機比較用: 全画面でも島を出す（ステータスバーのアイコンを消した画面で測るため） */
    val forceVisible = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** 実機比較用: 設定の「位置と大きさ」を無視して、iPhone と同じ比率の寸法にする */
    val forceAutoSize = kotlinx.coroutines.flow.MutableStateFlow(false)

    /**
     * 実機確認用: 本物の活動（動いているストップウォッチなど）を隠し、このアプリ自身が出した試験用のもの
     * （デモ・見本の台本・受け口の試験用 MediaSession）だけを出す
     */
    val referenceOnly = kotlinx.coroutines.flow.MutableStateFlow(false)

    val arbiter = IslandArbiter(
        scope,
        combine(demo.activities, notifications.activities, clock.activities, media.state, referenceOnly) { d, n, c, m, only ->
            // 優先度が同じときはこの並び（デモ → 通知の新しい順 → 内蔵時計 → 音楽）が効く
            buildList<IslandActivity> {
                addAll(d)
                if (only) {
                    if (m != null && m.packageName == app.packageName) add(m)
                    return@buildList
                }
                addAll(n)
                addAll(c)
                if (m != null) add(m)
            }
        },
    )

    val actions = ActionRunner(app, this)
}
