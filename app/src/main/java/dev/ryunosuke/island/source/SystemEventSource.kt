package dev.ryunosuke.island.source

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import dev.ryunosuke.island.data.IslandSettings
import dev.ryunosuke.island.island.IslandAlert
import kotlinx.coroutines.flow.StateFlow

/**
 * 端末の出来事を一時表示に変える。アクセシビリティサービスが生きている間だけ登録しておく。
 */
class SystemEventSource(
    private val context: Context,
    private val settings: StateFlow<IslandSettings>,
    /** 省電力が入った・切れた */
    private val onBatterySaverChanged: (Boolean) -> Unit = {},
    private val post: (IslandAlert) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)

    private var level = -1
    private var charging = false
    private var ringerMode = audio.ringerMode
    private var dndOn = isDnd()
    private var started = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val s = settings.value
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> onBattery(intent, s)
                Intent.ACTION_POWER_CONNECTED -> {
                    charging = true
                    if (s.charging) post(IslandAlert.Charging(currentLevel()))
                }
                Intent.ACTION_POWER_DISCONNECTED -> charging = false
                AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                    if (isInitialStickyBroadcast) return
                    val mode = intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, audio.ringerMode)
                    if (mode != ringerMode) {
                        ringerMode = mode
                        if (s.ringer) post(IslandAlert.Ringer(mode))
                    }
                }
                NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> {
                    val on = isDnd()
                    if (on != dndOn) {
                        dndOn = on
                        if (s.dnd) post(IslandAlert.Dnd(on))
                    }
                }
                Intent.ACTION_USER_PRESENT -> if (s.unlock && keyguard.isDeviceSecure) post(IslandAlert.Unlock)
                BluetoothDevice.ACTION_ACL_CONNECTED -> if (s.bluetooth) onBluetooth(intent)
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> onBatterySaverChanged(BatterySaver.isOn(context))
            }
        }
    }

    /** 有線のヘッドホン。登録直後に今つながっているものが一度流れてくるので、それは無視する */
    private val audioDevices = object : AudioDeviceCallback() {
        var primed = false
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            if (!primed) {
                primed = true
                return
            }
            if (!settings.value.bluetooth) return
            val wired = added.firstOrNull {
                it.isSink && it.type in setOf(
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                )
            } ?: return
            val name = wired.productName?.toString()?.takeIf { it.isNotBlank() && it != android.os.Build.MODEL } ?: "ヘッドフォン"
            post(IslandAlert.Device(name, null, wired = true))
        }
    }

    fun start() {
        if (started) return
        started = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        audio.registerAudioDeviceCallback(audioDevices, main)
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { context.unregisterReceiver(receiver) }
        audio.unregisterAudioDeviceCallback(audioDevices)
    }

    private fun isDnd() = nm.currentInterruptionFilter.let {
        it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    private fun currentLevel(): Int {
        val bm = context.getSystemService(BatteryManager::class.java)
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 } ?: level.coerceAtLeast(0)
    }

    private fun onBattery(intent: Intent, s: IslandSettings) {
        val raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (raw < 0) return
        val now = raw * 100 / scale
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val before = level
        level = now
        if (before < 0 || charging || !s.lowBattery) return
        // iPhone と同じく 20% と 10% を切ったときに一度だけ
        if ((before > 20 && now <= 20) || (before > 10 && now <= 10)) {
            post(IslandAlert.LowBattery(now))
        }
    }

    private fun onBluetooth(intent: Intent) {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        val major = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull()
        if (major != null && major != BluetoothClass.Device.Major.AUDIO_VIDEO) return
        // 接続直後は電池残量がまだ届いていないことが多いので、少し待ってから出す
        main.postDelayed({
            val name = runCatching { device.alias ?: device.name }.getOrNull() ?: "Bluetooth"
            post(IslandAlert.Device(name, batteryOf(device), wired = false))
        }, 1_200)
    }

    /** 隠し API（BluetoothDevice#getBatteryLevel）。取れなければ null */
    private fun batteryOf(device: BluetoothDevice): Int? = runCatching {
        BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(device) as Int
    }.getOrNull()?.takeIf { it in 0..100 }
}
