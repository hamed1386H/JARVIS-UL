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
    private val answerNotificationId = 7705

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

            val readBuffer = ShortArray(3200)
            var waitingForCommandUntil = 0L
            updateNotification("گوش‌به‌زنگ فعال است؛ بگو " + configuredWakePhrase())

            while (running.get()) {
                val utterance = captureUtterance(recorder, rate, readBuffer) ?: continue
                val heard = normalizeSpeech(engine.transcribe(utterance))
                if (heard.isBlank()) continue

                val wakePhrase = configuredWakePhrase()
                val wakeIndex = heard.indexOf(wakePhrase)
                val hasWake = wakeIndex >= 0 || heard.contains("جارویس") || heard.contains("jarvis")
                val command = if (hasWake) {
                    when {
                        wakeIndex >= 0 -> heard.substring(wakeIndex + wakePhrase.length).trim()
                        heard.contains("جارویس") -> heard.substringAfter("جارویس").trim()
                        else -> heard.substringAfter("jarvis").trim()
                    }
                } else ""

                if (hasWake && command.length < 2) {
                    waitingForCommandUntil = System.currentTimeMillis() + 15000L
                    showAnswerNotification("در خدمتم؛ فرمانت را بگو")
                    speakInBackground("در خدمتم")
                    updateNotification("در انتظار فرمان تو هستم")
                    continue
                }

                val actualCommand = when {
                    hasWake && command.length >= 2 -> command
                    !hasWake && System.currentTimeMillis() <= waitingForCommandUntil -> heard
                    else -> continue
                }

                waitingForCommandUntil = 0L
                showAnswerNotification("درخواست: " + actualCommand + "\nدر حال آماده‌سازی پاسخ…", "در حال پردازش")
                val answer = answerInBackground(actualCommand)
                showAnswerNotification(answer)
                speakInBackground(answer.take(500))
                updateNotification("گوش‌به‌زنگ فعال است؛ بگو " + configuredWakePhrase())
            }
        } catch (e: Exception) {
            updateNotification("گوش‌به‌زنگ متوقف شد: " + (e.message ?: "خطای میکروفون"))
            running.set(false)
        } finally {
            try { recorder?.stop() } catch (_: Exception) {}
            try { recorder?.release() } catch (_: Exception) {}
            try { engine.unload() } catch (_: Exception) {}
        }
    }

    /** Captures one complete utterance using VAD; Whisper runs once per utterance, not every half-second. */
    private fun captureUtterance(recorder: AudioRecord, rate: Int, buffer: ShortArray): FloatArray? {
        val samples = ArrayList<Short>(rate * 4)
        var started = false
        var quietChunks = 0
        var elapsedChunks = 0
        val maxChunks = 50 // 10 seconds with 200 ms reads
        while (running.get() && elapsedChunks < maxChunks) {
            val n = recorder.read(buffer, 0, buffer.size)
            if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE ||
                n == AudioRecord.ERROR_DEAD_OBJECT) throw IllegalStateException("میکروفون قطع شد.")
            if (n <= 0) continue
            elapsedChunks++
            var energy = 0.0
            for (i in 0 until n) {
                val value = buffer[i].toDouble()
                energy += value * value
            }
            val rms = sqrt(energy / n) / 32768.0
            if (rms >= 0.0045) {
                started = true
                quietChunks = 0
            } else if (started) {
                quietChunks++
            }
            if (started) for (i in 0 until n) samples.add(buffer[i])
            if (started && quietChunks >= 4) break
        }
        if (!started || samples.size < rate / 4) return null
        return FloatArray(samples.size) { samples[it].toFloat() / 32768f }
    }

    private fun configuredWakePhrase(): String {
        val raw = getSharedPreferences("cht_ai", Context.MODE_PRIVATE)
            .getString("wake_phrase", "جارویس") ?: "جارویس"
        return normalizeSpeech(raw).ifBlank { "جارویس" }
    }

    /** Uses the phone's installed Persian TTS engine for a short hands-free acknowledgement/answer. */
    private fun speakInBackground(message: String) {
        if (message.isBlank()) return
        val ready = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        var speaker: android.speech.tts.TextToSpeech? = null
        try {
            speaker = android.speech.tts.TextToSpeech(this) { status ->
                if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                    try {
                        speaker?.language = java.util.Locale("fa", "IR")
                        speaker?.setSpeechRate(1.08f)
                        speaker?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                            override fun onStart(id: String?) {}
                            override fun onDone(id: String?) { finished.countDown() }
                            @Deprecated("Deprecated in Java")
                            override fun onError(id: String?) { finished.countDown() }
                        })
                        speaker?.speak(message, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "jarvis_bg_reply")
                    } catch (_: Exception) { finished.countDown() }
                } else finished.countDown()
                ready.countDown()
            }
            ready.await(4, java.util.concurrent.TimeUnit.SECONDS)
            finished.await(8, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {
        } finally {
            try { speaker?.stop(); speaker?.shutdown() } catch (_: Exception) {}
        }
    }

    private fun normalizeSpeech(value: String): String = value.lowercase()
        .replace("ي", "ی").replace("ك", "ک")
        .replace("‌", " ").replace(Regex("\\s+"), " ").trim()

    private fun answerInBackground(command: String): String {
        val agent = JarvisAgentCore(this)
        if (agent.isOnlineConfigured()) {
            try {
                return agent.askOnline(
                    "به عنوان JARVIS به این درخواست کاربر کوتاه، دقیق و به فارسی پاسخ بده. " +
                        "برنامه در پس‌زمینه است؛ هیچ ادعایی درباره اجرای عملیات گوشی نکن. درخواست: " + command.take(1800),
                    maxTokens = 180
                ).trim().ifBlank { "پاسخ خالی دریافت شد؛ دوباره تلاش کن." }
            } catch (_: Exception) {
                // Fall back to the downloaded on-device model.
            }
        }

        val modelFile = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(filesDir, "models"), "Qwen_Qwen3-1.7B-Q4_K_M.gguf")
        if (modelFile.isFile) {
            val local = LocalAiEngine()
            try {
                // Shorter, direct prompts and a smaller output budget reduce latency on-device.
                local.load(modelFile.absolutePath, threads = Runtime.getRuntime().availableProcessors().coerceIn(3, 6), context = 1536)
                val prompt = agent.buildLocalPrompt(
                    "فقط پاسخ کوتاه و مستقیم به فارسی بده. درخواست: " + command.take(900),
                    org.json.JSONArray()
                )
                return local.generate(prompt, 96).replace(Regex("<think>[\\s\\S]*?</think>"), "")
                    .trim().ifBlank { "پاسخ خالی بود؛ دوباره بگو." }
            } catch (e: Exception) {
                return "نتوانستم پاسخ را آماده کنم: " + (e.message ?: "خطای مدل محلی")
            } finally {
                try { local.unload() } catch (_: Exception) {}
            }
        }
        return "اتصال هوش آنلاین در دسترس نیست و مدل محلی هم آماده نیست. تنظیمات اتصال یا مدل را در JARVIS بررسی کن."
    }

    private fun showAnswerNotification(message: String, title: String = "پاسخ جارویس") {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            updateNotification("مجوز اعلان‌ها خاموش است؛ پاسخ جارویس نمایش داده نمی‌شود")
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(ANSWER_CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(
                ANSWER_CHANNEL_ID, "پاسخ‌های پس‌زمینه JARVIS", NotificationManager.IMPORTANCE_HIGH
            ).apply { lockscreenVisibility = Notification.VISIBILITY_PUBLIC })
        }
        val notification = NotificationCompat.Builder(this, ANSWER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(message.take(180))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.take(2500)))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .build()
        manager.notify(answerNotificationId, notification)
    }

    companion object {
        const val ACTION_STOP = "com.chtai.assistant.STOP_WAKE"
        const val ACTION_WAKE_DETECTED = "com.chtai.assistant.WAKE_DETECTED"
        private const val ANSWER_CHANNEL_ID = "jarvis_background_answers"
    }

    override fun onDestroy() {
        running.set(false)
        try { worker?.interrupt() } catch (_: Exception) {}
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

}
