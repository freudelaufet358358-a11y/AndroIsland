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
import android.os.SystemClock
import android.util.Log
import dev.ryunosuke.island.data.IslandSettings
import dev.ryunosuke.island.island.IslandAlert
import dev.ryunosuke.island.island.PodsBattery
import kotlinx.coroutines.flow.StateFlow

/**
 * 端末の出来事を一時表示に変える。アクセシビリティサービスが生きている間だけ登録しておく。
 */
class SystemEventSource(
    private val context: Context,
    private val settings: StateFlow<IslandSettings>,
    /** 省電力が入った・切れた */
    private val onBatterySaverChanged: (Boolean) -> Unit = {},
    /** 今出している一時表示 */
    private val currentAlert: () -> IslandAlert? = { null },
    /** 出している一時表示を、表示時間はそのままで書き換える（null を返せばそのまま） */
    private val updateAlert: ((IslandAlert) -> IslandAlert?) -> Unit = {},
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

    /**
     * つながったばかりのイヤホン。電池が届くのを待ってから島に出し、出している間は届いた電池で書き換える。
     * 電池の知らせ（ACTION_BATTERY_LEVEL_CHANGED）は、イヤホン自身が HFP などで送ってくるもの（AirPods は 10% 刻み）と、
     * Evolution X の BtHelper が流すもの（AirPods の左右の低い方）の両方が来る。
     * 左右とケースの内訳は、BtHelper がメタデータに書いたものを Shizuku で読む（[PodsMetadata]）
     */
    private class Earbuds(val device: BluetoothDevice, val name: String) {
        val connectedAt = SystemClock.uptimeMillis()

        /** 最後に届いた電池の知らせ */
        var level: Int? = null

        /** 左右とケース。この接続で書かれたと確かめられた（[podsFresh]）あとに読んだものだけ */
        var pods: PodsBattery? = null
        var podsFresh = false

        /** 内訳を読めなかった（Android が変わって呼べない、など）。この接続の間はもう読まない */
        var podsUnavailable = false

        /** Shizuku で内訳を読んでいる途中。その間に届いた知らせは、読み終わってからまとめて読み直す（[again]・[againLevel]） */
        var reading = false
        var again = false
        var againLevel: Int? = null

        /** 島に出した */
        var shown = false
    }

    /** つながったばかりのイヤホン。切れたら null */
    private var earbuds: Earbuds? = null

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
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> if (deviceOf(intent)?.address == earbuds?.device?.address) earbuds = null
                ACTION_BATTERY_LEVEL_CHANGED -> onBatteryLevel(intent)
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
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(ACTION_BATTERY_LEVEL_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        audio.registerAudioDeviceCallback(audioDevices, main)
    }

    fun stop() {
        if (!started) return
        started = false
        earbuds = null
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

    private fun deviceOf(intent: Intent) = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)

    private fun onBluetooth(intent: Intent) {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        val device = deviceOf(intent) ?: return
        val major = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull()
        if (major != null && major != BluetoothClass.Device.Major.AUDIO_VIDEO) return
        // 同じイヤホンが BR/EDR と LE で続けてつながったときは 1 回だけ出す
        val prev = earbuds
        if (prev != null && prev.device.address == device.address && SystemClock.uptimeMillis() - prev.connectedAt < DUPLICATE_MS) return
        val name = runCatching { device.alias ?: device.name }.getOrNull() ?: "Bluetooth"
        val e = Earbuds(device, name)
        earbuds = e
        // 接続直後は電池残量がまだ届いていないことが多いので、少し待ってから出す。
        // それでも分からなければ届くのを待ち、来なければ電池なしで出す（あとから届いたら、出している間は書き換える）
        main.postDelayed({ refresh(e) }, SHOW_DELAY_MS)
        main.postDelayed({ if (earbuds === e && !e.shown) show(e) }, BATTERY_WAIT_MS)
    }

    private fun onBatteryLevel(intent: Intent) {
        val e = earbuds ?: return
        if (deviceOf(intent)?.address != e.device.address) return
        val level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1).takeIf { it in 0..100 } ?: return
        e.level = level
        // 出す前と、出している間だけ読み直す（BtHelper はつながっている間ずっと知らせてくる）
        if (!e.shown || (currentAlert() as? IslandAlert.Device)?.address == e.device.address) refresh(e, level)
    }

    /**
     * 電池を読み直し、まだ出していなければ（分かっていれば）出し、出していれば書き換える。
     * 左右とケースの内訳は Shizuku で読む。[broadcast] は今届いた電池の知らせで、
     * 読んだ内訳がこの接続で書かれたものか（前の接続の古い値ではないか）をこれと比べて確かめる
     */
    private fun refresh(e: Earbuds, broadcast: Int? = null) {
        if (earbuds !== e) return
        if (e.podsUnavailable || !ShizukuShell.isReady()) {
            publish(e)
            return
        }
        // 知らせは続けて届くことがあるので、読んでいる間に届いたものは読み終わってから 1 回だけ読み直す
        if (e.reading) {
            e.again = true
            if (broadcast != null) e.againLevel = broadcast
            return
        }
        e.reading = true
        ShizukuShell.handler.post {
            val read = runCatching { PodsMetadata.parse(ShizukuShell.bluetoothMetadata(e.device, PodsMetadata.KEYS)) }
                .onFailure { Log.w(TAG, "イヤホンの電池の内訳を読めない", it) }
            main.post {
                e.reading = false
                if (read.isFailure) e.podsUnavailable = true
                val pods = read.getOrNull()
                if (pods != null) {
                    if (broadcast != null && PodsMetadata.matches(pods, broadcast)) e.podsFresh = true
                    if (e.podsFresh) e.pods = pods
                }
                publish(e)
                if (e.again) {
                    val next = e.againLevel
                    e.again = false
                    e.againLevel = null
                    refresh(e, next)
                }
            }
        }
    }

    private fun publish(e: Earbuds) {
        if (earbuds !== e) return
        if (!e.shown) {
            if (batteryOf(e) != null && SystemClock.uptimeMillis() - e.connectedAt >= SHOW_DELAY_MS) show(e)
            return
        }
        val battery = batteryOf(e)
        updateAlert { a ->
            (a as? IslandAlert.Device)?.takeIf { it.address == e.device.address }?.copy(battery = battery, pods = e.pods)
        }
    }

    private fun show(e: Earbuds) {
        e.shown = true
        if (!settings.value.bluetooth) return
        post(IslandAlert.Device(e.name, batteryOf(e), wired = false, address = e.device.address, pods = e.pods))
    }

    /** 全体の残量。内訳を読めていればその左右の低い方、無ければ最後の知らせ、それも無ければ Bluetooth に聞く */
    private fun batteryOf(e: Earbuds): Int? = e.pods?.headset ?: e.level ?: batteryLevel(e.device)

    /** BluetoothDevice#getBatteryLevel（@SystemApi）。取れなければ null */
    private fun batteryLevel(device: BluetoothDevice): Int? = runCatching {
        BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(device) as Int
    }.getOrNull()?.takeIf { it in 0..100 }

    companion object {
        private const val TAG = "IslandEvents"

        /** BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED・EXTRA_BATTERY_LEVEL（@SystemApi なので文字列で持つ） */
        private const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        private const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"

        /** つないでから島に出すまで */
        private const val SHOW_DELAY_MS = 1_200L

        /**
         * 電池が分からないとき、届くのを待つ長さ（つないでから）。BtHelper は AirPods の BLE の広告か AACP の知らせを
         * 受けてから流すので、接続より少し遅れる
         */
        private const val BATTERY_WAIT_MS = 3_000L

        /** 同じイヤホンの 2 回目の接続（別の方式で続けてつながった）を無視する間 */
        private const val DUPLICATE_MS = 10_000L
    }
}
