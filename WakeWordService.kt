package com.chtai.assistant

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import java.util.Locale

/** User-started microphone foreground service. It remains visible through an ongoing notification. */
class WakeWordService : Service() {
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var stopping = false
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private val mainHandler by lazy { android.os.Handler(mainLooper) }
    private val restartRecognition = Runnable { if (!stopping) startRecognition() }
    private val channelId = "cht_wake_service"
    private val notificationId = 7701

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(notificationId, buildNotification("در حال گوش‌به‌زنگ؛ بگو جارویس"))
        try {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "CHTAI:WakeWord")
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire()
        } catch (_: Exception) {}
        startRecognition()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "گوش‌به‌زنگ JARVIS", NotificationManager.IMPORTANCE_LOW).apply {
                description = "اعلان دائمی برای سرویس تشخیص عبارت بیدارباش"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): android.app.Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(this, 7702, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = Intent(this, WakeWordService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(this, 7703, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("JARVIS فعال است")
            .setContentText(message)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "توقف گوش‌به‌زنگ", stopPending)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(notificationId, buildNotification(message))
    }

    private fun startRecognition() {
        if (listening || !SpeechRecognizer.isRecognitionAvailable(this)) return
        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { listening = true }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { listening = false }
                override fun onError(error: Int) {
                    listening = false
                    recognizer?.cancel()
                    scheduleRecognitionRestart(1600)
                }
                override fun onResults(results: Bundle?) {
                    listening = false
                    val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    val normalized = heard.lowercase(Locale.ROOT).replace("ي", "ی").replace("ك", "ک")
                    val wakeFound = listOf("جارویس", "هی جارویس", "جارویس بیدار شو", "جارویس گوش کن").any { normalized.contains(it) }
                    if (wakeFound) {
                        updateNotification("عبارت جارویس شنیده شد؛ برای گفتن فرمان، اعلان را لمس کن")
                        // Android restricts background activity launches. The user opens the app from this notification.
                    } else {
                        updateNotification("در حال گوش‌به‌زنگ؛ بگو جارویس")
                    }
                    scheduleRecognitionRestart(1000)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            recognizer?.startListening(request)
        } catch (_: Exception) {
            listening = false
            scheduleRecognitionRestart(2000)
        }
    }

    private fun scheduleRecognitionRestart(delayMs: Long) {
        mainHandler.removeCallbacks(restartRecognition)
        if (!stopping) mainHandler.postDelayed(restartRecognition, delayMs)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The foreground service and its ongoing notification remain independent of the Activity task.
        super.onTaskRemoved(rootIntent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopping = true
            mainHandler.removeCallbacks(restartRecognition)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!listening) startRecognition()
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        mainHandler.removeCallbacks(restartRecognition)
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        listening = false
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object { const val ACTION_STOP = "com.chtai.assistant.STOP_WAKE" }
}
