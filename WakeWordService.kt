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
import kotlin.math.sqrt

/** Local-only wake listener. Uses short overlapping windows and voice-activity gating. */
class WakeWordService : Service() {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private val channelId = "cht_wake_service_v2"
    private val notificationId = 7701
    private val wakeNotificationId = 7704

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
            val channel = NotificationChannel(channelId, "گوش‌به‌زنگ JARVIS", NotificationManager.IMPORTANCE_HIGH)
                .apply { lockscreenVisibility = Notification.VISIBILITY_PUBLIC }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String, wakeDetected: Boolean = false): Notification {
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            if (wakeDetected) putExtra(MainActivity.EXTRA_WAKE_DETECTED, true)
        }
        val content = PendingIntent.getActivity(this, 7702, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = Intent(this, WakeWordService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(this, 7703, stop,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (wakeDetected) "JARVIS صدایت را شنید" else "JARVIS فعال است")
            .setContentText(message)
            .setContentIntent(content)
            .setOngoing(!wakeDetected)
            .setAutoCancel(wakeDetected)
            .setOnlyAlertOnce(!wakeDetected)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(if (wakeDetected) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_LOW)
            // On supported lock screens this brings the command screen forward.
            .setFullScreenIntent(content, wakeDetected)
            .addAction(android.R.drawable.ic_media_pause, "توقف گوش‌به‌زنگ", stopPending)
            .build()
    }

    private fun updateNotification(message: String, wakeDetected: Boolean = false) {
        val manager = getSystemService(NotificationManager::class.java)
        if (wakeDetected) {
            // Post a separate alert so Android treats the full-screen wake intent as a new alert.
            manager.notify(wakeNotificationId, buildNotification(message, true))
        } else {
            manager.notify(notificationId, buildNotification(message))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            running.set(false)
            stopSelf()
            return START_NOT_STICKY
        }
        getSystemService(NotificationManager::class.java).cancel(wakeNotificationId)
        if (running.compareAndSet(false, true)) {
            worker = Thread { localWakeLoop() }.also {
                it.name = "Jarvis-Local-Wake"
                it.start()
            }
        }
        return START_STICKY
    }

    private fun localWakeLoop() {
        val model = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(filesDir, "models"), "ggml-base.bin")
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
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT)
            check(min > 0) { "میکروفون ۱۶ کیلوهرتز در دسترس نیست." }
            recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, 12800))
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "میکروفون آماده نشد." }
            recorder.startRecording()
            updateNotification("گوش‌به‌زنگ سریع آفلاین فعال است؛ بگو جارویس")

            // Keep a rolling 2-second window, refreshed every 0.5 seconds.
            // Whisper is not invoked during silence, avoiding repeated expensive empty decodes.
            val windowSize = rate * 2
            val ring = ShortArray(windowSize)
            val readBuffer = ShortArray(3200) // 200 ms
            var writeAt = 0
            var samplesSeen = 0
            var nextCheckAt = windowSize
            while (running.get()) {
                val n = recorder.read(readBuffer, 0, readBuffer.size)
                if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE ||
                    n == AudioRecord.ERROR_DEAD_OBJECT) {
                    throw IllegalStateException("ارتباط میکروفون قطع شد.")
                }
                if (n <= 0) continue
                for (i in 0 until n) {
                    ring[writeAt] = readBuffer[i]
                    writeAt = (writeAt + 1) % windowSize
                }
                samplesSeen += n
                if (samplesSeen < nextCheckAt) continue
                nextCheckAt = samplesSeen + rate / 2

                // RMS voice gate. Ignore silence and low-level microphone noise.
                var energy = 0.0
                for (sample in ring) {
                    val v = sample.toDouble()
                    energy += v * v
                }
                val rms = sqrt(energy / windowSize) / 32768.0
                if (rms < 0.008) continue

                val audio = FloatArray(windowSize)
                for (i in 0 until windowSize) {
                    audio[i] = ring[(writeAt + i) % windowSize].toFloat() / 32768f
                }
                val heard = engine.transcribe(audio).lowercase()
                    .replace("ي", "ی").replace("ك", "ک")
                    .replace(" ", "").replace("‌", "")
                if (heard.contains("جارویس") || heard.contains("جارويس") ||
                    heard.contains("jarvis")) {
                    // Trigger a high-priority full-screen notification as well as a broadcast.
                    // The broadcast handles the foreground case; the notification handles lock-screen wake.
                    running.set(false)
                    updateNotification("جارویس بیدار شد؛ فرمانت را بگو", wakeDetected = true)
                    sendBroadcast(Intent(ACTION_WAKE_DETECTED).setPackage(packageName))
                    break
                }
            }
        } catch (e: Exception) {
            updateNotification("گوش‌به‌زنگ متوقف شد: ${e.message ?: "خطای میکروفون"}")
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
