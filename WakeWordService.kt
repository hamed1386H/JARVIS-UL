package com.chtai.assistant

import android.app.*
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import android.net.Uri
import android.telephony.SmsManager
import android.telecom.TelecomManager
import android.content.pm.PackageManager
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
// Build includes the SIM permission and wake full-screen behavior.
// Rebuild after correcting SIM-slot phrase normalization.
class WakeWordService : Service() {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private val channelId = "cht_wake_service_v2"
    private val notificationId = 7701
    private val wakeNotificationId = 7704
    private val answerNotificationId = 7705
    private val backgroundLocalAi = LocalAiEngine()
    private val backgroundLocalAiLoaded = AtomicBoolean(false)

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
            .setFullScreenIntent(if (wakeDetected) content else null, wakeDetected)
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
                val hasWake = wakeIndex >= 0 || heard.contains("جارویس") || heard.contains("jarvis") || heard.contains("بیدار شو")
                val command = if (hasWake) {
                    when {
                        wakeIndex >= 0 -> heard.substring(wakeIndex + wakePhrase.length).trim()
                        heard.contains("جارویس") -> heard.substringAfter("جارویس").trim()
                        heard.contains("jarvis") -> heard.substringAfter("jarvis").trim()
                        else -> heard.substringAfter("بیدار شو").trim()
                    }
                } else ""

                if (hasWake && command.length < 2) {
                    showAnswerNotification("در خدمتم؛ فرمانت را بگو")
                    speakWithMicPaused(recorder, "در خدمتم")
                    waitingForCommandUntil = System.currentTimeMillis() + 20000L
                    updateNotification("در انتظار فرمان تو هستم")
                    continue
                }

                val actualCommand = when {
                    hasWake && command.length >= 2 -> command
                    !hasWake && System.currentTimeMillis() <= waitingForCommandUntil -> heard
                    else -> continue
                }

