package dev.ryunosuke.island.source

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit

/**
 * 省電力（バッテリー セーバー）を島から入れたり切ったりする。
 *
 * 普通のアプリは PowerManager.setPowerSaveModeEnabled を呼べない（POWER_SAVER / DEVICE_POWER は adb でも許可できない）。
 * かといって設定の Global.low_power を直接 1 にすると、システムの BatterySaverStateMachine の状態が
 * 「オフ」のまま省電力だけが入り、設定画面やクイック設定から切ろうとしても「もうオフ」として無視される
 * （"Tried to disable BS when it's already OFF"。充電しても切れない）。
 *
 * そこで、システム自身の「電池残量が○%になったら自動でオン」（スケジュール）を今の残量 + 1% に一時的に合わせ、
 * システムに「自動でオン」の状態で入れてもらう。この状態なら設定画面・クイック設定のスイッチでも、充電でも普通に切れる。
 * 切れたら（島・設定画面・充電のどれでも）ユーザーの元のスケジュールに戻す。書き換えには adb で一度許可してもらう
 * WRITE_SECURE_SETTINGS が要る。無ければ設定の「バッテリー セーバー」画面を開く。
 *
 * Shizuku が使えるときは、shell の権限（DEVICE_POWER）で `cmd power set-mode` を動かして直接入れ・切りする
 * （設定画面のスイッチと同じ PowerManager.setPowerSaveModeEnabled）。スケジュールに触らず、手動で入れた省電力も切れる。
 */
object BatterySaver {
    const val GRANT_COMMAND = "adb shell pm grant dev.ryunosuke.island android.permission.WRITE_SECURE_SETTINGS"

    /** Settings.Global の隠し定数 */
    private const val TRIGGER_LEVEL = "low_power_trigger_level"
    private const val AUTO_MODE = "automatic_power_save_mode"
    /** PowerManager.POWER_SAVE_MODE_TRIGGER_PERCENTAGE */
    private const val MODE_PERCENTAGE = "0"

    private const val PREFS = "battery_saver"
    private const val KEY_APPLIED = "applied"
    private const val KEY_LEVEL = "orig_trigger_level"
    private const val KEY_MODE = "orig_auto_mode"

    /** 島から入れた直後にシステムが出す「バッテリー セーバーが ON になっています」の通知を消す猶予 */
    private const val NOTICE_WINDOW_MS = 5_000L
    /** 入れたのに入らなかった（スヌーズ中・充電中など）と判断するまで */
    private const val CONFIRM_MS = 1_500L

    private val main = Handler(Looper.getMainLooper())
    private var turnedOnAt = 0L

    fun canToggle(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun isOn(context: Context) = context.getSystemService(PowerManager::class.java).isPowerSaveMode

    /** 島のボタン: オフならオン、オンならオフ */
    fun toggle(context: Context) {
        if (isOn(context)) turnOff(context) else turnOn(context)
    }

    private fun turnOn(context: Context) {
        if (ShizukuShell.isReady()) return setDirectly(context, true)
        turnOnBySchedule(context)
    }

    private fun turnOnBySchedule(context: Context) {
        if (!canToggle(context)) return openSettings(context)
        val cr = context.contentResolver
        val prefs = prefs(context)
        // 元のスケジュールを覚えておく（前回戻しそびれていたら、その時の値を残す）。
        // 書き換えた直後にプロセスが落ちても戻せるように、端末の設定を変える前に同期で保存する
        if (!prefs.getBoolean(KEY_APPLIED, false)) {
            prefs.edit(commit = true) {
                putBoolean(KEY_APPLIED, true)
                putString(KEY_LEVEL, Settings.Global.getString(cr, TRIGGER_LEVEL))
                putString(KEY_MODE, Settings.Global.getString(cr, AUTO_MODE))
            }
        }
        val level = batteryLevel(context).coerceIn(0, 99)
        // 「残量が閾値以下」は、閾値を変えた瞬間は「残量 < 閾値」で見直されるので + 1 にする
        val trigger = maxOf(level + 1, Settings.Global.getInt(cr, TRIGGER_LEVEL, 0)).coerceAtMost(100)
        val ok = runCatching {
            Settings.Global.putString(cr, AUTO_MODE, MODE_PERCENTAGE)
            Settings.Global.putInt(cr, TRIGGER_LEVEL, trigger)
        }.onFailure { Log.w(TAG, "スケジュールを書けない", it) }.getOrDefault(false)
        if (!ok) {
            restore(context)
            return openSettings(context)
        }
        turnedOnAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "スケジュールを ${trigger}% にしてオン")
        // 自分のスケジュールで入った省電力をスヌーズしていた・充電中などで入らなかったときは、元に戻して設定画面へ
        main.postDelayed({
            if (!isOn(context)) {
                Log.i(TAG, "オンにならなかったので元に戻す")
                restore(context)
                openSettings(context)
            }
        }, CONFIRM_MS)
    }

