package dev.ryunosuke.island.island

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** ユーザーの操作で決まる状態。活動の一覧とは独立に持つ */
data class Interaction(
    /** スワイプでしまったもの。key → しまったときの revision */
    val hidden: Map<String, Any> = emptyMap(),
    /** 副の丸を長押しして前に出したもの */
    val focusKey: String? = null,
    /** 長押しで展開したもの */
    val expandedKey: String? = null,
    /** 自動で展開されるもの（着信・鳴動）のうち、ユーザーが畳んだもの。key|revision */
    val collapsedAuto: Set<String> = emptySet(),
)

data class Arrangement(
    val primary: IslandActivity?,
    val secondary: IslandActivity?,
    val expanded: Boolean,
    /** スワイプでしまっていて、まだ続いている活動の数 */
    val hidden: Int = 0,
)

object Arrange {
    /** 何もしなくても展開して出すもの。iPhone でも着信とアラームは大きく出る */
    private val autoExpandKinds = setOf(Kind.IncomingCall, Kind.Ringing)

    fun autoKey(a: IslandActivity) = "${a.key}|${a.revision}"

    fun isAutoExpand(a: IslandActivity) = a.kind in autoExpandKinds

    fun arrange(activities: List<IslandActivity>, i: Interaction): Arrangement {
        val distinct = activities.distinctBy { it.key }
        val visible = distinct
            .filter { i.hidden[it.key] != it.revision }
            // sortedBy は安定なので、同じ優先度なら入力の順（= 新しいもの順）が保たれる
            .sortedByDescending { it.kind.priority }
        val focused = i.focusKey?.let { f -> visible.firstOrNull { it.key == f } }
        // 着信と鳴動は、長押しで前に出したものより優先する
        val ordered = if (focused != null && visible.first().kind.priority < Kind.Ringing.priority) {
            listOf(focused) + (visible - focused)
        } else {
            visible
        }
        val primary = ordered.firstOrNull()
        val expanded = primary != null && (
            primary.key == i.expandedKey ||
                (isAutoExpand(primary) && autoKey(primary) !in i.collapsedAuto)
            )
        return Arrangement(primary, ordered.getOrNull(1), expanded, hidden = distinct.size - visible.size)
    }

    /** もう存在しない活動への参照を捨てる（しまったものが溜まり続けないように） */
    fun prune(i: Interaction, activities: List<IslandActivity>): Interaction {
        val keys = activities.mapTo(HashSet()) { it.key }
        val autoKeys = activities.mapTo(HashSet()) { autoKey(it) }
        return Interaction(
            hidden = i.hidden.filterKeys { it in keys },
            focusKey = i.focusKey?.takeIf { it in keys },
            expandedKey = i.expandedKey?.takeIf { it in keys },
            collapsedAuto = i.collapsedAuto.filterTo(HashSet()) { it in autoKeys },
        )
    }
}

/**
 * 全部の Source から来た活動と一時表示をまとめて、島が今どう見えるべきかを決める。
 */
class IslandArbiter(
    private val scope: CoroutineScope,
    activities: Flow<List<IslandActivity>>,
) {
    private val interaction = MutableStateFlow(Interaction())
    private val alert = MutableStateFlow<IslandAlert?>(null)
    private var alertJob: Job? = null
    private var idleCollapseJob: Job? = null

    private val latest: StateFlow<List<IslandActivity>> =
        activities.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val presentation: StateFlow<IslandPresentation> =
        combine(latest, interaction, alert) { list, i, a ->
            val pruned = Arrange.prune(i, list)
            if (pruned != i) interaction.value = pruned
            val arr = Arrange.arrange(list, pruned)
            // 着信中と展開中は一時表示を割り込ませない
            val showAlert = a != null && !arr.expanded && arr.primary?.kind != Kind.IncomingCall
            IslandPresentation(
                primary = arr.primary,
                secondary = arr.secondary,
                alert = if (showAlert) a else null,
                expanded = arr.expanded,
                hidden = arr.hidden,
            )
        }.stateIn(scope, SharingStarted.Eagerly, IslandPresentation())

    /** 一時表示を出す。同じものを出し直すと、表示時間を durationMs から数え直す */
    fun postAlert(a: IslandAlert, durationMs: Long = a.durationMs) {
        alertJob?.cancel()
        alert.value = a
        alertJob = scope.launch {
            delay(durationMs)
            alert.value = null
        }
    }

    /** 今出している（出そうとしている）一時表示 */
    val currentAlert: IslandAlert? get() = alert.value

    /** 一時表示を引っ込める（スワイプで払ったとき） */
    fun dismissAlert() {
        alertJob?.cancel()
        alert.value = null
    }

    fun expand(key: String) {
        interaction.update { it.copy(focusKey = key, expandedKey = key) }
        touched()
    }

    fun collapse() {
        idleCollapseJob?.cancel()
        val p = presentation.value.primary
        interaction.update { i ->
            val auto = if (p != null && Arrange.isAutoExpand(p)) i.collapsedAuto + Arrange.autoKey(p) else i.collapsedAuto
            i.copy(expandedKey = null, focusKey = null, collapsedAuto = auto)
        }
    }

    /** 左右スワイプ。展開中なら畳むだけ、そうでなければしまう */
    fun swipeAway(key: String) {
        val pres = presentation.value
        if (pres.expanded) {
            collapse()
            return
        }
        val target = listOfNotNull(pres.primary, pres.secondary).firstOrNull { it.key == key } ?: return
        interaction.update { it.copy(hidden = it.hidden + (key to target.revision), focusKey = null) }
    }

    /** スワイプでしまったものを全部戻す（iPhone で島を外へスワイプしたときと同じ） */
    fun restoreHidden() {
        interaction.update { it.copy(hidden = emptyMap()) }
    }

    /**
     * 展開中の操作があったことを知らせる。しばらく触られなければ自分で畳む。
     * 着信と鳴動は放っておいても畳まない。
     */
    fun touched() {
        idleCollapseJob?.cancel()
        idleCollapseJob = scope.launch {
            delay(IDLE_COLLAPSE_MS)
            val p = presentation.value
            if (p.expanded && p.primary != null && !Arrange.isAutoExpand(p.primary)) collapse()
        }
    }

    companion object {
        const val IDLE_COLLAPSE_MS = 8_000L
    }
}