                waitingForCommandUntil = 0L
                val actionResult = executeBackgroundPhoneAction(actualCommand)
                val navigationResult = if (actionResult == null) executeBackgroundNavigation(actualCommand) else null
                val answer = actionResult ?: navigationResult ?: answerInBackground(actualCommand)
                showAnswerNotification(answer)
                speakWithMicPaused(recorder, answer.take(500))
                updateNotification("گوش‌به‌زنگ فعال است؛ بگو " + configuredWakePhrase())
            }
        } catch (e: Exception) {
            updateNotification("گوش‌به‌زنگ متوقف شد: " + (e.message ?: "خطای میکروفون"))
            running.set(false)
        } finally {
            try { recorder?.stop() } catch (_: Exception) {}
            try { recorder?.release() } catch (_: Exception) {}
            try { engine.unload() } catch (_: Exception) {}
            try { backgroundLocalAi.unload(); backgroundLocalAiLoaded.set(false) } catch (_: Exception) {}
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



    private fun executeBackgroundNavigation(command: String): String? {
        val q = normalizeSpeech(command).replace(Regex("""\s+"""), " ").trim()
        val weather = q.contains("هواشناسی") || q.contains("آب و هوا") || q.contains("اب و هوا") || q.contains("وضعیت هوا")
        val searchMatch = Regex("""(?:در\s+گوگل\s+)?(?:جستجو کن|جست‌وجو کن|بگرد|سرچ کن)\s*(?:برای|دنبال)?\s*(.*)$""").find(q)
        if (weather || searchMatch != null) {
            val place = Regex("""(?:در|برای|شهر)\s+(.+?)(?:\s+(?:رو|را)?\s*(?:چک کن|بررسی کن))?$""").find(q)?.groupValues?.get(1)?.trim().orEmpty()
            val query = if (weather) {
                if (place.isBlank()) "هواشناسی امروز" else "هواشناسی " + place
            } else searchMatch?.groupValues?.get(1)?.trim().orEmpty()
            if (query.isBlank()) return "عبارت جستجو مشخص نیست."
            return try {
                val url = "https://www.google.com/search?q=" + java.net.URLEncoder.encode(query, "UTF-8")
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "نتایج گوگل برای «" + query + "» باز شد."
            } catch (_: Exception) { "بازکردن جستجوی گوگل ممکن نشد؛ مرورگر را بررسی کن." }
        }

        val appTarget = (Regex("""^(?:لطفا\s+)?(.+?)\s+(?:رو\s+|را\s+)?(?:باز\s*کن|اجرا\s*کن|راه\s*بنداز)$""").find(q)?.groupValues?.get(1)?.trim()
            ?: Regex("""^(?:باز\s*کن|اجرا\s*کن)\s+(.+)$""").find(q)?.groupValues?.get(1)?.trim()
            ?: return null).removePrefix("برنامه ").removePrefix("اپلیکیشن ").trim()
        return try {
            val aliases = mapOf(
                "اینستاگرام" to "com.instagram.android", "اینستا" to "com.instagram.android",
                "instagram" to "com.instagram.android", "واتساپ" to "com.whatsapp",
                "واتس اپ" to "com.whatsapp", "whatsapp" to "com.whatsapp",
                "تلگرام" to "org.telegram.messenger", "telegram" to "org.telegram.messenger",
                "یوتیوب" to "com.google.android.youtube", "youtube" to "com.google.android.youtube",
                "کروم" to "com.android.chrome", "chrome" to "com.android.chrome",
                "جیمیل" to "com.google.android.gm", "gmail" to "com.google.android.gm"
            )
            val aliasPackage = aliases.entries.firstOrNull { appTarget.contains(it.key, ignoreCase = true) }?.value
            if (aliasPackage != null) {
                val aliasIntent = packageManager.getLaunchIntentForPackage(aliasPackage)
                    ?: return "برنامه " + appTarget + " روی گوشی نصب نیست."
                aliasIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(aliasIntent)
                return "برنامه " + appTarget + " باز شد."
            }
            val installed = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            val target = appTarget.replace(Regex("""\s+"""), "")
            val candidates = installed.mapNotNull { info ->
                val label = packageManager.getApplicationLabel(info).toString()
                val normalized = normalizeSpeech(label).replace(Regex("""\s+"""), "")
                val score = when {
                    normalized == target -> 100
                    normalized.startsWith(target) || target.startsWith(normalized) -> 80
                    normalized.contains(target) || target.contains(normalized) -> 60
                    else -> 0
                }
                if (score > 0) Triple(info.packageName, label, score) else null
            }.sortedByDescending { it.third }
            val best = candidates.firstOrNull() ?: return "برنامه «" + appTarget + "» پیدا نشد."
            if (candidates.size > 1 && candidates[0].third == candidates[1].third)
                return "چند برنامه با این نام پیدا شد؛ برای انتخاب دقیق، JARVIS را باز کن."
            val launch = packageManager.getLaunchIntentForPackage(best.first) ?: return "این برنامه قابلیت اجرا از JARVIS را ندارد."
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
            "برنامه " + best.second + " باز شد."
        } catch (_: Exception) { "بازکردن برنامه از حالت آماده‌باش ممکن نشد؛ JARVIS را باز کن و دوباره تلاش کن." }
    }

    private fun executeBackgroundPhoneAction(command: String): String? {
        val q = normalizeSpeech(command).replace(Regex("\s+"), " ").trim()
        val simSlot = when {
            Regex("""(?:سیم ?کارت|سیم)\s*(?:یک|اول|۱|1)""").containsMatchIn(q) -> 0
            Regex("""(?:سیم ?کارت|سیم)\s*(?:دو|دوم|۲|2)""").containsMatchIn(q) -> 1
            else -> null
        }
        val cleanCommand = q.replace(Regex("""(?:با\s*)?(?:سیم ?کارت|سیم)\s*(?:یک|اول|۱|1|دو|دوم|۲|2)"""), " ")
            .replace(Regex("""\s+"""), " ").trim()
        val smsMatch = Regex("""به\s+(.+?)\s+(?:پیام\s*(?:بده|بفرست|ارسال\s*کن)|پیامک\s*(?:بده|بفرست|ارسال\s*کن)|اس\s*ام\s*اس\s*(?:بده|بفرست|ارسال\s*کن))""").find(cleanCommand)
        val callMatch = Regex("""^(?:لطفا\s+)?(?:به|با)\s+(.+?)\s+(?:زنگ\s+بزن|تماس\s+بگیر|تلفن\s+کن)$""").find(cleanCommand)
        val isSms = smsMatch != null
        val recipient = (smsMatch?.groupValues?.get(1) ?: callMatch?.groupValues?.get(1))?.trim() ?: return null
        val body = if (isSms) Regex("""(?:و\s*)?(?:بنویس|متن(?:ش)?(?:\s+این)?(?:\s+باشه)?|بگو|بفرست)\s*[:：،,-]*\s*(.+)$""")
            .find(cleanCommand)?.groupValues?.get(1)?.trim().orEmpty() else ""
        if (isSms && body.isBlank()) return "متن پیام مشخص نیست؛ بگو: به نام مخاطب پیام بده، بنویس متن پیام."
        val required = if (isSms) android.Manifest.permission.SEND_SMS else android.Manifest.permission.CALL_PHONE
        if (checkSelfPermission(required) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            val action = if (isSms) "ارسال پیامک" else "تماس"
            return "برای این کار مجوز مخاطبین و " + action + " لازم است؛ JARVIS را باز کن و مجوزها را فعال کن."
        }
        data class Contact(val name: String, val number: String, val score: Int)
        val contacts = ArrayList<Contact>()
        try {
            val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
            contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, null)?.use { cursor ->
                val nameCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val target = recipient.replace(Regex("\s+"), "")
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol).orEmpty()
                    val number = cursor.getString(numberCol).orEmpty()
                    val normalized = normalizeSpeech(name).replace(Regex("\s+"), "")
                    val score = when {
                        normalized == target -> 100
                        normalized.startsWith(target) || target.startsWith(normalized) -> 80
                        normalized.contains(target) || target.contains(normalized) -> 60
                        else -> 0
                    }
                    if (score > 0 && number.isNotBlank()) contacts.add(Contact(name, number, score))
                }
            }
        } catch (_: Exception) {
            return "خواندن مخاطبین ممکن نشد؛ مجوز مخاطبین را در تنظیمات JARVIS بررسی کن."
        }
        val matches = contacts.sortedByDescending { it.score }.distinctBy { it.number }
        if (matches.isEmpty()) return "مخاطبی با نام " + recipient + " پیدا نکردم."
        if (matches.size > 1 && matches[0].score <= matches[1].score) {
            val names = matches.take(3).joinToString("، ") { it.name }
            return "چند مخاطب با نام مشابه پیدا شد: " + names + ". برای جلوگیری از انتخاب اشتباه، JARVIS را باز کن و مخاطب را انتخاب کن."
        }
        val match = matches.first()
        return try {
            if (!isSms) {
                val call = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", match.number, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (simSlot != null) {
                    val account = phoneAccountForSlot(simSlot)
                        ?: return "سیم‌کارت " + (simSlot + 1) + " برای تماس شناسایی نشد؛ تماس برقرار نشد."
                    call.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account)
                }
                startActivity(call)
                "در حال تماس با " + match.name + (simSlot?.let { " با سیم‌کارت " + (it + 1) } ?: "")
            } else {
                val sms = smsManagerForSlot(simSlot)
                val parts = sms.divideMessage(body)
                val batchId = System.currentTimeMillis()
                val callbacks = ArrayList<android.app.PendingIntent>()
                for (index in parts.indices) {
                    val callback = Intent(this, SmsSentReceiver::class.java)
                        .setAction(SmsSentReceiver.ACTION_SMS_SENT)
                        .putExtra(SmsSentReceiver.EXTRA_BATCH_ID, batchId)
                        .putExtra(SmsSentReceiver.EXTRA_RECIPIENT, match.name)
                        .putExtra(SmsSentReceiver.EXTRA_PART_COUNT, parts.size)
                        .putExtra(SmsSentReceiver.EXTRA_PART_INDEX, index)
                    callbacks.add(android.app.PendingIntent.getBroadcast(
                        this, (batchId + index).toInt(), callback,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
                    ))
                }
                if (parts.size <= 1) sms.sendTextMessage(match.number, null, body, callbacks.first(), null)
                else sms.sendMultipartTextMessage(match.number, null, parts, callbacks, null)
                "پیامک به " + match.name + " برای ارسال ثبت شد؛ نتیجه ارسال در اعلان جداگانه نمایش داده می‌شود."
            }
        } catch (e: Exception) {
            "اجرای فرمان برای " + match.name + " ناموفق بود: " + (e.message ?: "خطای گوشی")
        }
    }

    private fun activeSubscriptionId(slot: Int): Int? {
        if (Build.VERSION.SDK_INT < 22 || checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val manager = getSystemService(android.telephony.SubscriptionManager::class.java) ?: return null
            manager.activeSubscriptionInfoList?.firstOrNull { it.simSlotIndex == slot }?.subscriptionId
        } catch (_: Exception) { null }
    }

    private fun smsManagerForSlot(slot: Int?): SmsManager {
        if (slot == null || Build.VERSION.SDK_INT < 22) {
            return if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) ?: SmsManager.getDefault() else SmsManager.getDefault()
        }
        val subId = activeSubscriptionId(slot) ?: throw IllegalStateException("سیم‌کارت ${slot + 1} فعال یا قابل دسترسی نیست")
        return SmsManager.getSmsManagerForSubscriptionId(subId)
    }

    private fun phoneAccountForSlot(slot: Int): android.telecom.PhoneAccountHandle? {
        if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return null
        val subId = activeSubscriptionId(slot) ?: return null
        return try {
            val telecom = getSystemService(TelecomManager::class.java) ?: return null
            telecom.callCapablePhoneAccounts.firstOrNull { it.id == subId.toString() || it.id.contains(subId.toString()) }
        } catch (_: Exception) { null }
    }

    private fun speakWithMicPaused(recorder: AudioRecord, message: String) {
        try { if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() } catch (_: Exception) {}
        speakInBackground(message)
        try { if (running.get() && recorder.state == AudioRecord.STATE_INITIALIZED) recorder.startRecording() } catch (_: Exception) {}
    }

    /** Uses the phone's installed Persian TTS engine for a short hands-free acknowledgement/answer. */
    private fun speakInBackground(message: String) {
        if (message.isBlank()) return
        val cloud = PersianCloudTts(this)
        if (cloud.isConfigured()) {
            var player: android.media.MediaPlayer? = null
            var audioFile: File? = null
            try {
                audioFile = cloud.synthesize(message)
                val completed = java.util.concurrent.CountDownLatch(1)
                player = android.media.MediaPlayer().apply {
                    setDataSource(audioFile!!.absolutePath)
                    setOnCompletionListener { completed.countDown() }
                    setOnErrorListener { _, _, _ -> completed.countDown(); true }
                    prepare()
                    start()
                }
                completed.await(35, java.util.concurrent.TimeUnit.SECONDS)
                try { player?.stop() } catch (_: Exception) {}
                try { player?.release() } catch (_: Exception) {}
                player = null
                try { audioFile.delete() } catch (_: Exception) {}
                return
            } catch (_: Exception) {
                try { player?.release() } catch (_: Exception) {}
                try { audioFile?.delete() } catch (_: Exception) {}
                // Fall back to the phone's installed TTS engine if cloud speech fails.
            }
        }
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
        .replace('٠','0').replace('١','1').replace('٢','2').replace('٣','3').replace('٤','4')
        .replace('٥','5').replace('٦','6').replace('٧','7').replace('٨','8').replace('٩','9')
        .replace('۰','0').replace('۱','1').replace('۲','2').replace('۳','3').replace('۴','4')
        .replace('۵','5').replace('۶','6').replace('۷','7').replace('۸','8').replace('۹','9')
        .replace("‌", " ").replace(Regex("\s+"), " ").trim()

    private fun answerInBackground(command: String): String {
        val agent = JarvisAgentCore(this)
        if (agent.isOnlineConfigured() && getSharedPreferences("cht_ai", Context.MODE_PRIVATE).getBoolean("online_ai_enabled", true)) {
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

        // Never cold-load the multi-gigabyte model in response to a wake-word command:
        // this used to hold the microphone loop and delay the answer for minutes.
        if (getSharedPreferences("cht_ai", Context.MODE_PRIVATE).getBoolean("local_ai_enabled", true) &&
            backgroundLocalAiLoaded.get() && backgroundLocalAi.isLoaded()) {
            try {
                val prompt = agent.buildLocalPrompt(
                    "فقط پاسخ کوتاه و مستقیم به فارسی بده. درخواست: " + command.take(700),
                    org.json.JSONArray()
                )
                return backgroundLocalAi.generate(prompt, 64).replace(Regex("<think>[\s\\S]*?</think>"), "")
                    .trim().ifBlank { "پاسخ خالی بود؛ دوباره بگو." }
            } catch (e: Exception) {
                return "پاسخ محلی آماده نشد: " + (e.message ?: "خطای مدل")
            }
        }
        return "اتصال هوش آنلاین پاسخ نداد و مدل محلی در پس‌زمینه آماده نیست. اینترنت و کلید API را بررسی کن یا JARVIS را باز کن تا مدل محلی بارگذاری شود."
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
