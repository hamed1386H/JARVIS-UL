package com.chtai.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.app.DownloadManager
import android.os.Environment
import android.database.Cursor
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioFormat
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.widget.*
import android.graphics.Color
import android.graphics.Typeface
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var status: TextView
    private lateinit var orbView: JarvisOrbView
    private var autoMicStarted = false
    private lateinit var log: TextView
    private lateinit var input: EditText
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var wakeMode = false
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("cht_ai", Context.MODE_PRIVATE) }
    private val requestMic = 102
    private val requestCorePermissions = 103
    private var permissionStatusView: TextView? = null
    private var torchOn = false
    private var dndOn = false
    // Keep a short, user-visible conversation context so the AI can follow follow-up questions.
    private val conversation = JSONArray()
    private val maxHistoryMessages = 12
    private val localAi = LocalAiEngine()
    private val whisper = WhisperEngine()
    private val requestWhisperModel = 106
    private val requestDownloadFolder = 108
    private val PREF_DOWNLOAD_TREE = "jarvis_download_tree_uri"
    private val whisperModelFile by lazy { java.io.File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir, WHISPER_MODEL_FILENAME) }
    @Volatile private var activityVisible = false
    private val requestLocalModel = 105
    private val localModelFile by lazy {
        val base = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: java.io.File(filesDir, "models")
        java.io.File(base, MODEL_FILENAME)
    }
    private var modelReceiverRegistered = false
    private val modelDownloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id == prefs.getLong(KEY_WHISPER_DOWNLOAD_ID, -2L)) handleWhisperDownloadComplete(id) else handleModelDownloadComplete(id)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        restoreConversation()
        buildUi()
        registerModelDownloadReceiver()
        autoLoadLocalModel()
        autoLoadWhisperModel()
    }

    private fun buildUi() {
        window.statusBarColor = Color.rgb(3, 5, 16)
        window.navigationBarColor = Color.rgb(3, 5, 16)
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(38), dp(20), dp(12))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(3, 5, 17), Color.rgb(7, 10, 29), Color.rgb(2, 3, 11))
            )
        }

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val brand = TextView(this).apply {
            text = "J A R V I S"
            textSize = 25f
            letterSpacing = 0.30f
            setTextColor(Color.rgb(246, 247, 255))
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(actionButton("⚙", "تنظیمات JARVIS") { showJarvisSettings() },
            LinearLayout.LayoutParams(dp(54), dp(46)))
        root.addView(header)

        val pill = TextView(this).apply {
            text = "●   JARVIS   •   READY"
            textSize = 11f
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(190, 199, 255))
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = glass(Color.rgb(13, 17, 43), Color.rgb(83, 91, 177), 40)
        }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(2))
            addView(pill)
        })

        orbView = JarvisOrbView(this)
        root.addView(orbView, LinearLayout.LayoutParams(-1, dp(250)))

        status = TextView(this).apply {
            text = "در انتظار فرمان تو"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(166, 181, 222))
            setPadding(dp(8), 0, dp(8), dp(12))
        }
        root.addView(status)

        val chatShell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(Color.argb(205, 10, 13, 29), Color.rgb(48, 57, 103), 24)
            setPadding(dp(15), dp(14), dp(15), dp(14))
        }
        val chatHeader = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val chatTitle = TextView(this).apply {
            text = "گفتگوی امن"
            textSize = 12f
            setTextColor(Color.rgb(177, 190, 235))
            typeface = Typeface.DEFAULT_BOLD
        }
        chatHeader.addView(chatTitle, LinearLayout.LayoutParams(0, -2, 1f))
        chatHeader.addView(TextView(this).apply {
            text = "LOCAL AI"
            textSize = 9f
            letterSpacing = 0.12f
            setTextColor(Color.rgb(105, 224, 210))
            setPadding(dp(9), dp(5), dp(9), dp(5))
            background = glass(Color.rgb(10, 36, 42), Color.rgb(42, 125, 132), 20)
        })
        chatShell.addView(chatHeader)
        val divider = View(this).apply { setBackgroundColor(Color.rgb(38, 47, 82)) }
        chatShell.addView(divider, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(8) })

        log = TextView(this).apply {
            text = "سلام، من JARVIS هستم.\nبرای شروع، صحبت کن یا پیامت را بنویس.\n\nبرای فعال‌سازی مغز هوشمند، مدل Qwen3 را از تنظیمات دریافت کن."
            textSize = 15f
            setLineSpacing(dp(6).toFloat(), 1.04f)
            setTextColor(Color.rgb(231, 236, 255))
            setPadding(dp(4), dp(6), dp(4), dp(8))
            gravity = Gravity.TOP or Gravity.START
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG_RTL
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(log)
            setBackgroundColor(Color.TRANSPARENT)
        }
        chatShell.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(chatShell, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(4)
            bottomMargin = dp(12)
        })

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glass(Color.rgb(15, 18, 39), Color.rgb(82, 91, 157), 24)
            setPadding(dp(10), dp(7), dp(8), dp(7))
        }
        input = EditText(this).apply {
            hint = "پیامت را برای JARVIS بنویس…"
            setHintTextColor(Color.rgb(126, 139, 174))
            setTextColor(Color.WHITE)
            textSize = 14f
            setSingleLine(true)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            setBackgroundColor(Color.TRANSPARENT)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG_RTL
        }
        composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        composer.addView(actionButton("◉", "شروع صحبت با میکروفون") { beginListening() },
            LinearLayout.LayoutParams(dp(52), dp(48)).apply { marginEnd = dp(8) })
        composer.addView(actionButton("➤", "ارسال پیام") {
            val q = input.text.toString().trim()
            if (q.isNotEmpty()) { input.setText(""); handleCommand(q) }
        }, LinearLayout.LayoutParams(dp(52), dp(48)))
        root.addView(composer)
        setContentView(root)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun glass(fill: Int, stroke: Int, radiusDp: Int): android.graphics.drawable.GradientDrawable = android.graphics.drawable.GradientDrawable().apply { setColor(fill); cornerRadius = dp(radiusDp).toFloat(); setStroke(dp(1), stroke) }
    private fun showJarvisSettings() {
        val items = arrayOf(
            "دانلود و فعال‌سازی مغز هوشمند JARVIS (Qwen3-4B)",
            "انتخاب فایل مدل GGUF دیگر",
            "تخلیه مدل از حافظه",
            "روشن / خاموش کردن هوش مصنوعی محلی",
            "انتخاب صدای دستیار",
            "فعال / غیرفعال کردن گوش‌به‌زنگ",
            "مدیریت مجوزهای گوشی",
            "نمایش برنامه‌ها",
            "تنظیمات وای‌فای",
            "تنظیمات بلوتوث",
            "تنظیم صدا و روشنایی",
            "تنظیمات باتری",
            "پاک‌کردن تاریخچه گفتگو",
            "دانلود و فعال‌سازی تشخیص صدای فارسی (Whisper)"
        )
        AlertDialog.Builder(this)
            .setTitle("تنظیمات JARVIS")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> promptRecommendedModelDownload()
                    1 -> selectLocalModel()
                    2 -> unloadLocalModel()
                    3 -> toggleLocalAi()
                    4 -> showVoicePicker()
                    5 -> toggleWakeMode()
                    6 -> showPermissionCenter()
                    7 -> showApps()
                    8 -> openWifi()
                    9 -> openSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
                    10 -> volumeDialog()
                    11 -> openSettings(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                    12 -> clearConversation()
                    13 -> promptWhisperModelDownload()
                }
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun weightParams() = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(3, 3, 3, 3) }
    private fun actionButton(label: String, content: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 17f
        isAllCaps = false
        setTextColor(Color.rgb(239, 241, 255))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        stateListAnimator = null
        background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(42, 38, 91), Color.rgb(24, 30, 65))
        ).apply {
            cornerRadius = dp(15).toFloat()
            setStroke(dp(1), Color.rgb(89, 94, 165))
        }
        setPadding(dp(8), dp(5), dp(8), dp(5))
        contentDescription = content
        setOnClickListener { action() }
    }

    private fun addLog(who: String, message: String) {
        log.append("\n\n$who: $message")
    }

    private fun showPermissionCenter() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
        }
        val explanation = TextView(this).apply {
            text = "مجوزها را خود برنامه آماده می‌کند و صفحه رسمی اندروید را باز می‌کند. هیچ مجوزی مخفیانه یا خودکار تأیید نمی‌شود. پس از انتخاب «اجازه دادن» یا فعال‌سازی در تنظیمات، به JARVIS برگرد و «مجوز داده شد / بررسی دوباره» را بزن."
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 12)
        }
        content.addView(explanation)
        permissionStatusView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(150, 235, 190))
            setPadding(0, 4, 0, 12)
        }
        content.addView(permissionStatusView)
        val runtimeButton = Button(this).apply {
            text = "۱) اجازه میکروفون و دوربین (چراغ‌قوه)"
            isAllCaps = false
            setOnClickListener { requestCorePermissionsNow() }
        }
        content.addView(runtimeButton)
        val writeButton = Button(this).apply {
            text = "۲) اجازه تغییر تنظیمات سیستم (روشنایی)"
            isAllCaps = false
            setOnClickListener { requestWriteSettings() }
        }
        content.addView(writeButton)
        val dndButton = Button(this).apply {
            text = "۳) اجازه حالت مزاحم نشو"
            isAllCaps = false
            setOnClickListener { openDndPermissionSettings() }
        }
        content.addView(dndButton)
        val verifyButton = Button(this).apply {
            text = "✓ مجوز داده شد / بررسی دوباره"
            isAllCaps = false
            setOnClickListener { updatePermissionStatus(); reply("وضعیت مجوزها بررسی شد") }
        }
        content.addView(verifyButton)
        val persistenceButton = Button(this).apply {
            text = "۴) پایداری سرویس در پس‌زمینه (باتری / شروع خودکار)"
            isAllCaps = false
            setOnClickListener { showBackgroundPersistenceOptions() }
        }
        content.addView(persistenceButton)
        content.addView(Button(this).apply {
            text = "۵) اجازه اعلان‌های JARVIS"
            isAllCaps = false
            setOnClickListener {
                if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 107)
                else reply("مجوز اعلان‌ها از قبل فعال است")
            }
        })
        updatePermissionStatus()
        AlertDialog.Builder(this)
            .setTitle("مرکز مجوزهای JARVIS")
            .setView(content)
            .setPositiveButton("بستن", null)
            .show()
    }

    private fun requestCorePermissionsNow() {
        val missing = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            reply("میکروفون و دوربین از قبل مجاز هستند")
            updatePermissionStatus()
            return
        }
        ActivityCompat.requestPermissions(this, missing.toTypedArray(), requestCorePermissions)
    }

    private fun showBackgroundPersistenceOptions() {
        val choices = arrayOf("حذف محدودیت بهینه‌سازی باتری", "باز کردن تنظیمات شروع خودکار شیائومی", "صفحه اطلاعات برنامه")
        AlertDialog.Builder(this).setTitle("پایداری JARVIS")
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> {
                        try {
                            if (android.os.Build.VERSION.SDK_INT >= 23) {
                                val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
                                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                                } else reply("محدودیت بهینه‌سازی باتری قبلاً برداشته شده است")
                            }
                        } catch (_: Exception) { openAppDetails() }
                    }
                    1 -> {
                        try {
                            val intent = Intent().apply {
                                component = android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(intent)
                        } catch (_: Exception) { openAppDetails() }
                    }
                    else -> openAppDetails()
                }
            }.setNegativeButton("بستن", null).show()
    }

    private fun openAppDetails() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        catch (_: Exception) { openSettings(Settings.ACTION_SETTINGS) }
    }

    private fun openDndPermissionSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        } catch (_: Exception) {
            reply("صفحه مجوز مزاحم نشو در این نسخه اندروید باز نشد")
        }
    }

    private fun hasDndAccess(): Boolean {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        return android.os.Build.VERSION.SDK_INT < 23 || nm.isNotificationPolicyAccessGranted
    }

    private fun updatePermissionStatus() {
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val write = Settings.System.canWrite(this)
        val dnd = hasDndAccess()
        permissionStatusView?.text = "وضعیت فعلی:\n${if (mic) "✓" else "○"} میکروفون\n${if (camera) "✓" else "○"} دوربین / چراغ‌قوه\n${if (write) "✓" else "○"} تغییر تنظیمات سیستم / روشنایی\n${if (dnd) "✓" else "○"} حالت مزاحم نشو\n\nعلامت ✓ یعنی مجوز فعال است."
    }

    override fun onResume() {
        super.onResume()
        activityVisible = true
        updatePermissionStatus()
        scanGrantedDownloadFolder()
        if (!autoMicStarted) {
            autoMicStarted = true
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginListening()
            else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), requestMic)
        }
    }

    override fun onPause() {
        activityVisible = false
        super.onPause()
    }

    private fun beginListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), requestMic); return
        }
        if (!WhisperEngine.isAvailable()) { reply("کتابخانه Whisper در این نسخه وجود ندارد."); return }
        if (!whisper.isLoaded()) {
            if (whisperModelFile.isFile && whisperModelFile.length() > 50000000L) { loadWhisperModel(whisperModelFile); return }
            promptWhisperModelDownload(); return
        }
        captureAndTranscribe()
    }

    private fun captureAndTranscribe() {
        if (!activityVisible || !whisper.isLoaded()) return
        status.text = "JARVIS گوش می‌دهد…"; orbView.setMode(JarvisOrbView.Mode.LISTENING)
        executor.execute {
            var recorder: AudioRecord? = null
            try {
                val rate = 16000
                val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minBuffer > 0) { "ضبط صدای ۱۶ کیلوهرتز در دسترس نیست." }
                recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, 6400))
                check(recorder.state == AudioRecord.STATE_INITIALIZED) { "میکروفون آماده نشد." }
                val chunk = ShortArray(320); val samples = ArrayList<Float>(rate * 12)
                var started = false; var silence = 0; var elapsed = 0
                recorder.startRecording()
                while (elapsed < 15000 && (!started || silence < 850)) {
                    val n = recorder.read(chunk, 0, chunk.size); if (n <= 0) continue
                    var energy = 0.0
                    for (i in 0 until n) { val v = chunk[i].toFloat() / 32768f; samples.add(v); energy += chunk[i].toDouble() * chunk[i].toDouble() }
                    val rms = kotlin.math.sqrt(energy / n).toInt()
                    if (rms > 420) { started = true; silence = 0 } else if (started) silence += n * 1000 / rate
                    elapsed += n * 1000 / rate
                    val level = (rms / 5000f).coerceIn(0f, 1f)
                    runOnUiThread { orbView.setAudioLevel(level) }
                }
                recorder.stop()
                val audio = FloatArray(samples.size) { samples[it] }
                runOnUiThread { orbView.setAudioLevel(0f); orbView.setMode(JarvisOrbView.Mode.THINKING); status.text = "در حال تشخیص فارسی با Whisper…" }
                if (audio.size < rate / 2) { runOnUiThread { status.text = "صدایی دریافت نشد؛ دوباره صحبت کن"; orbView.setMode(JarvisOrbView.Mode.IDLE) }; return@execute }
                val words = whisper.transcribe(audio)
                runOnUiThread { if (words.isNotBlank()) { addLog("شما", words); handleCommand(words) } else { status.text = "متوجه صحبتت نشدم؛ دوباره بگو"; orbView.setMode(JarvisOrbView.Mode.IDLE) } }
            } catch (e: Exception) { runOnUiThread { reply("تشخیص صدای Whisper ناموفق بود: " + (e.message ?: "خطای ناشناخته")) } }
            finally { try { recorder?.release() } catch (_: Exception) {}; runOnUiThread { orbView.setAudioLevel(0f) } }
        }
    }
    private fun autoLoadWhisperModel() {
        if (whisperModelFile.isFile && whisperModelFile.length() > 50000000L && WhisperEngine.isAvailable()) {
            executor.execute { try { whisper.load(whisperModelFile.absolutePath) } catch (_: Exception) { } }
        }
    }

    private fun promptWhisperModelDownload() {
        showModelDownloadDialog(
            title = "مدل تشخیص صدای فارسی Whisper",
            description = "فایل مدل Whisper Base حدود ۸۲ مگابایت است. دانلود مستقیم یا دانلود با Chrome و ADM را انتخاب کن.",
            url = WHISPER_MODEL_URL,
            fileName = WHISPER_MODEL_FILENAME,
            isWhisper = true
        )
    }

    private fun showModelDownloadDialog(title: String, description: String, url: String, fileName: String, isWhisper: Boolean) {
        val choices = arrayOf("دانلود با برنامه دلخواه (Chrome / ADM)", "دانلود مستقیم داخل JARVIS", "کپی لینک مستقیم", "بعداً")
        AlertDialog.Builder(this).setTitle(title).setMessage("$description\n\nلینک مستقیم:\n$url")
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> {
                        prefs.edit().putString("pending_external_model", if (isWhisper) "whisper" else "qwen").apply()
                        ensureDownloadFolderPermissionThenChooseApp()
                    }
                    1 -> if (isWhisper) enqueueWhisperDownload() else enqueueRecommendedModelDownload()
                    2 -> copyDirectModelLink(url, fileName)
                }
            }.show()
    }

    private fun copyDirectModelLink(url: String, label: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, url))
        reply("لینک مستقیم کپی شد.")
    }

    private fun ensureDownloadFolderPermissionThenChooseApp() {
        val saved = prefs.getString(PREF_DOWNLOAD_TREE, null)
        if (!saved.isNullOrBlank()) {
            launchExternalModelChooser()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("اتصال خودکار فایل دانلودشده")
            .setMessage("برای اینکه بعد از دانلود با Chrome یا ADM فایل بدون انتخاب دستی وارد JARVIS شود، فقط یک‌بار پوشه Downloads را انتخاب و اجازه دسترسی بده. سپس برنامه فایل مدل را خودکار پیدا و وارد می‌کند.")
            .setPositiveButton("انتخاب پوشه Downloads") { _, _ ->
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                startActivityForResult(intent, requestDownloadFolder)
            }
            .setNegativeButton("لغو", null).show()
    }

    private fun launchExternalModelChooser() {
        val kind = prefs.getString("pending_external_model", "whisper") ?: "whisper"
        val url = if (kind == "qwen") MODEL_URL else WHISPER_MODEL_URL
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addCategory(Intent.CATEGORY_BROWSABLE) }
        try {
            startActivity(Intent.createChooser(intent, "برای دانلود مدل JARVIS کدام برنامه را باز کنم؟"))
        } catch (e: Exception) {
            reply("برنامه دانلود سازگار پیدا نشد؛ لینک مستقیم را کپی کن.")
        }
    }

    private fun scanGrantedDownloadFolder() {
        val raw = prefs.getString(PREF_DOWNLOAD_TREE, null) ?: return
        val tree = try { DocumentFile.fromTreeUri(this, Uri.parse(raw)) } catch (_: Exception) { null } ?: return
        executor.execute {
            try {
                val kind = prefs.getString("pending_external_model", "") ?: ""
                if (kind.isBlank()) return@execute
                val expected = if (kind == "qwen") MODEL_FILENAME else WHISPER_MODEL_FILENAME
                val found = findModelDocument(tree, expected, 4) ?: return@execute
                val target = if (kind == "qwen") localModelFile else whisperModelFile
                if (target.isFile && target.length() == found.length()) return@execute
                val temp = java.io.File(target.parentFile, target.name + ".importing")
                target.parentFile?.mkdirs()
                val uri = found.uri
                contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().buffered(1024 * 1024).use { output -> input.copyTo(output, 1024 * 1024) }
                } ?: throw IllegalStateException("فایل دانلودشده قابل خواندن نیست")
                if (kind == "qwen") {
                    require(temp.length() > 2_000_000_000L) { "فایل GGUF هنوز کامل دانلود نشده است" }
                    temp.inputStream().use { stream ->
                        val magic = ByteArray(4)
                        require(stream.read(magic) == 4 && String(magic, Charsets.US_ASCII) == "GGUF") { "فایل دانلودشده GGUF نیست" }
                    }
                } else {
                    require(temp.length() > 50_000_000L) { "فایل Whisper هنوز کامل دانلود نشده است" }
                }
                if (target.exists()) target.delete()
                check(temp.renameTo(target)) { "ذخیره فایل مدل ناموفق بود" }
                prefs.edit().remove("pending_external_model").apply()
                if (kind == "qwen") {
                    prefs.edit().putString("local_model_path", target.absolutePath).putBoolean("local_ai_enabled", true).apply()
                    runOnUiThread { reply("مدل Qwen از پوشه دانلودها به‌صورت خودکار وارد شد؛ در حال فعال‌سازی…"); loadLocalModel(target) }
                } else {
                    runOnUiThread { reply("مدل Whisper از پوشه دانلودها به‌صورت خودکار وارد شد؛ در حال بارگذاری…"); loadWhisperModel(target) }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "واردسازی خودکار مدل: ${e.message ?: "در انتظار تکمیل دانلود"}" }
            }
        }
    }

    private fun findModelDocument(folder: DocumentFile, expectedName: String, depth: Int): DocumentFile? {
        if (depth < 0 || !folder.isDirectory) return null
        for (item in folder.listFiles()) {
            if (item.isFile && item.name == expectedName) return item
            if (item.isDirectory) {
                val nested = findModelDocument(item, expectedName, depth - 1)
                if (nested != null) return nested
            }
        }
        return null
    }

    private fun openWhisperInChrome() {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(WHISPER_MODEL_URL)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            setPackage("com.android.chrome")
        }
        try { startActivity(view) }
        catch (_: Exception) { launchExternalModelChooser() }
    }

    private fun shareWhisperModelLink() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "مدل فارسی Whisper برای JARVIS")
            putExtra(Intent.EXTRA_TEXT, WHISPER_MODEL_URL)
        }
        startActivity(Intent.createChooser(send, "ارسال لینک با ADM یا برنامه دیگر"))
    }

    private fun copyWhisperModelLink() = copyDirectModelLink(WHISPER_MODEL_URL, WHISPER_MODEL_FILENAME)

    private fun enqueueWhisperDownload() {
        try {
            whisperModelFile.parentFile?.mkdirs()
            val request = DownloadManager.Request(Uri.parse(WHISPER_MODEL_URL))
                .setTitle("دانلود مدل فارسی Whisper")
                .setDescription("مدل تشخیص گفتار فارسی JARVIS")
                .setAllowedOverMetered(true).setAllowedOverRoaming(false)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, WHISPER_MODEL_FILENAME)
            val id = (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            prefs.edit().putLong(KEY_WHISPER_DOWNLOAD_ID, id).apply()
            reply("دانلود مدل فارسی Whisper آغاز شد؛ پس از پایان خودکار بارگذاری می‌شود.")
        } catch (e: Exception) { reply("شروع دانلود Whisper ممکن نشد: " + (e.message ?: "خطا")) }
    }

    private fun selectWhisperModelFile() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/octet-stream" }
        startActivityForResult(pick, requestWhisperModel)
    }

    private fun importWhisperModel(uri: Uri) {
        status.text = "در حال وارد کردن مدل Whisper…"
        executor.execute {
            try {
                whisperModelFile.parentFile?.mkdirs()
                contentResolver.openInputStream(uri)!!.use { input ->
                    whisperModelFile.outputStream().buffered(1024 * 1024).use { output -> input.copyTo(output, 1024 * 1024) }
                }
                require(whisperModelFile.length() > 50000000L) { "فایل انتخاب‌شده اندازه مدل Whisper را ندارد." }
                whisper.load(whisperModelFile.absolutePath)
                runOnUiThread { reply("مدل فارسی Whisper بارگذاری شد؛ تشخیص صدا داخل JARVIS انجام می‌شود.") }
            } catch (e: Exception) { runOnUiThread { reply("وارد کردن مدل Whisper ناموفق بود: " + (e.message ?: "خطا")) }
            }
        }
    }

    private fun loadWhisperModel(file: java.io.File) {
        status.text = "در حال بارگذاری مدل فارسی Whisper…"
        executor.execute {
            try {
                whisper.load(file.absolutePath)
                runOnUiThread { status.text = "Whisper فارسی آماده است."; if (activityVisible) captureAndTranscribe() }
            } catch (e: Exception) { runOnUiThread { reply("بارگذاری مدل Whisper ناموفق بود: " + (e.message ?: "خطا")) }
            }
        }
    }

    private fun handleWhisperDownloadComplete(id: Long) {
        if (id != prefs.getLong(KEY_WHISPER_DOWNLOAD_ID, -1L)) return
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (cursor.moveToFirst() && cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL &&
                whisperModelFile.isFile && whisperModelFile.length() > 50000000L) {
                prefs.edit().remove(KEY_WHISPER_DOWNLOAD_ID).apply()
                loadWhisperModel(whisperModelFile)
            } else { prefs.edit().remove(KEY_WHISPER_DOWNLOAD_ID).apply(); reply("دانلود مدل Whisper کامل یا معتبر نبود.") }
        }
    }

    private fun toggleWakeMode() {
        if (isWakeServiceRunning()) {
            stopService(Intent(this, WakeWordService::class.java))
            reply("سرویس گوش‌به‌زنگ متوقف شد")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), requestMic)
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 104)
            return
        }
        startWakeServiceNow()
    }

    private fun startWakeServiceNow() {
        try {
            ContextCompat.startForegroundService(this, Intent(this, WakeWordService::class.java))
            reply("گوش‌به‌زنگ فعال شد؛ اعلان دائمی JARVIS نمایش داده می‌شود")
        } catch (_: Exception) {
            reply("فعال‌سازی سرویس ممکن نشد؛ مجوز میکروفون، اعلان و محدودیت باتری را بررسی کن")
        }
    }

    private fun isWakeServiceRunning(): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == WakeWordService::class.java.name }
    }

    private fun showVoicePicker() {
        if (!ttsReady) { reply("موتور صدای گوشی هنوز آماده نیست"); return }
        val voices = tts.voices?.filter { it.locale.language == "fa" || it.locale.language == "en" }?.sortedWith(compareBy({ it.locale.language }, { it.name })) ?: emptyList()
        if (voices.isEmpty()) { reply("صدای جایگزین نصب‌شده‌ای پیدا نکردم؛ از تنظیمات تبدیل متن به گفتار اندروید صدا نصب کن"); return }
        val labels = voices.map { v ->
            val lang = if (v.locale.language == "fa") "فارسی" else "English"
            val quality = when (v.quality) { android.speech.tts.Voice.QUALITY_HIGH -> "کیفیت بالا"; android.speech.tts.Voice.QUALITY_NORMAL -> "معمولی"; else -> "کیفیت پایه" }
            "$lang • ${v.name} • $quality"
        }.toTypedArray()
        val selected = prefs.getString("tts_voice", "")
        val checked = voices.indexOfFirst { it.name == selected }.coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("انتخاب صدای JARVIS")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val voice = voices[which]
                val result = tts.setVoice(voice)
                if (result == TextToSpeech.SUCCESS) {
                    prefs.edit().putString("tts_voice", voice.name).apply()
                    reply("صدا تغییر کرد؛ سلام، من JARVIS هستم")
                } else reply("این صدا قابل فعال‌سازی نیست")
                dialog.dismiss()
            }.setNegativeButton("بستن", null).show()
    }

    @Deprecated("Deprecated Android callback kept for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestLocalModel && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) copyAndLoadLocalModel(uri) else reply("فایلی انتخاب نشد")
            return
        }
        if (requestCode == requestDownloadFolder && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    prefs.edit().putString(PREF_DOWNLOAD_TREE, uri.toString()).apply()
                    launchExternalModelChooser()
                } catch (e: Exception) { reply("اجازه پوشه دانلود ذخیره نشد: ${e.message ?: "خطا"}") }
            }
            return
        }
        if (requestCode == requestWhisperModel && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) importWhisperModel(uri) else reply("فایلی انتخاب نشد")
            return
        }
    }

    private fun normalize(s: String): String = s.lowercase(Locale("fa", "IR"))
        .replace("ي", "ی").replace("ك", "ک").replace("‌", " ").replace("ؤ", "و").replace("۰", "0").replace("۱", "1").replace("۲", "2").replace("۳", "3").replace("۴", "4").replace("۵", "5").replace("۶", "6").replace("۷", "7").replace("۸", "8").replace("۹", "9")

    private fun handleCommand(raw: String) {
        val q = normalize(raw)
        when {
            q.contains("وای فای") || q.contains("wifi") -> { reply("پنل وای‌فای را باز می‌کنم؛ تغییر اتصال را از همان‌جا تأیید کن"); openWifi() }
            q.contains("بلوتوث") -> { reply("تنظیمات بلوتوث را باز می‌کنم"); openSettings(Settings.ACTION_BLUETOOTH_SETTINGS) }
            q.contains("چراغ") || q.contains("فلش") -> {
                if (q.contains("خاموش")) setTorch(false) else if (q.contains("روشن")) setTorch(true) else toggleTorch()
            }
            q.contains("روشنایی") || q.contains("نور صفحه") -> {
                val percent = Regex("(\\d{1,3})").find(q)?.value?.toIntOrNull()
                if (percent != null) setBrightness(percent.coerceIn(1, 100)) else brightnessDialog()
            }
            q.contains("صدا") || q.contains("ولوم") || q.contains("بلندی") -> {
                val percent = Regex("(\\d{1,3})").find(q)?.value?.toIntOrNull()
                if (percent != null) setVolume(percent.coerceIn(0, 100)) else volumeDialog()
            }
            q.contains("مزاحم نشو") || q.contains("عدم مزاحمت") -> openDnd()
            q.contains("برنامه ها") || q.contains("اپلیکیشن") || q.contains("فهرست برنامه") -> showApps()
            q.contains("تنظیمات باتری") || q.contains("باتری") -> openSettings(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            q.contains("تنظیمات") -> openSettings(Settings.ACTION_SETTINGS)
            q.startsWith("باز کن") || q.startsWith("بازکن") -> {
                val name = q.removePrefix("باز کن").removePrefix("بازکن").trim()
                if (name.isBlank()) reply("نام برنامه را هم بگو") else launchAppByName(name)
            }
            q.endsWith("را باز کن") || q.endsWith("رو باز کن") -> {
                val name = q.removeSuffix("را باز کن").removeSuffix("رو باز کن").trim()
                if (name.isBlank()) reply("نام برنامه را هم بگو") else launchAppByName(name)
            }
            else -> askLocal(raw)
        }
    }

    private fun openWifi() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) startActivity(Intent("android.settings.panel.action.WIFI"))
            else startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        } catch (_: Exception) { openSettings(Settings.ACTION_SETTINGS) }
    }

    private fun openSettings(action: String) {
        try { startActivity(Intent(action)) } catch (_: Exception) { reply("این صفحه تنظیمات در نسخه اندروید گوشی در دسترس نیست") }
    }

    private fun showApps() {
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(intent, 0).sortedBy { it.loadLabel(packageManager).toString() }
        val names = apps.map { it.loadLabel(packageManager).toString() }
        addLog("JARVIS", if (names.isEmpty()) "برنامه‌ای پیدا نشد" else "برنامه‌های قابل اجرا:\n" + names.joinToString("\n"))
        reply("فهرست ${names.size} برنامه را نمایش دادم")
    }

    private fun launchAppByName(name: String) {
        val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = packageManager.queryIntentActivities(intent, 0).firstOrNull {
            normalize(it.loadLabel(packageManager).toString()).contains(name)
        }
        if (match == null) { reply("برنامه «$name» را پیدا نکردم"); return }
        val label = match.loadLabel(packageManager).toString()
        val launch = packageManager.getLaunchIntentForPackage(match.activityInfo.packageName)
        if (launch != null) { reply("$label را باز می‌کنم"); startActivity(launch) } else reply("امکان باز کردن این برنامه وجود ندارد")
    }

    private fun volumeDialog() {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val seek = SeekBar(this).apply { this.max = max; progress = current }
        AlertDialog.Builder(this).setTitle("تنظیم صدای رسانه").setView(seek)
            .setPositiveButton("ذخیره") { _, _ -> audio.setStreamVolume(AudioManager.STREAM_MUSIC, seek.progress, 0); reply("صدای رسانه تنظیم شد") }
            .setNegativeButton("بستن", null).show()
    }

    private fun setVolume(percent: Int) {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * percent / 100f).roundToInt().coerceIn(0, max), 0)
        reply("صدای رسانه روی $percent درصد تنظیم شد")
    }

    private fun brightnessDialog() {
        val seek = SeekBar(this).apply { max = 100; progress = 60 }
        AlertDialog.Builder(this).setTitle("روشنایی صفحه").setMessage("برای تغییر روشنایی کلی ممکن است لازم باشد مجوز تغییر تنظیمات سیستم را فعال کنی.")
            .setView(seek).setPositiveButton("اعمال") { _, _ -> setBrightness(seek.progress.coerceAtLeast(1)) }
            .setNeutralButton("دادن مجوز") { _, _ -> requestWriteSettings() }.setNegativeButton("بستن", null).show()
    }

    private fun setBrightness(percent: Int) {
        val value = (255 * percent / 100f).roundToInt().coerceIn(1, 255)
        if (Settings.System.canWrite(this)) {
            try { Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, value); reply("روشنایی کلی صفحه روی $percent درصد تنظیم شد") }
            catch (_: Exception) { reply("تغییر روشنایی انجام نشد") }
        } else {
            val lp = window.attributes; lp.screenBrightness = percent / 100f; window.attributes = lp
            reply("روشنایی پنجره برنامه روی $percent درصد تنظیم شد. برای تغییر کل گوشی، مجوز تغییر تنظیمات را فعال کن.")
        }
    }

    private fun requestWriteSettings() {
        try { startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))) }
        catch (_: Exception) { reply("صفحه مجوز تغییر تنظیمات باز نشد") }
    }

    private fun toggleTorch() = setTorch(!torchOn)
    private fun setTorch(enabled: Boolean) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), requestCorePermissions)
            reply("برای کنترل چراغ‌قوه، مجوز دوربین لازم است؛ اجازه رسمی اندروید را تأیید کن.")
            return
        }
        try {
            val manager = getSystemService(CAMERA_SERVICE) as CameraManager
            val cameraId = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            if (cameraId == null) { reply("چراغ‌قوه در این گوشی پیدا نشد"); return }
            manager.setTorchMode(cameraId, enabled); torchOn = enabled
            reply(if (enabled) "چراغ‌قوه روشن شد" else "چراغ‌قوه خاموش شد")
        } catch (_: Exception) { reply("کنترل چراغ‌قوه ممکن نشد؛ ممکن است دوربین در حال استفاده باشد") }
    }

    private fun openDnd() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 23 && !nm.isNotificationPolicyAccessGranted) {
            reply("برای کنترل حالت مزاحم نشو، دسترسی ویژه لازم است؛ صفحه مجوز را باز می‌کنم")
            try { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) } catch (_: Exception) { openSettings(Settings.ACTION_SETTINGS) }
        } else if (android.os.Build.VERSION.SDK_INT >= 23) {
            try {
                dndOn = nm.currentInterruptionFilter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL
                nm.setInterruptionFilter(if (dndOn) android.app.NotificationManager.INTERRUPTION_FILTER_ALL else android.app.NotificationManager.INTERRUPTION_FILTER_NONE)
                dndOn = !dndOn
                reply(if (dndOn) "حالت مزاحم نشو فعال شد" else "حالت مزاحم نشو غیرفعال شد")
            } catch (_: Exception) { openSettings(Settings.ACTION_SOUND_SETTINGS) }
        } else openSettings(Settings.ACTION_SOUND_SETTINGS)
    }

    private fun restoreConversation() {
        try {
            val saved = prefs.getString("conversation", "[]") ?: "[]"
            val parsed = JSONArray(saved)
            val start = (parsed.length() - maxHistoryMessages).coerceAtLeast(0)
            for (i in start until parsed.length()) conversation.put(parsed.getJSONObject(i))
        } catch (_: Exception) { /* Ignore an invalid old history safely. */ }
    }

    private fun saveConversation() {
        prefs.edit().putString("conversation", conversation.toString()).apply()
    }

    private fun addConversationMessage(role: String, content: String) {
        conversation.put(JSONObject().put("role", role).put("content", content))
        while (conversation.length() > maxHistoryMessages) conversation.remove(0)
        saveConversation()
    }

    private fun clearConversation() {
        AlertDialog.Builder(this).setTitle("پاک‌کردن گفتگو")
            .setMessage("تاریخچه گفت‌وگوی هوش مصنوعی از حافظه همین برنامه پاک شود؟")
            .setPositiveButton("پاک کن") { _, _ ->
                while (conversation.length() > 0) conversation.remove(0)
                prefs.edit().remove("conversation").apply()
                log.text = "گفتگو پاک شد. من JARVIS هستم؛ چه کمکی از من برمی‌آید؟"
                reply("تاریخچه گفتگو پاک شد")
            }.setNegativeButton("لغو", null).show()
    }

    private fun registerModelDownloadReceiver() {
        if (modelReceiverRegistered) return
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(modelDownloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(modelDownloadReceiver, filter)
        }
        modelReceiverRegistered = true
    }

    private fun promptRecommendedModelDownload() {
        if (isRecommendedModelDownloadActive()) {
            status.text = "دانلود مدل JARVIS در پس‌زمینه ادامه دارد؛ اعلان دانلود را بررسی کن."
            return
        }
        if (localModelFile.exists() && isValidGguf(localModelFile, 2_000_000_000L)) {
            if (!localAi.isLoaded()) loadLocalModel(localModelFile)
            else reply("مغز هوشمند JARVIS از قبل فعال است.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("روش دریافت مغز هوشمند JARVIS")
            .setMessage("مدل پیشنهادی Qwen3-4B-Instruct حدود ۲.۵ گیگابایت است. می‌توانی Chrome، ADM یا دانلود مستقیم JARVIS را انتخاب کنی. برای واردسازی خودکار از دانلودر بیرونی، فقط یک‌بار پوشه Downloads را مجاز کن.")
            .setItems(arrayOf(
                "دانلود با برنامه دلخواه (Chrome / ADM)",
                "دانلود مستقیم داخل JARVIS",
                "کپی لینک مستقیم دانلود",
                "انتخاب فایل مدل دانلودشده",
                "بعداً"
            )) { _, which ->
                when (which) {
                    0 -> { prefs.edit().putString("pending_external_model", "qwen").apply(); ensureDownloadFolderPermissionThenChooseApp() }
                    1 -> enqueueRecommendedModelDownload()
                    2 -> copyDirectModelLink(MODEL_URL, MODEL_FILENAME)
                    3 -> selectLocalModel()
                    else -> { }
                }
            }
            .show()
    }

    private fun shareRecommendedModelLink() {
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "دانلود مدل هوش مصنوعی JARVIS")
                putExtra(Intent.EXTRA_TEXT, MODEL_URL)
            }
            startActivity(Intent.createChooser(send, "ارسال لینک دانلود با ADM یا برنامه دیگر"))
        } catch (_: Exception) {
            copyRecommendedModelLink()
        }
    }

    private fun copyRecommendedModelLink() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("JARVIS GGUF model", MODEL_URL))
            reply("لینک مستقیم مدل کپی شد. آن را در ADM وارد کن؛ پس از دانلود، از گزینه انتخاب فایل مدل استفاده کن.")
        } catch (_: Exception) {
            reply("کپی لینک ممکن نشد.")
        }
    }

    private fun isRecommendedModelDownloadActive(): Boolean {
        val id = prefs.getLong(KEY_MODEL_DOWNLOAD_ID, -1L)
        if (id < 0L) return false
        return try {
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                if (!cursor.moveToFirst()) return false
                val index = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                if (index < 0) return false
                when (cursor.getInt(index)) {
                    DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> true
                    else -> false
                }
            } ?: false
        } catch (_: Exception) { false }
    }

    private fun enqueueRecommendedModelDownload() {
        try {
            if (localModelFile.exists()) localModelFile.delete()
            localModelFile.parentFile?.mkdirs()
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(MODEL_URL))
                .setTitle("دانلود مغز هوشمند JARVIS")
                .setDescription("دریافت مدل Qwen3-4B-Instruct؛ حدود ۲.۵ گیگابایت")
                .setMimeType("application/octet-stream")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, MODEL_FILENAME)
            val id = manager.enqueue(request)
            prefs.edit().putLong(KEY_MODEL_DOWNLOAD_ID, id).putBoolean("local_ai_enabled", true).apply()
            status.text = "دانلود مدل آغاز شد؛ از اعلان‌های گوشی می‌توانی پیشرفت را ببینی."
            reply("دانلود مغز JARVIS آغاز شد. پس از پایان دانلود، مدل به‌صورت خودکار فعال می‌شود.")
        } catch (e: Exception) {
            reply("شروع دانلود مدل ممکن نشد: ${e.message ?: "خطای ناشناخته"}")
        }
    }

    private fun handleModelDownloadComplete(downloadId: Long) {
        if (downloadId != prefs.getLong(KEY_MODEL_DOWNLOAD_ID, -1L)) return
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor: Cursor? = manager.query(DownloadManager.Query().setFilterById(downloadId))
        var successful = false
        var reason = "دانلود کامل نشد."
        cursor?.use {
            if (it.moveToFirst()) {
                val statusIndex = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val reasonIndex = it.getColumnIndex(DownloadManager.COLUMN_REASON)
                successful = statusIndex >= 0 && it.getInt(statusIndex) == DownloadManager.STATUS_SUCCESSFUL
                if (!successful && reasonIndex >= 0) reason = "دانلود با خطا متوقف شد (کد ${it.getInt(reasonIndex)})."
            }
        }
        if (successful && isValidGguf(localModelFile, 2_000_000_000L)) {
            prefs.edit().putString("local_model_path", localModelFile.absolutePath).putBoolean("local_ai_enabled", true).remove(KEY_MODEL_DOWNLOAD_ID).apply()
            reply("دانلود مدل کامل شد. در حال بارگذاری مغز هوشمند روی گوشی…")
            loadLocalModel(localModelFile)
        } else {
            localModelFile.delete()
            prefs.edit().remove(KEY_MODEL_DOWNLOAD_ID).apply()
            reply(if (successful) "فایل دانلودشده معتبر نیست یا کامل دریافت نشده است؛ دوباره تلاش کن." else reason)
        }
    }

    private fun isValidGguf(file: java.io.File, minimumBytes: Long = 100L * 1024L * 1024L): Boolean {
        if (!file.isFile || file.length() < minimumBytes) return false
        return try {
            file.inputStream().use { stream ->
                val magic = ByteArray(4)
                stream.read(magic) == 4 && String(magic, Charsets.US_ASCII) == "GGUF"
            }
        } catch (_: Exception) { false }
    }

    private fun loadLocalModel(file: java.io.File) {
        status.text = "در حال بارگذاری مدل محلی روی گوشی…"
        executor.execute {
            try {
                localAi.load(file.absolutePath, 4, 4096)
                prefs.edit().putString("local_model_path", file.absolutePath).putBoolean("local_ai_enabled", true).apply()
                runOnUiThread { status.text = "مغز هوشمند JARVIS فعال است؛ پردازش کاملاً محلی"; addLog("JARVIS", "مدل Qwen روی گوشی بارگذاری و آماده شد.") }
            } catch (e: Exception) {
                runOnUiThread { reply("بارگذاری مدل ناموفق بود: ${e.message ?: "حافظه کافی نیست یا فایل سازگار نیست."}") }
            }
        }
    }

    private fun autoLoadLocalModel() {
        if (!prefs.getBoolean("local_ai_enabled", true)) {
            status.text = "هوش مصنوعی محلی خاموش است"
            return
        }
        if (isRecommendedModelDownloadActive()) {
            status.text = "دانلود مغز هوشمند JARVIS در پس‌زمینه ادامه دارد."
            return
        }
        val savedPath = prefs.getString("local_model_path", "") ?: ""
        val savedFile = if (savedPath.isNotBlank()) java.io.File(savedPath) else null
        val isOldWeakModel = savedFile?.name?.contains("0.6b", ignoreCase = true) == true
        val file = when {
            isValidGguf(localModelFile, 2_000_000_000L) -> localModelFile
            savedFile != null && savedFile.isFile && !isOldWeakModel -> savedFile
            else -> localModelFile
        }
        if (isValidGguf(file)) {
            loadLocalModel(file)
        } else {
            status.text = "برای راه‌اندازی اولیه، مدل هوش مصنوعی باید دریافت شود."
            window.decorView.post { promptRecommendedModelDownload() }
        }
    }

    private fun selectLocalModel() {
        AlertDialog.Builder(this)
            .setTitle("انتخاب مدل هوش مصنوعی محلی")
            .setMessage("یک فایل GGUF سازگار انتخاب کن. مدل اصلی پیشنهادی JARVIS، Qwen3-4B-Instruct-2507 با کوانت Q4_K_M است. مدل‌های دیگر هم در صورت سازگاری با llama.cpp قابل آزمایش‌اند.")
            .setPositiveButton("انتخاب فایل مدل") { _, _ ->
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/octet-stream"
                }
                startActivityForResult(intent, requestLocalModel)
            }
            .setNegativeButton("بعداً", null).show()
    }

    private fun copyAndLoadLocalModel(uri: Uri) {
        status.text = "در حال کپی مدل به حافظه برنامه…"
        executor.execute {
            try {
                localModelFile.parentFile?.mkdirs()
                val temporaryFile = java.io.File(localModelFile.parentFile, "model.importing")
                val inputStream = requireNotNull(contentResolver.openInputStream(uri)) { "فایل انتخاب‌شده قابل خواندن نیست." }
                inputStream.use { source ->
                    temporaryFile.outputStream().buffered(1024 * 1024).use { target ->
                        source.copyTo(target, 1024 * 1024)
                    }
                }
                require(temporaryFile.length() > 100L * 1024L * 1024L) { "حجم فایل برای مدل موردنظر غیرعادی است." }
                temporaryFile.inputStream().use { stream ->
                    val magic = ByteArray(4)
                    require(stream.read(magic) == 4 && String(magic, Charsets.US_ASCII) == "GGUF") { "فایل انتخاب‌شده فرمت GGUF ندارد." }
                }
                if (localModelFile.exists()) localModelFile.delete()
                check(temporaryFile.renameTo(localModelFile)) { "ذخیره نهایی فایل مدل ناموفق بود." }
                runOnUiThread { status.text = "فایل ذخیره شد؛ در حال بارگذاری موتور هوش مصنوعی…" }
                check(LocalAiEngine.isNativeAvailable()) { "کتابخانه موتور llama.cpp در APK وجود ندارد. پروژه باید با NDK کامپایل شود." }
                localAi.load(localModelFile.absolutePath, 4, 4096)
                prefs.edit().putString("local_model_path", localModelFile.absolutePath).putBoolean("local_ai_enabled", true).apply()
                runOnUiThread { reply("مدل محلی بارگذاری شد. اکنون می‌توانی بدون API با من گفتگو کنی.") }
            } catch (e: Exception) {
                runOnUiThread { reply("بارگذاری مدل ناموفق بود: ${e.message ?: "خطای ناشناخته"}") }
            }
        }
    }

    private fun unloadLocalModel() {
        prefs.edit().putBoolean("local_ai_enabled", false).apply()
        executor.execute {
            try { localAi.unload() } catch (_: Exception) { }
            runOnUiThread { reply("مدل از حافظه خارج شد و منابع آزاد شدند. برای فعال‌سازی دوباره، گزینه هوش مصنوعی را روشن کن.") }
        }
    }

    private fun toggleLocalAi() {
        val enabled = !prefs.getBoolean("local_ai_enabled", true)
        prefs.edit().putBoolean("local_ai_enabled", enabled).apply()
        if (enabled) {
            val savedPath = prefs.getString("local_model_path", "") ?: ""
            val savedFile = if (savedPath.isNotBlank()) java.io.File(savedPath) else null
            val modelFile = if (savedFile != null && savedFile.isFile && !savedFile.name.contains("0.6b", true)) savedFile else localModelFile
            if (modelFile.isFile && isValidGguf(modelFile) && !localAi.isLoaded()) {
                loadLocalModel(modelFile)
            } else if (localAi.isLoaded()) reply("هوش مصنوعی محلی فعال است.") else promptRecommendedModelDownload()
        } else {
            unloadLocalModel()
        }
    }

    private fun askLocal(prompt: String) {
        if (!prefs.getBoolean("local_ai_enabled", true)) {
            reply("هوش مصنوعی خاموش است. از تنظیمات JARVIS آن را فعال کن؛ فرمان‌های پایه همچنان کار می‌کنند.")
            return
        }
        if (!localAi.isLoaded()) {
            reply("مغز هوشمند هنوز آماده نیست. از تنظیمات JARVIS دانلود و فعال‌سازی مدل اصلی را انتخاب کن.")
            return
        }
        status.text = "در حال فکر کردن روی گوشی…"; orbView.setMode(JarvisOrbView.Mode.THINKING)
        executor.execute {
            try {
                val promptBuilder = StringBuilder()
                promptBuilder.append("<|im_start|>system\n/no_think\n")
                    .append("تو JARVIS هستی؛ دستیار فارسی‌زبان، دوستانه، دقیق و مسئولیت‌پذیر. همیشه به فارسی روان و محاوره‌ای پاسخ بده مگر کاربر زبان دیگری بخواهد. پاسخ را مستقیم و کاربردی نگه دار و اگر چیزی را نمی‌دانی صادقانه بگو.\n" +
                        "فرمان‌های واقعی فعلی فقط این‌ها هستند: باز کردن یک برنامه با نام آن؛ باز کردن پنل Wi-Fi؛ باز کردن تنظیمات Bluetooth؛ باز کردن تنظیمات باتری؛ روشن یا خاموش کردن چراغ‌قوه؛ تنظیم درصد روشنایی؛ تنظیم درصد صدای رسانه؛ باز کردن تنظیمات مزاحم نشو.\n" +
                        "اگر کاربر فقط سؤال یا گفت‌وگوی عادی دارد، پاسخ متنی طبیعی بده. اگر واقعاً درخواست اجرای یکی از فرمان‌های پشتیبانی‌شده را دارد، فقط یک JSON معتبر با قالب {\"type\":\"action\",\"command\":\"فرمان کوتاه فارسی\"} تولید کن و هیچ متن دیگری اضافه نکن. برای هر قابلیت دیگر بگو هنوز پیاده‌سازی نشده است. هرگز ادعا نکن کاری انجام شده؛ اجرای فرمان فقط پس از تأیید کاربر انجام می‌شود.\n<|im_end|>\n")
                val first = (conversation.length() - 6).coerceAtLeast(0)
                for (i in first until conversation.length()) {
                    val item = conversation.getJSONObject(i)
                    val role = item.optString("role", "user")
                    val content = item.optString("content", "").takeLast(700)
                    promptBuilder.append("<|im_start|>").append(role).append("\n").append(content).append("<|im_end|>\n")
                }
                promptBuilder.append("<|im_start|>user\n").append(prompt.take(1200)).append("<|im_end|>\n<|im_start|>assistant\n/no_think\n")
                val answer = localAi.generate(promptBuilder.toString(), 384).trim()
                    .replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
                runOnUiThread {
                    addLog("شما", prompt)
                    addConversationMessage("user", prompt)
                    val action = parseAiAction(answer)
                    if (action != null) {
                        val command = action.optString("command", "").trim()
                        if (isAllowedAiCommand(command)) {
                            val message = "برای اجرای این فرمان تأیید تو لازم است: $command"
                            addLog("JARVIS", message)
                            addConversationMessage("assistant", message)
                            confirmAiAction(command)
                        } else {
                            val message = "این فرمان در فهرست عملیات امن و پشتیبانی‌شده نیست؛ اجرا نشد."
                            addLog("JARVIS", message); addConversationMessage("assistant", message); speak(message)
                        }
                    } else {
                        addLog("JARVIS", answer)
                        addConversationMessage("assistant", answer)
                        status.text = "پاسخ محلی آماده شد"
                        speak(answer)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { reply("خطای هوش مصنوعی محلی: ${e.message ?: "خطای ناشناخته"}") }
            }
        }
    }


    private fun parseAiAction(raw: String): JSONObject? {
        return try {
            val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val start = cleaned.indexOf('{')
            val end = cleaned.lastIndexOf('}')
            if (start < 0 || end <= start) null else JSONObject(cleaned.substring(start, end + 1)).takeIf { it.optString("type") == "action" }
        } catch (_: Exception) { null }
    }

    // Strict allowlist: the model may suggest only commands already implemented locally.
    private fun isAllowedAiCommand(command: String): Boolean {
        val q = normalize(command)
        if (q.isBlank() || q.length > 100) return false
        val hasPercent = Regex("\\d{1,3}").containsMatchIn(q)
        return when {
            q.contains("وای فای") || q.contains("wifi") || q.contains("بلوتوث") -> true
            q.contains("چراغ قوه") || q.contains("چراغ") || q.contains("فلش") -> q.contains("روشن") || q.contains("خاموش")
            q.contains("روشنایی") || q.contains("نور صفحه") || q.contains("صدا") || q.contains("ولوم") -> hasPercent
            q.contains("مزاحم نشو") || q.contains("عدم مزاحمت") -> true
            q.contains("تنظیمات باتری") || q == "باتری" || q == "تنظیمات" -> true
            q.contains("فهرست برنامه") || q.contains("برنامه ها") -> true
            q.endsWith("را باز کن") || q.endsWith("رو باز کن") || q.startsWith("باز کن ") || q.startsWith("بازکن ") -> true
            else -> false
        }
    }

    private fun confirmAiAction(command: String) {
        AlertDialog.Builder(this)
            .setTitle("تأیید اجرای فرمان")
            .setMessage("هوش مصنوعی پیشنهاد داده این فرمان اجرا شود:\n\n$command\n\nفقط در صورت تأیید تو اجرا می‌شود.")
            .setPositiveButton("تأیید و اجرا") { _, _ -> handleCommand(command) }
            .setNegativeButton("لغو") { _, _ -> reply("اجرای فرمان لغو شد") }
            .show()
    }

    private fun reply(message: String) { status.text = message; addLog("JARVIS", message); speak(message) }
    private fun speak(message: String) { if (ttsReady) { orbView.setMode(JarvisOrbView.Mode.SPEAKING); tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "jarvis_reply") } }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestMic && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) beginListening()
        else if (requestCode == requestMic) reply("برای فرمان صوتی باید مجوز میکروفون را بدهی")
        if (requestCode == 104) {
            if (android.os.Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) startWakeServiceNow()
            else reply("مجوز اعلان داده نشد؛ برای نمایش اعلان دائمی باید آن را از تنظیمات مجاز کنی")
        }
        if (requestCode == requestCorePermissions) {
            updatePermissionStatus()
            val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            reply("نتیجه مجوزها: میکروفون ${if (micGranted) "فعال" else "غیرفعال"}، دوربین/چراغ‌قوه ${if (cameraGranted) "فعال" else "غیرفعال"}. برای مجوزهای ویژه، دکمه‌های مرکز مجوزها را جداگانه استفاده کن.")
        }
    }

    override fun onInit(code: Int) {
        if (code == TextToSpeech.SUCCESS) {
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    runOnUiThread { if (::orbView.isInitialized) { orbView.setMode(JarvisOrbView.Mode.SPEAKING); orbView.setAudioLevel(0.15f) } }
                }
                override fun onDone(utteranceId: String?) {
                    runOnUiThread {
                        if (::orbView.isInitialized) { orbView.setAudioLevel(0f); orbView.setMode(JarvisOrbView.Mode.IDLE) }
                        if (activityVisible && whisper.isLoaded()) captureAndTranscribe()
                    }
                }
                override fun onError(utteranceId: String?) {
                    runOnUiThread { if (::orbView.isInitialized) { orbView.setAudioLevel(0f); orbView.setMode(JarvisOrbView.Mode.IDLE) } }
                }
                override fun onAudioAvailable(utteranceId: String?, audio: ByteArray?) {
                    if (audio == null || audio.size < 2) return
                    var sum = 0.0; var count = 0; var i = 0
                    while (i + 1 < audio.size) {
                        val sample = ((audio[i + 1].toInt() shl 8) or (audio[i].toInt() and 255)).toShort().toInt()
                        sum += sample.toDouble() * sample.toDouble(); count++; i += 2
                    }
                    val level = if (count == 0) 0f else (kotlin.math.sqrt(sum / count) / 12000.0).toFloat().coerceIn(0f, 1f)
                    runOnUiThread { if (::orbView.isInitialized) orbView.setAudioLevel(level) }
                }
            })
            val result = tts.setLanguage(Locale("fa", "IR"))
            ttsReady = result >= TextToSpeech.LANG_AVAILABLE
            val savedVoice = prefs.getString("tts_voice", "")
            if (!savedVoice.isNullOrBlank()) tts.voices?.firstOrNull { it.name == savedVoice }?.let { tts.setVoice(it) }
            if (!ttsReady) { tts.setLanguage(Locale.getDefault()); ttsReady = true; status.text = "صدای فارسی نصب نیست؛ زبان پیش‌فرض استفاده می‌شود" }
        }
    }

    override fun onDestroy() {
        if (modelReceiverRegistered) {
            try { unregisterReceiver(modelDownloadReceiver) } catch (_: Exception) { }
            modelReceiverRegistered = false
        }
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
        // Queue native cleanup behind any active inference to avoid freeing the model mid-generation.
        try {
            executor.execute { try { localAi.unload() } catch (_: Exception) { } }
            executor.shutdown()
        } catch (_: Exception) {
            executor.shutdownNow()
        }
        super.onDestroy()
    }

    companion object {
        private const val WHISPER_MODEL_FILENAME = "ggml-base-persian-q8_0.bin"
        private const val WHISPER_MODEL_URL = "https://huggingface.co/armantee/whisper-base-persian-ggml/resolve/main/ggml-base-persian-q8_0.bin"
        private const val KEY_WHISPER_DOWNLOAD_ID = "jarvis_whisper_download_id"
        private const val MODEL_FILENAME = "Qwen3-4B-Instruct-2507-Q4_K_M.gguf"
        private const val MODEL_URL = "https://huggingface.co/bartowski/Qwen_Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q4_K_M.gguf"
        private const val KEY_MODEL_DOWNLOAD_ID = "jarvis_model_download_id"
    }

}