    private fun turnOff(context: Context) {
        if (prefs(context).getBoolean(KEY_APPLIED, false) && canToggle(context)) {
            // 島からスケジュールで入れたもの: スケジュールを戻せば、システムが「自動でオン」をやめて切る
            restore(context)
        } else if (ShizukuShell.isReady()) {
            // ユーザーが自分で（手動で）入れたものも、shell の権限なら切れる
            setDirectly(context, false)
        } else {
            // 手動で入れたものは、普通のアプリからは切れない
            openSettings(context)
        }
    }

    /**
     * Shizuku で `cmd power set-mode 1|0` を動かす（adb shell で打つのと同じ）。設定画面のスイッチで入れたのと同じ
     * 「手動」の状態になるので、設定画面・クイック設定・充電でも普通に切れる。
     * 充電中はシステムが入れないので、入らなければ設定画面を開く。Shizuku が途中で止まっていたら今までのやり方に戻す
     */
    private fun setDirectly(context: Context, on: Boolean) {
        val app = context.applicationContext
        if (on) turnedOnAt = SystemClock.elapsedRealtime()
        ShizukuShell.handler.post {
            val ok = runCatching { ShizukuShell.exec("cmd", "power", "set-mode", if (on) "1" else "0") }
                .onFailure { Log.w(TAG, "Shizuku で切り替えられない", it) }
                .isSuccess
            main.post {
                if (!ok) {
                    turnedOnAt = 0L
                    if (on) turnOnBySchedule(app) else openSettings(app)
                    return@post
                }
                Log.i(TAG, "Shizuku で${if (on) "オン" else "オフ"}にした")
                main.postDelayed({
                    if (isOn(app) != on) {
                        Log.i(TAG, "${if (on) "オン" else "オフ"}にならなかったので設定画面を開く")
                        openSettings(app)
                    }
                }, CONFIRM_MS)
            }
        }
    }

    /**
     * 省電力が切り替わった・島のサービスが起動したときに呼ぶ。島から入れた省電力が
     * （設定画面・クイック設定・充電などで）切れていたら、ユーザーの元のスケジュールに戻す
     */
    fun onModeChanged(context: Context) {
        if (!isOn(context) && prefs(context).getBoolean(KEY_APPLIED, false)) restore(context)
    }

    /** 島から入れた直後か（システムが出す「ON になっています」の通知を消してよいか） */
    fun justTurnedOnByIsland() = SystemClock.elapsedRealtime() - turnedOnAt < NOTICE_WINDOW_MS

    private fun restore(context: Context) {
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_APPLIED, false)) return
        val cr = context.contentResolver
        runCatching {
            // 元が未設定（null）なら null に戻す。システムは null を既定値（スケジュールなし）として読む
            Settings.Global.putString(cr, TRIGGER_LEVEL, prefs.getString(KEY_LEVEL, null))
            Settings.Global.putString(cr, AUTO_MODE, prefs.getString(KEY_MODE, null))
        }.onFailure { Log.w(TAG, "スケジュールを戻せない", it) }
        prefs.edit(commit = true) { clear() }
        turnedOnAt = 0L
        Log.i(TAG, "スケジュールを元に戻した")
    }

    /** システムが「残量低下」を判定するのと同じ値（ACTION_BATTERY_CHANGED の残量） */
    private fun batteryLevel(context: Context): Int {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.coerceAtLeast(1) ?: 100
        return if (level >= 0) level * 100 / scale
        else context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun openSettings(context: Context) {
        val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure { Log.w(TAG, "設定を開けない", it) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val TAG = "IslandBatterySaver"
}
