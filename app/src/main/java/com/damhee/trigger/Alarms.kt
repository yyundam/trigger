package com.damhee.trigger

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build

private const val PI_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

object AlarmScheduler {
    private fun firePi(ctx: Context, id: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, id,
            Intent(ctx, AlarmReceiver::class.java).putExtra("id", id),
            PI_FLAGS
        )

    fun schedule(ctx: Context, a: Alarm) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val fire = firePi(ctx, a.id)
        val show = PendingIntent.getActivity(
            ctx, a.id, Intent(ctx, MainActivity::class.java), PI_FLAGS
        )
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, a.time, fire)
        } else {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(a.time, show), fire)
        }
    }

    fun cancel(ctx: Context, id: Int) {
        ctx.getSystemService(AlarmManager::class.java).cancel(firePi(ctx, id))
    }

    /** 재부팅·시간 변경 후 다시 등록. 폰이 꺼져 있는 동안 놓친 알람(12시간 이내)은 곧바로 울림 */
    fun rescheduleAll(ctx: Context) {
        AlarmStore.prune(ctx)
        val now = System.currentTimeMillis()
        for (a in AlarmStore.load(ctx)) {
            when {
                a.time > now -> schedule(ctx, a)
                a.time > now - 12 * 3600_000L -> schedule(ctx, a.copy(time = now + 10_000L))
            }
        }
    }
}

object Actions {
    fun done(ctx: Context, id: Int) {
        AlarmScheduler.cancel(ctx, id)
        AlarmStore.remove(ctx, id)
        stopRinging(ctx, id)
    }

    fun snooze(ctx: Context, id: Int, minutes: Int) {
        val a = AlarmStore.get(ctx, id)
        if (a != null) {
            val n = a.copy(time = System.currentTimeMillis() + minutes * 60_000L)
            AlarmStore.put(ctx, n)
            AlarmScheduler.schedule(ctx, n)
        }
        stopRinging(ctx, id)
    }

    private fun stopRinging(ctx: Context, id: Int) {
        ctx.stopService(Intent(ctx, AlarmService::class.java))
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.cancel(Notif.RINGING_ID)
        nm.cancel(Notif.missedId(id))
    }
}

object Notif {
    const val CH = "trigger_alarm"
    const val CH_MISSED = "trigger_missed"
    const val RINGING_ID = 4242
    fun missedId(id: Int) = 100_000 + id

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "알람", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)          // 소리는 서비스가 알람 음량으로 직접 재생
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_MISSED, "놓친 알람", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun alarmIntent(ctx: Context, id: Int): Intent =
        Intent(ctx, AlarmActivity::class.java)
            .putExtra("id", id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)

    private fun actionPi(ctx: Context, action: String, id: Int, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, code,
            Intent(ctx, ActionReceiver::class.java).setAction(action).putExtra("id", id),
            PI_FLAGS
        )

    private fun title(a: Alarm?): String {
        if (a == null) return "트리거"
        val c = Cats.of(a.cat)
        return "${c.emoji} ${c.label}"
    }

    private fun body(a: Alarm?): String =
        if (a == null || a.memo.isBlank()) "무엇이었을까요?" else a.memo

    fun ringing(ctx: Context, a: Alarm?, id: Int): Notification {
        ensureChannels(ctx)
        val full = PendingIntent.getActivity(ctx, id, alarmIntent(ctx, id), PI_FLAGS)
        return Notification.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title(a))
            .setContentText(body(a))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .addAction(
                Notification.Action.Builder(
                    null as Icon?, "끄기", actionPi(ctx, ActionReceiver.DONE, id, 200_000 + id)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    null as Icon?, "10분 뒤", actionPi(ctx, ActionReceiver.SNOOZE, id, 300_000 + id)
                ).build()
            )
            .build()
    }

    fun missed(ctx: Context, a: Alarm) {
        ensureChannels(ctx)
        val pi = PendingIntent.getActivity(ctx, a.id, alarmIntent(ctx, a.id), PI_FLAGS)
        val n = Notification.Builder(ctx, CH_MISSED)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("놓친 알람 · ${title(a)}")
            .setContentText(body(a))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(missedId(a.id), n)
        } catch (_: SecurityException) {
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        if (AlarmStore.get(ctx, id) == null) return
        ctx.startForegroundService(Intent(ctx, AlarmService::class.java).putExtra("id", id))
    }
}

class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val DONE = "com.damhee.trigger.DONE"
        const val SNOOZE = "com.damhee.trigger.SNOOZE"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        when (intent.action) {
            DONE -> Actions.done(ctx, id)
            SNOOZE -> Actions.snooze(ctx, id, 10)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        AlarmScheduler.rescheduleAll(ctx)
    }
}
