package dev.ryunosuke.island.builtin

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import dev.ryunosuke.island.R

/**
 * 内蔵タイマーが終わったときの音と振動。
 * 画面が点いていれば島が「終了」を出すので、通知（全画面インテント）は画面が消えているときだけ出す。
 */
class TimerRinger(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private val power = context.getSystemService(PowerManager::class.java)
    private val nm = context.getSystemService(NotificationManager::class.java)
    private var ringtone: Ringtone? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** 鳴らしっぱなしにはしない。島の「終了」表示は残る */
    private val autoSilence = Runnable { silence() }

    fun start() {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        ringtone = runCatching {
            RingtoneManager.getRingtone(context, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                isLooping = true
                play()
            }
        }.getOrNull()
        vibrator.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 500, 500), 0),
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
        )
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "island:timer").apply { acquire(SILENCE_AFTER_MS) }
        if (!power.isInteractive) postNotification()
        main.postDelayed(autoSilence, SILENCE_AFTER_MS)
    }

    fun stop() {
        silence()
        nm.cancel(NOTIFICATION_ID)
    }

    private fun silence() {
        main.removeCallbacks(autoSilence)
        runCatching { ringtone?.stop() }
        ringtone = null
        vibrator.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun postNotification() {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "タイマー終了", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
        val full = PendingIntent.getActivity(
            context, 0,
            Intent(context, TimerRingingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, TimerAlarmReceiver::class.java).setAction(TimerAlarmReceiver.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify_timer)
            .setContentTitle("タイマー")
            .setContentText("終了")
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .build()
        nm.notify(NOTIFICATION_ID, n)
    }

    companion object {
        private const val CHANNEL = "timer_done"
        private const val NOTIFICATION_ID = 42
        private const val SILENCE_AFTER_MS = 3 * 60_000L
    }
}
