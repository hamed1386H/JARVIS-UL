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
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.RecognitionListener
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.widget.*
import android.graphics.Color
import android.graphics.Typeface
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var input: EditText
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var wakeMode = false
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("cht_ai", Context.MODE_PRIVATE) }
    private val requestSpeech = 101
    private val requestMic = 102
    private val requestCorePermissions = 103
    private var permissionStatusView: TextView? = null
    private var torchOn = false
    private var dndOn = false
    // Keep a short, user-visible conversation context so the AI can follow follow-up questions.
    private val conversation = JSONArray()
    private val maxHistoryMessages = 12
    private val localAi = LocalAiEngine()
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
                handleModelDownloadComplete(id)
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
    }

    private fun buildUi() {
        window.statusBarColor = Color.rgb(3, 3, 9)
        window.navigationBarColor = Color.rgb(3, 3, 9)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 42, 22, 18)
            setBackgroundColor(Color.rgb(3, 3, 9))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = TextView(this).apply {
            text = "J A R V I S"
            textSize = 21f
            letterSpacing = 0.22f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(actionButton("⚙", "تنظیمات JARVIS") { showJarvisSettings() })
        root.addView(header)

        val island = TextView(this).apply {
            text = "●  JARVIS  •  آماده‌باش"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(190, 160, 255))
            setPadding(22, 11, 22, 11)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(15, 10, 27))
                cornerRadius = 48f
                setStroke(1, Color.rgb(91, 48, 160))
            }
        }
        val islandWrap = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 8)
            addView(island, LinearLayout.LayoutParams(-2, -2))
        }
        root.addView(islandWrap)

        val orb = JarvisOrbView(this)
        root.addView(orb, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = 6
            bottomMargin = 4
        })

        status = TextView(this).apply {
            text = "در خدمتم"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(190, 180, 220))
            setPadding(4, 6, 4, 12)
        }
        root.addView(status)

        log = TextView(this).apply {
            text = "سلام. من JARVIS هستم.\nبرای شروع صحبت کن یا پیامت را بنویس.\nبرای گفت‌وگوی هوشمند، مدل GGUF را از تنظیمات بارگذاری کن."
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(12, 12, 12, 12)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(log)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(10, 9, 18))
                cornerRadius = 22f
                setStroke(1, Color.rgb(39, 28, 61))
            }
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 0.85f).apply {
            topMargin = 8
            bottomMargin = 12
        })

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(15, 13, 23))
                cornerRadius = 28f
                setStroke(1, Color.rgb(75, 47, 120))
            }
            setPadding(12, 5, 8, 5)
        }
        input = EditText(this).apply {
            hint = "با JARVIS صحبت کن…"
            setHintTextColor(Color.rgb(135, 128, 151))
            setTextColor(Color.WHITE)
            textSize = 15f
            setSingleLine(true)
            setBackgroundColor(Color.TRANSPARENT)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
        }
        composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        composer.addView(actionButton("🎙", "فرمان صوتی") { beginListening() })
        composer.addView(actionButton("➤", "ارسال پیام") {
            val q = input.text.toString().trim()
            if (q.isNotEmpty()) {
                input.setText("")
                handleCommand(q)
            }
        })
        root.addView(composer)
        setContentView(root)
    }

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
            "پاک‌کردن تاریخچه گفتگو"
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
                }
            }
            .setNegativeButton("بستن", null)
            .show()
    }

    private fun weightParams() = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(3, 3, 3, 3) }
    private fun actionButton(label: String, content: String, action: () -> Unit): Button = Button(this).apply {
        text = label; textSize = 12f; isAllCaps = false; setOnClickListener { action() }
        contentDescription = content
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
        updatePermissionStatus()
    }

    override fun onPause() {
        // The foreground listening service intentionally continues while this screen is closed.
        super.onPause()
    }

    private fun beginListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), requestMic); return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "بگو چه کاری انجام بدهم")
        }
        try { startActivityForResult(intent, requestSpeech) } catch (_: Exception) { reply("تشخیص گفتار روی این گوشی در دسترس نیست") }
    }

    private fun toggleWakeMode() {
        if (isWakeServiceRunning()) {
            stopService(Intent(this, WakeWordService::class.java))
            reply("سرویس گوش‌به‌زنگ متوقف شد")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { reply("سرویس تشخیص گفتار روی گوشی در دسترس نیست"); return }
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
        if (requestCode == requestSpeech && resultCode == RESULT_OK) {
            val words = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            if (words.isNotBlank()) { addLog("شما", words); handleCommand(words) }
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
            .setTitle("فعال‌سازی مغز هوشمند JARVIS")
            .setMessage("مدل مکالمه‌ای Qwen3-4B-Instruct با کیفیت بالاتر انتخاب شده است. حجم دانلود حدود ۲.۵ گیگابایت است. دانلود فقط یک‌بار انجام می‌شود؛ پس از آن پاسخ‌گویی مدل روی خود گوشی و بدون API و اینترنت انجام خواهد شد. برای دانلود اولیه اینترنت و فضای خالی کافی لازم است.")
            .setPositiveButton("دانلود و فعال‌سازی") { _, _ -> enqueueRecommendedModelDownload() }
            .setNeutralButton("انتخاب مدل دیگر") { _, _ -> selectLocalModel() }
            .setNegativeButton("بعداً", null)
            .show()
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
        status.text = "در حال فکر کردن روی گوشی…"
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
    private fun speak(message: String) { if (ttsReady) tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "jarvis_reply") }

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
        private const val MODEL_FILENAME = "Qwen3-4B-Instruct-2507-Q4_K_M.gguf"
        private const val MODEL_URL = "https://huggingface.co/bartowski/Qwen_Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q4_K_M.gguf"
        private const val KEY_MODEL_DOWNLOAD_ID = "jarvis_model_download_id"
    }

}
