package dev.ryunosuke.island.island

object TimeFormat {
    /** タイマー・通話時間: "4:59" / "1:02:03" */
    fun clock(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        val h = total / 3600
        val m = (total / 60) % 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** カウントダウンは切り上げて表示する（残り 0.4 秒を "0:00" にしない） */
    fun countdown(ms: Long): String = clock(((ms.coerceAtLeast(0) + 999) / 1000) * 1000)

    /** ストップウォッチ: 1 時間未満は "0:12.34"、以降は "1:02:03" */
    fun stopwatch(ms: Long): String {
        val v = ms.coerceAtLeast(0)
        if (v >= 3_600_000) return clock(v)
        val m = v / 60_000
        val s = (v / 1000) % 60
        val cs = (v / 10) % 100
        return "%d:%02d.%02d".format(m, s, cs)
    }

    /** 再生位置: "1:23"。残りは頭に "-" を付ける */
    fun media(ms: Long, remaining: Boolean = false): String =
        (if (remaining) "-" else "") + clock(ms)
}
