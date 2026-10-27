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
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 알람이 울리는 동안: 알람음 + 진동, 중간중간 "내일은 물건 챙기는 날이에요" 를 소리 내 읽어줌.
 * 3분 동안 안 끄면 멈추고 '놓친 알람' 알림을 남김.
 */
class AlarmService : Service() {
    companion object {
        const val ACTION_SPEAK = "com.damhee.trigger.SPEAK"
    }

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private var currentId = -1
    private var timedOut = false

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speakText = ""
    private var spoken = 0

    private val alarmAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SPEAK) {
            // 알람 화면의 "다시 듣기"
            if (currentId >= 0) speakNow() else stopSelf()
            return START_NOT_STICKY
        }
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
        spoken = 0
        speakText = Fmt.sentence(a) + "." + if (a.memo.isNotBlank()) " 메모. ${a.memo}." else ""
        startRinging()
        initTts()
        try {
            startActivity(Notif.alarmIntent(this, id))
        } catch (_: Exception) {
        }
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            timedOut = true
            stopSelf()
        }, 3 * 60_000L)
        if (ttsReady) handler.postDelayed({ speakCycle() }, 2500)
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

    // ---------- 읽어주기 ----------
    private fun initTts() {
        if (tts != null) return
        tts = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            val t = tts ?: return@TextToSpeech
            val r = t.setLanguage(Locale.KOREAN)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) return@TextToSpeech
            t.setAudioAttributes(alarmAttrs)
            t.setSpeechRate(0.9f)
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    handler.post { resumeSound() }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    handler.post { resumeSound() }
                }
            })
            ttsReady = true
            handler.postDelayed({ speakCycle() }, 2500)
        }
    }

    /** 알람음 몇 초 → 읽어주기 → 알람음 … (최대 6번) */
    private fun speakCycle() {
        if (currentId < 0 || spoken >= 6) return
        spoken++
        speakNow()
        handler.postDelayed({ speakCycle() }, 14_000L)
    }

    private fun speakNow() {
        val t = tts ?: return
        if (!ttsReady) return
        try {
            player?.pause()
        } catch (_: Exception) {
        }
        t.speak(speakText, TextToSpeech.QUEUE_FLUSH, null, "u${System.currentTimeMillis()}")
    }

    private fun resumeSound() {
        if (currentId < 0) return
        try {
            player?.start()
        } catch (_: Exception) {
        }
    }

    // ---------- 알람음·진동 ----------
    private fun startRinging() {
        stopSound()
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(alarmAttrs)
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
        val id = currentId
        currentId = -1
        handler.removeCallbacksAndMessages(null)
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        stopSound()
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(Notif.RINGING_ID)
        if (timedOut) AlarmStore.get(this, id)?.let { Notif.missed(this, it) }
        super.onDestroy()
    }
}
