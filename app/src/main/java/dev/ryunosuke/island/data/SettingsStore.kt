package dev.ryunosuke.island.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class IslandSettings(
    // 何を出すか
    val media: Boolean = true,
    val calls: Boolean = true,
    val clockMirror: Boolean = true,
    val navigation: Boolean = true,
    val otherLive: Boolean = true,
    val charging: Boolean = true,
    val lowBattery: Boolean = true,
    val ringer: Boolean = true,
    val dnd: Boolean = true,
    val bluetooth: Boolean = true,
    val unlock: Boolean = true,
    /** 何もないときも島（カメラの周りの黒い部分）を出す。iPhone と同じ見え方 */
    val idleVisible: Boolean = false,
    // いつ隠すか
    val hideFullscreen: Boolean = true,
    val hideLandscape: Boolean = true,
    val hideShade: Boolean = true,
    // 振る舞い
    val haptics: Boolean = true,
    val mediaPausedTimeoutSec: Int = 60,
    /** 島の動きの速さ。1 = iOS 26 と同じ。2 で倍速、0.5 で半分の速さ */
    val animationSpeed: Float = 1f,
    /** 音楽の波形を実際の音に合わせる（端末の出力音声を解析する。オフなら擬似的な波） */
    val audioWaveform: Boolean = true,
    /**
     * 2 つ同時で主の島が狭くなっている（右に丸が離れる）ときも、印の右にストップウォッチ・タイマー・通話・録画の時間を出す。
     * オフなら iPhone と同じく印だけ
     */
    val splitTime: Boolean = true,
    // 位置と大きさの微調整。0 は「カットアウトから自動で決める」
    val heightDp: Float = 0f,
    val centerWidthDp: Float = 0f,
    /** 広がったとき（コンパクト）の幅。0 は自動（中身に合わせる。iPhone と同じ） */
    val compactWidthDp: Float = 0f,
    /** 展開したときの幅。0 は自動（画面幅から左右 0.265H を除いた幅） */
    val expandedWidthDp: Float = 0f,
    val offsetXDp: Float = 0f,
    val offsetYDp: Float = 0f,
    val excludedPackages: Set<String> = emptySet(),
)

private val Context.dataStore by preferencesDataStore("settings")

class SettingsStore(private val context: Context, scope: CoroutineScope) {

    private object K {
        val media = booleanPreferencesKey("media")
        val calls = booleanPreferencesKey("calls")
        val clockMirror = booleanPreferencesKey("clock_mirror")
        val navigation = booleanPreferencesKey("navigation")
        val otherLive = booleanPreferencesKey("other_live")
        val charging = booleanPreferencesKey("charging")
        val lowBattery = booleanPreferencesKey("low_battery")
        val ringer = booleanPreferencesKey("ringer")
        val dnd = booleanPreferencesKey("dnd")
        val bluetooth = booleanPreferencesKey("bluetooth")
        val unlock = booleanPreferencesKey("unlock")
        val idleVisible = booleanPreferencesKey("idle_visible")
        val hideFullscreen = booleanPreferencesKey("hide_fullscreen")
        val hideLandscape = booleanPreferencesKey("hide_landscape")
        val hideShade = booleanPreferencesKey("hide_shade")
        val haptics = booleanPreferencesKey("haptics")
        val mediaPausedTimeoutSec = intPreferencesKey("media_paused_timeout_sec")
        val animationSpeed = floatPreferencesKey("animation_speed")
        val audioWaveform = booleanPreferencesKey("audio_waveform")
        val splitTime = booleanPreferencesKey("split_time")
        val heightDp = floatPreferencesKey("height_dp")
        val centerWidthDp = floatPreferencesKey("center_width_dp")
        val compactWidthDp = floatPreferencesKey("compact_width_dp")
        val expandedWidthDp = floatPreferencesKey("expanded_width_dp")
        val offsetXDp = floatPreferencesKey("offset_x_dp")
        val offsetYDp = floatPreferencesKey("offset_y_dp")
        val excluded = stringSetPreferencesKey("excluded_packages")
    }

