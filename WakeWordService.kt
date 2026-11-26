package com.chtai.assistant

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Local-only wake listener. No Android/Google SpeechRecognizer is used. */
class WakeWordService : Service() {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private val channelId = "cht_wake_service_v2"
    private val notificationId = 7701

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(notificationId, buildNotification("در حال آماده‌سازی تشخیص آفلاین فارسی"))
        try {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "CHTAI:WakeWord")
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire()
        } catch (_: Exception) {}
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "گوش‌به‌زنگ JARVIS", NotificationManager.IMPORTANCE_HIGH).apply { lockscreenVisibility = Notification.VISIBILITY_PUBLIC }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String, wakeDetected: Boolean = false): Notification {
        val open = Intent(this, MainActivity::class.java).apply {\n            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK\n            if (wakeDetected) putExtra(MainActivity.EXTRA_WAKE_DETECTED, true)
        }
        val content = PendingIntent.getActivity(this, 7702, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = Intent(this, WakeWordService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(this, 7703, stop, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("JARVIS فعال است")
            .setContentText(message)
            .setContentIntent(content)
            .setOngoing(true)
            .setOnlyAlertOnce(!wakeDetected)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(if (wakeDetected) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_LOW)
            .setFullScreenIntent(content, wakeDetected)
            .addAction(android.R.drawable.ic_media_pause, "توقف گوش‌به‌زنگ", stopPending)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(notificationId, buildNotification(message))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running.compareAndSet(false, true)) {
            worker = Thread { localWakeLoop() }.also { it.name = "Jarvis-Local-Wake"; it.start() }
        }
        return START_STICKY
    }

    private fun localWakeLoop() {
        val model = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(filesDir, "models"), "ggml-base.bin")
        if (!model.isFile || model.length() < 50_000_000L) {
            updateNotification("برای گوش‌به‌زنگ آفلاین، ابتدا مدل Whisper را در JARVIS دانلود کن")
            running.set(false)
            stopSelf()
            return
        }
        val engine = WhisperEngine()
        var recorder: AudioRecord? = null
        try {
            engine.load(model.absolutePath)
            val rate = 16000
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(min > 0)
            recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 12800))
            check(recorder.state == AudioRecord.STATE_INITIALIZED)
            recorder.startRecording()
            updateNotification("گوش‌به‌زنگ آفلاین فعال است؛ بگو جارویس")
            val chunk = ShortArray(rate * 2)
            while (running.get()) {
                var offset = 0
                while (offset < chunk.size && running.get()) {
                    val n = recorder.read(chunk, offset, chunk.size - offset)
                    if (n > 0) offset += n
                }
                if (!running.get()) break
                val samples = FloatArray(offset) { chunk[it].toFloat() / 32768f }
                val heard = engine.transcribe(samples).lowercase()
                    .replace("ي", "ی").replace("ك", "ک")
                if (listOf("جارویس", "هی جارویس", "جارویس بیدار شو", "جارویس گوش کن").any { heard.contains(it) }) {
                    // Release the microphone before the Activity starts command recognition.
                    // The foreground service remains alive; MainActivity restarts the listener
                    // after JARVIS has finished speaking the command response.
                    running.set(false)
                    updateNotification("جارویس بیدار شد؛ منتظر فرمان صوتی")
                    sendBroadcast(Intent(ACTION_WAKE_DETECTED).setPackage(packageName))
                    break
                }
            }
        } catch (e: Exception) {
            updateNotification("گوش‌به‌زنگ محلی متوقف شد: ${e.message ?: "خطا"}")
            running.set(false)
        } finally {
            try { recorder?.stop() } catch (_: Exception) {}
            try { recorder?.release() } catch (_: Exception) {}
            try { engine.unload() } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        running.set(false)
        try { worker?.interrupt() } catch (_: Exception) {}
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val ACTION_STOP = "com.chtai.assistant.STOP_WAKE"
        const val ACTION_WAKE_DETECTED = "com.chtai.assistant.WAKE_DETECTED"
    }
}
