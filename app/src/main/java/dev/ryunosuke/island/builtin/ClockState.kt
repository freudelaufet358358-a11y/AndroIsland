package dev.ryunosuke.island.builtin

/**
 * 内蔵ストップウォッチ。時刻はすべて elapsedRealtime（端末の時計合わせに影響されない）。
 */
data class StopwatchState(
    val running: Boolean = false,
    /** 動作中: 今回動かし始めた時刻 */
    val startElapsed: Long = 0,
    /** 前回までに積み上がった時間 */
    val accumulatedMs: Long = 0,
    /** ラップを取った時点の通算時間 */
    val laps: List<Long> = emptyList(),
) {
    val active: Boolean get() = running || accumulatedMs > 0

    fun elapsed(now: Long): Long = accumulatedMs + if (running) (now - startElapsed).coerceAtLeast(0) else 0

    fun toggle(now: Long): StopwatchState =
        if (running) copy(running = false, accumulatedMs = elapsed(now))
        else copy(running = true, startElapsed = now)

    fun lap(now: Long): StopwatchState = if (running) copy(laps = laps + elapsed(now)) else this

    fun reset(): StopwatchState = StopwatchState()
}

/**
 * 内蔵タイマー。鳴り始めたら ringing になり、止めるまで島に「終了」を出し続ける。
 */
data class TimerState(
    val totalMs: Long = 0,
    val running: Boolean = false,
    val endElapsed: Long = 0,
    /** 一時停止中の残り */
    val remainingMs: Long = 0,
    val ringing: Boolean = false,
) {
    val active: Boolean get() = totalMs > 0

    fun remaining(now: Long): Long = if (running) (endElapsed - now).coerceAtLeast(0) else remainingMs

    fun toggle(now: Long): TimerState = when {
        !active || ringing -> this
        running -> copy(running = false, remainingMs = remaining(now))
        else -> copy(running = true, endElapsed = now + remainingMs)
    }

    fun addMinute(now: Long): TimerState = when {
        !active -> this
        ringing -> start(60_000, now)
        running -> copy(endElapsed = endElapsed + 60_000, totalMs = totalMs + 60_000)
        else -> copy(remainingMs = remainingMs + 60_000, totalMs = totalMs + 60_000)
    }

    fun finish(): TimerState = if (active) copy(running = false, remainingMs = 0, ringing = true) else this

    companion object {
        fun start(totalMs: Long, now: Long) =
            TimerState(totalMs = totalMs, running = true, endElapsed = now + totalMs, remainingMs = totalMs)
    }
}