    val settings: StateFlow<IslandSettings> = context.dataStore.data
        .map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, IslandSettings())

    suspend fun update(transform: (IslandSettings) -> IslandSettings) {
        context.dataStore.edit { p ->
            val s = transform(p.toSettings())
            p[K.media] = s.media
            p[K.calls] = s.calls
            p[K.clockMirror] = s.clockMirror
            p[K.navigation] = s.navigation
            p[K.otherLive] = s.otherLive
            p[K.charging] = s.charging
            p[K.lowBattery] = s.lowBattery
            p[K.ringer] = s.ringer
            p[K.dnd] = s.dnd
            p[K.bluetooth] = s.bluetooth
            p[K.unlock] = s.unlock
            p[K.idleVisible] = s.idleVisible
            p[K.hideFullscreen] = s.hideFullscreen
            p[K.hideLandscape] = s.hideLandscape
            p[K.hideShade] = s.hideShade
            p[K.haptics] = s.haptics
            p[K.mediaPausedTimeoutSec] = s.mediaPausedTimeoutSec
            p[K.animationSpeed] = s.animationSpeed
            p[K.audioWaveform] = s.audioWaveform
            p[K.splitTime] = s.splitTime
            p[K.heightDp] = s.heightDp
            p[K.centerWidthDp] = s.centerWidthDp
            p[K.compactWidthDp] = s.compactWidthDp
            p[K.expandedWidthDp] = s.expandedWidthDp
            p[K.offsetXDp] = s.offsetXDp
            p[K.offsetYDp] = s.offsetYDp
            p[K.excluded] = s.excludedPackages
        }
    }

    private fun Preferences.toSettings(): IslandSettings {
        val d = IslandSettings()
        return IslandSettings(
            media = this[K.media] ?: d.media,
            calls = this[K.calls] ?: d.calls,
            clockMirror = this[K.clockMirror] ?: d.clockMirror,
            navigation = this[K.navigation] ?: d.navigation,
            otherLive = this[K.otherLive] ?: d.otherLive,
            charging = this[K.charging] ?: d.charging,
            lowBattery = this[K.lowBattery] ?: d.lowBattery,
            ringer = this[K.ringer] ?: d.ringer,
            dnd = this[K.dnd] ?: d.dnd,
            bluetooth = this[K.bluetooth] ?: d.bluetooth,
            unlock = this[K.unlock] ?: d.unlock,
            idleVisible = this[K.idleVisible] ?: d.idleVisible,
            hideFullscreen = this[K.hideFullscreen] ?: d.hideFullscreen,
            hideLandscape = this[K.hideLandscape] ?: d.hideLandscape,
            hideShade = this[K.hideShade] ?: d.hideShade,
            haptics = this[K.haptics] ?: d.haptics,
            mediaPausedTimeoutSec = this[K.mediaPausedTimeoutSec] ?: d.mediaPausedTimeoutSec,
            animationSpeed = this[K.animationSpeed] ?: d.animationSpeed,
            audioWaveform = this[K.audioWaveform] ?: d.audioWaveform,
            splitTime = this[K.splitTime] ?: d.splitTime,
            heightDp = this[K.heightDp] ?: d.heightDp,
            centerWidthDp = this[K.centerWidthDp] ?: d.centerWidthDp,
            compactWidthDp = this[K.compactWidthDp] ?: d.compactWidthDp,
            expandedWidthDp = this[K.expandedWidthDp] ?: d.expandedWidthDp,
            offsetXDp = this[K.offsetXDp] ?: d.offsetXDp,
            offsetYDp = this[K.offsetYDp] ?: d.offsetYDp,
            excludedPackages = this[K.excluded] ?: d.excludedPackages,
        )
    }
}
