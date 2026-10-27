package com.damhee.trigger

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** 알람이 울리는 동안 소리·진동을 내는 서비스. 3분 동안 안 끄면 멈추고 '놓친 알람' 알림을 남김 */
class AlarmService : Service() {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private var currentId = -1
    private var timedOut = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getIntExtra("id", -1) ?: -1
        val a = AlarmStore.get(this, id)
        val n = Notif.ringing(this, a, id)
        goForeground(n)
        if (a == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        currentId = id
        timedOut = false
        startRinging()
        try {
            startActivity(Notif.alarmIntent(this, id))
        } catch (_: Exception) {
        }
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            timedOut = true
            stopSelf()
        }, 3 * 60_000L)
        return START_NOT_STICKY
    }

    private fun goForeground(n: android.app.Notification) {
        if (Build.VERSION.SDK_INT < 34) {
            startForeground(Notif.RINGING_ID, n)
            return
        }
        try {
            startForeground(Notif.RINGING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } catch (_: Exception) {
            try {
                startForeground(Notif.RINGING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } catch (_: Exception) {
                getSystemService(NotificationManager::class.java).notify(Notif.RINGING_ID, n)
            }
        }
    }

    private fun startRinging() {
        stopSound()
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@AlarmService, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {
            player = null
        }
        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        try {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0))
        } catch (_: Exception) {
        }
    }

    private fun stopSound() {
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        player?.release()
        player = null
        vibrator?.cancel()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopSound()
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(Notif.RINGING_ID)
        if (timedOut) AlarmStore.get(this, currentId)?.let { Notif.missed(this, it) }
        super.onDestroy()
    }
}
