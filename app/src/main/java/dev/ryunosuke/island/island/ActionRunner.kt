package dev.ryunosuke.island.island

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dev.ryunosuke.island.Graph
import dev.ryunosuke.island.island.ActionTarget.NotificationIntent.Which
import dev.ryunosuke.island.source.BatterySaver

/** 島のボタンやタップを、実際の PendingIntent・メディア操作・内蔵時計の操作に変える */
class ActionRunner(private val context: Context, private val g: Graph) {

    fun run(target: ActionTarget) {
        when (target) {
            is ActionTarget.NotificationAction ->
                g.notifications.get(target.notifKey)?.notification?.actions
                    ?.getOrNull(target.index)?.actionIntent?.let(::send)
            is ActionTarget.NotificationIntent -> {
                val sbn = g.notifications.get(target.notifKey) ?: return
                val n = sbn.notification
                val pi = when (target.which) {
                    Which.Content -> n.contentIntent
                    Which.Answer -> n.extras.getParcelable(Notification.EXTRA_ANSWER_INTENT, PendingIntent::class.java)
                    Which.Decline -> n.extras.getParcelable(Notification.EXTRA_DECLINE_INTENT, PendingIntent::class.java)
                    Which.HangUp -> n.extras.getParcelable(Notification.EXTRA_HANG_UP_INTENT, PendingIntent::class.java)
                }
                if (pi != null) send(pi) else if (target.which == Which.Content) launch(sbn.packageName)
            }
            is ActionTarget.Media ->
                if (DemoController.isDemo(target.sessionKey)) g.demo.media(target.command)
                else g.media.command(target.sessionKey, target.command)
            is ActionTarget.MediaSeek ->
                if (DemoController.isDemo(target.sessionKey)) g.demo.seek(target.positionMs)
                else g.media.seek(target.sessionKey, target.positionMs)
            is ActionTarget.MediaOpen -> {
                if (DemoController.isDemo(target.sessionKey)) return launch(context.packageName)
                val c = g.media.controller(target.sessionKey) ?: return
                c.sessionActivity?.let(::send) ?: launch(c.packageName)
            }
            is ActionTarget.Builtin -> g.clock.handle(target.command)
            is ActionTarget.Launch -> launch(target.packageName)
            is ActionTarget.Demo -> g.demo.handle(target.command)
            ActionTarget.BatterySaverToggle -> BatterySaver.toggle(context)
        }
    }

    /**
     * 裏から他アプリの画面を開くことになるので、送る側としてバックグラウンド起動を許可しておく
     * （Android 14 以降は送信側の明示が要る）。アクセシビリティサービスを持つので許可は通る。
     */
    private fun send(pi: PendingIntent) {
        val mode = if (Build.VERSION.SDK_INT >= 36) {
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
        } else {
            @Suppress("DEPRECATION")
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        }
        val opts = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(mode).toBundle()
        try {
            pi.send(context, 0, null, null, null, null, opts)
        } catch (e: PendingIntent.CanceledException) {
            Log.w(TAG, "PendingIntent はもう無効", e)
        }
    }

    private fun launch(pkg: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { context.startActivity(intent) }.onFailure { Log.w(TAG, "$pkg を開けない", it) }
    }

    companion object {
        private const val TAG = "IslandAction"
    }
}
