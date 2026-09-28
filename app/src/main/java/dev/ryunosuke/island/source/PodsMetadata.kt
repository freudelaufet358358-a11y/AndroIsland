package dev.ryunosuke.island.source

import dev.ryunosuke.island.island.PodsBattery

/**
 * Bluetooth 機器のメタデータ（BluetoothDevice#getMetadata）から、イヤホンの左右とケースの電池を読む。
 *
 * AOSP にはイヤホンの電池の内訳を取る仕組みが無く、設定アプリの機器の画面は、このメタデータに書かれた値を出すだけ
 * （Pixel Buds なら Fast Pair が書く）。Evolution X では BtHelper（OpenPods・CAPod・LibrePods を元にしたシステムアプリ）が、
 * AirPods の BLE の広告と AACP（L2CAP の Apple の独自の通信）から読んだ電池を同じ形で書く。
 * 値は 10 進の文字列（不明は "-1"）、充電中かは "TRUE" / "FALSE"。
 * 書き終わってから、全体の残量（左右の低い方）を電池の知らせ（ACTION_BATTERY_LEVEL_CHANGED）で流す。
 *
 * キーは @SystemApi なので数値で持つ（Android 16 の BluetoothDevice.METADATA_* と同じ値）。
 */
object PodsMetadata {
    const val LEFT_BATTERY = 10
    const val RIGHT_BATTERY = 11
    const val CASE_BATTERY = 12
    const val LEFT_CHARGING = 13
    const val RIGHT_CHARGING = 14
    const val CASE_CHARGING = 15
    const val MAIN_BATTERY = 18
    const val MAIN_CHARGING = 19

    /** 読むキー */
    val KEYS = intArrayOf(
        LEFT_BATTERY, RIGHT_BATTERY, CASE_BATTERY,
        LEFT_CHARGING, RIGHT_CHARGING, CASE_CHARGING,
        MAIN_BATTERY, MAIN_CHARGING,
    )

    /** 読めた値（キー → 中身。書かれていなければ null）から電池を組み立てる。何も分からなければ null */
    fun parse(values: Map<Int, ByteArray?>): PodsBattery? {
        fun text(key: Int) = values[key]?.toString(Charsets.UTF_8)?.trim()
        fun part(battery: Int, charging: Int) = text(battery)?.toIntOrNull()?.takeIf { it in 0..100 }?.let {
            PodsBattery.Part(it, text(charging).equals("true", ignoreCase = true))
        }
        return PodsBattery(
            left = part(LEFT_BATTERY, LEFT_CHARGING),
            right = part(RIGHT_BATTERY, RIGHT_CHARGING),
            case = part(CASE_BATTERY, CASE_CHARGING),
            main = part(MAIN_BATTERY, MAIN_CHARGING),
        ).takeIf { it.hasParts || it.main != null }
    }

    /**
     * 読んだ値が、今届いた電池の知らせ（[level]）と同じ時に書かれたものか。
     * BtHelper は接続が切れてもメタデータを消さないので、つないだ直後は前の接続の古い値が残っている。
     * BtHelper は全体の残量を書いてから知らせるので、それと合えば今の接続の値とみなす
     * （HFP でイヤホン自身が送ってくる 10% 刻みの知らせとは、たいてい合わない）
     */
    fun matches(b: PodsBattery, level: Int): Boolean = b.main?.level == level
}
