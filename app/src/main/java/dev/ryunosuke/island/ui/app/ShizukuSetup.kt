package dev.ryunosuke.island.ui.app

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import dev.ryunosuke.island.overlay.IslandOverlayService
import dev.ryunosuke.island.source.IslandNotificationListener
import dev.ryunosuke.island.source.ShizukuShell

/**
 * セットアップ画面の「まとめて許可」。足りない許可を、adb で打つのと同じコマンドを Shizuku 側で動かして付ける。
 * どれも端末の設定として残るので、あとで再起動して Shizuku が止まっても消えない。
 */
object ShizukuSetup {
    private val main = Handler(Looper.getMainLooper())

    /** Shizuku の使用をまだ許可していなければ先に求め、許可されたら足りないものを付けて結果を知らせる */
    fun grant(context: Context, microphone: Boolean) {
        val app = context.applicationContext
        val work: () -> Unit = {
            val failed = grantMissing(app, microphone)
            main.post {
                val message = if (failed.isEmpty()) "許可しました" else "付けられなかったもの: ${failed.joinToString("、")}"
                Toast.makeText(app, message, Toast.LENGTH_LONG).show()
            }
        }
        when (ShizukuShell.status(app)) {
            ShizukuShell.Status.Ready -> ShizukuShell.handler.post(work)
            ShizukuShell.Status.NoPermission -> ShizukuShell.requestPermission(context, work)
            else -> ShizukuShell.openManager(context)
        }
    }

    /** 足りない許可を付け、付けられなかったものの名前を返す。Shizuku のスレッドで呼ぶ */
    private fun grantMissing(context: Context, microphone: Boolean): List<String> {
        val failed = mutableListOf<String>()
        fun attempt(name: String, block: () -> Unit) {
            runCatching { block() }.onFailure {
                Log.w(TAG, "$name を付けられない", it)
                failed += name
            }
        }
        val permissions = buildList {
            add(Manifest.permission.WRITE_SECURE_SETTINGS to "省電力の切り替え")
            add(Manifest.permission.BLUETOOTH_CONNECT to "Bluetooth")
            add(Manifest.permission.POST_NOTIFICATIONS to "通知")
            if (microphone) add(Manifest.permission.RECORD_AUDIO to "マイク")
        }
        for ((permission, name) in permissions) {
            if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) continue
            attempt(name) { ShizukuShell.exec("pm", "grant", context.packageName, permission) }
        }
        val listener = ComponentName(context, IslandNotificationListener::class.java)
        if (!context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(listener)) {
            attempt("通知へのアクセス") {
                ShizukuShell.exec("cmd", "notification", "allow_listener", listener.flattenToString())
            }
        }
        if (!isAccessibilityEnabled(context)) attempt("アクセシビリティ") { enableAccessibility(context) }
        return failed
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        val me = ComponentName(context, IslandOverlayService::class.java).flattenToString()
        return enabledAccessibilityServices(context).any { it.equals(me, ignoreCase = true) }
    }

    /**
     * 設定画面のスイッチと同じ Settings.Secure の値を書く（adb の `settings put secure …` と同じ）。
     * 先に、サイドロードしたアプリの「制限付き設定」を、アプリ情報の ⋮ から許可したのと同じ状態にしておく
     * （あとで設定画面から切ったり入れ直したりできるように）
     */
    private fun enableAccessibility(context: Context) {
        runCatching { ShizukuShell.exec("appops", "set", context.packageName, "ACCESS_RESTRICTED_SETTINGS", "allow") }
        val me = ComponentName(context, IslandOverlayService::class.java).flattenToString()
        val next = (enabledAccessibilityServices(context) + me).joinToString(":")
        ShizukuShell.exec("settings", "put", "secure", Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next)
        ShizukuShell.exec("settings", "put", "secure", Settings.Secure.ACCESSIBILITY_ENABLED, "1")
    }

    private fun enabledAccessibilityServices(context: Context): List<String> =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').filter { it.isNotBlank() }

    private const val TAG = "IslandSetup"
}
