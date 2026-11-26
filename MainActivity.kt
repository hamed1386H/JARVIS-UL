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
import android.provider.ContactsContract
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.widget.*
import android.graphics.Color
import android.graphics.Typeface
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var status: TextView
    private lateinit var orbView: JarvisOrbView
    private lateinit var dynamicIsland: TextView
    private val voiceCaptureActive = java.util.concurrent.atomic.AtomicBoolean(false)
    private val whisperLoadActive = java.util.concurrent.atomic.AtomicBoolean(false)
    private lateinit var log: TextView
    private lateinit var chatScroll: ScrollView
    private lateinit var input: EditText
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    @Volatile private var pendingSpeech: String? = null
    private var wakeMode = false
    private val executor = Executors.newSingleThreadExecutor()
    // Keep continuous microphone capture/transcription off the AI/model worker queue.
    private val voiceExecutor = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("cht_ai", Context.MODE_PRIVATE) }
    private val requestMic = 102
    private val requestCorePermissions = 103
    private var permissionStatusView: TextView? = null
    private var firstLaunchPermissionFlowPending = false
    private var torchOn = false
    private var dndOn = false
    // Keep a short, user-visible conversation context so the AI can follow follow-up questions.
    private val conversation = JSONArray()
    private val maxHistoryMessages = 12
    private val localAi = LocalAiEngine()
    private val agentCore by lazy { JarvisAgentCore(this) }
    private val whisper = WhisperEngine()
    private val requestWhisperModel = 106
    private val requestDownloadFolder = 108
    private val requestSmsContacts = 109
    private var pendingSmsRecipient = ""
    private var pendingSmsBody = ""
    private val PREF_DOWNLOAD_TREE = "jarvis_download_tree_uri"
    private val whisperModelFile by lazy { java.io.File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir, WHISPER_MODEL_FILENAME) }
    @Volatile private var activityVisible = false
    private val requestLocalModel = 105
    private val localModelFile by lazy {
        val base = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: java.io.File(filesDir, "models")
        java.io.File(base, MODEL_FILENAME)
    }
    private var modelReceiverRegistered = false
    private var wakeReceiverRegistered = false
    private val wakeDetectedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WakeWordService.ACTION_WAKE_DETECTED && activityVisible) {
                reply("در خدمتم")
            }
        }
    }
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
        window.decorView.post { startFirstLaunchPermissionFlow() }
    }

    private fun buildUi() {
        window.statusBarColor = Color.rgb(2, 2, 9)
        window.navigationBarColor = Color.rgb(2, 2, 9)
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(38), dp(18), dp(12))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(2, 2, 10), Color.rgb(8, 5, 22), Color.rgb(1, 3, 12))
            )
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(actionButton("☰", "منوی جارویس") { showJarvisMenu() }, LinearLayout.LayoutParams(dp(48), dp(44)))
        val brand = TextView(this).apply {
            text = "J A R V I S"; textSize = 23f; letterSpacing = 0.28f; gravity = Gravity.CENTER
            setTextColor(Color.rgb(245, 244, 255)); typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(actionButton("⚙", "تنظیمات JARVIS") { showJarvisSettings() }, LinearLayout.LayoutParams(dp(48), dp(44)))
        root.addView(header)
        dynamicIsland = TextView(this).apply {
            text = "●  JARVIS  ·  آماده‌باش"; textSize = 11f; letterSpacing = 0.04f; gravity = Gravity.CENTER
            setTextColor(Color.rgb(220, 208, 255)); setPadding(dp(22), dp(10), dp(22), dp(10))
            background = glass(Color.rgb(7, 6, 18), Color.rgb(116, 65, 205), 30); elevation = dp(10).toFloat()
        }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(4))
            addView(dynamicIsland, LinearLayout.LayoutParams(-2, -2))
        })
        val orbStage = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        orbView = JarvisOrbView(this)
        orbView.setOnClickListener { beginListening() }
        orbStage.addView(orbView, FrameLayout.LayoutParams(-1, -1))
        root.addView(orbStage, LinearLayout.LayoutParams(-1, 0, 1f))
        status = TextView(this).apply {
            text = "برای صحبت، روی گوی نورانی بزن"; textSize = 13f; gravity = Gravity.CENTER
            setTextColor(Color.rgb(177, 183, 215)); setPadding(dp(8), dp(2), dp(8), dp(12))
        }
        root.addView(status)
        lateinit var composer: LinearLayout
        lateinit var chatToggle: Button
        val chatShell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(Color.argb(225, 10, 8, 25), Color.rgb(90, 56, 160), 22)
            setPadding(dp(13), dp(10), dp(13), dp(10)); visibility = View.GONE
        }
        val chatHeader = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        chatHeader.addView(TextView(this).apply {
            text = "گفتگوی JARVIS"; textSize = 12f; setTextColor(Color.rgb(203, 194, 235)); typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        chatShell.addView(chatHeader)
        val divider = View(this).apply { setBackgroundColor(Color.rgb(47, 37, 78)) }
        chatShell.addView(divider, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(7); bottomMargin = dp(5) })
        log = TextView(this).apply {
            text = "سلام، من JARVIS هستم.\nبرای شروع، صحبت کن یا پیامت را بنویس."
            textSize = 14f; setLineSpacing(dp(4).toFloat(), 1.02f); setTextColor(Color.rgb(235, 234, 250))
            setPadding(dp(3), dp(4), dp(3), dp(6)); gravity = Gravity.TOP or Gravity.START
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG_RTL
        }
        chatScroll = ScrollView(this).apply {
            isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = true
            addView(log); setBackgroundColor(Color.TRANSPARENT)
        }
        chatShell.addView(chatScroll, LinearLayout.LayoutParams(-1, dp(142)))
        root.addView(chatShell, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = glass(Color.rgb(10, 8, 24), Color.rgb(108, 66, 185), 24)
            setPadding(dp(8), dp(5), dp(7), dp(5)); visibility = View.GONE
        }
        input = EditText(this).apply {
            hint = "پیامت را برای JARVIS بنویس…"; setHintTextColor(Color.rgb(130, 128, 160))
            setTextColor(Color.WHITE); textSize = 14f; setSingleLine(true); setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.TRANSPARENT); imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG_RTL
        }
        composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        input.setOnEditorActionListener { _, actionId, event ->
            val sendByIme = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_DOWN)
            if (sendByIme) { sendTextMessage(); true } else false
        }
        composer.addView(actionButton("➤", "ارسال پیام") { sendTextMessage() }, LinearLayout.LayoutParams(dp(48), dp(44)))
        root.addView(composer, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        chatToggle = actionButton("چت", "نمایش گفتگوی متنی") {
            val show = chatShell.visibility != View.VISIBLE
            chatShell.visibility = if (show) View.VISIBLE else View.GONE
            composer.visibility = if (show) View.VISIBLE else View.GONE
            chatToggle.text = if (show) "بستن چت" else "چت"
        }
        val bottomControls = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        bottomControls.addView(chatToggle, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        bottomControls.addView(actionButton("🎙", "شروع صحبت با میکروفون") { beginListening() }, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(bottomControls)
        setContentView(root)
    }

    private fun showJarvisMenu() {
        val items = arrayOf("خانه و دستیار صوتی", "گفتگوی متنی", "تنظیمات", "برنامه‌های گوشی", "درباره JARVIS")
        AlertDialog.Builder(this).setTitle("منوی JARVIS").setItems(items) { _, which ->
            when (which) {
                0 -> { setJarvisMode(JarvisOrbView.Mode.IDLE); status.text = "برای صحبت، روی گوی نورانی بزن" }
                1 -> status.text = "برای باز کردن گفتگوی متنی، دکمه «چت» پایین صفحه را بزن"
                2 -> showJarvisSettings()
                3 -> showApps()
                4 -> AlertDialog.Builder(this).setTitle("درباره JARVIS")
                    .setMessage("دستیار هوشمند صوتی و متنی فارسی\nبا پشتیبانی از مدل محلی و موتور آنلاین اختیاری.")
                    .setPositiveButton("باشه", null).show()
            }
        }.setNegativeButton("بستن", null).show()
    }

    private fun setJarvisMode(mode: JarvisOrbView.Mode) {
        if (::orbView.isInitialized) orbView.setMode(mode)
        if (!::dynamicIsland.isInitialized) return
        val label = when (mode) {
            JarvisOrbView.Mode.IDLE -> "●  JARVIS  ·  آماده‌باش"
            JarvisOrbView.Mode.LISTENING -> "◉  در حال گوش‌دادن به صدای تو…"
            JarvisOrbView.Mode.THINKING -> "✧  در حال پردازش فرمان…"
            JarvisOrbView.Mode.SPEAKING -> "♫  JARVIS در حال صحبت…"
        }
        dynamicIsland.text = label
        val active = mode != JarvisOrbView.Mode.IDLE
        dynamicIsland.setPadding(dp(if (active) 25 else 20), dp(if (active) 12 else 9), dp(if (active) 25 else 20), dp(if (active) 12 else 9))
        val stroke = when (mode) {
            JarvisOrbView.Mode.LISTENING -> Color.rgb(45, 190, 235)
            JarvisOrbView.Mode.THINKING -> Color.rgb(150, 91, 245)
            JarvisOrbView.Mode.SPEAKING -> Color.rgb(70, 205, 190)
            JarvisOrbView.Mode.IDLE -> Color.rgb(105, 75, 190)
        }
        dynamicIsland.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.rgb(9, 9, 24)); cornerRadius = dp(28).toFloat(); setStroke(dp(1), stroke)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun glass(fill: Int, stroke: Int, radiusDp: Int): android.graphics.drawable.GradientDrawable = android.graphics.drawable.GradientDrawable().apply { setColor(fill); cornerRadius = dp(radiusDp).toFloat(); setStroke(dp(1), stroke) }
    private fun showJarvisSettings() {
        val groups = arrayOf(
            "🧠 هوش مصنوعی و مدل‌ها" to arrayOf("دانلود / فعال‌سازی مدل محلی Qwen", "انتخاب فایل مدل GGUF", "تخلیه مدل از حافظه", "روشن / خاموش کردن هوش محلی", "تنظیم موتور هوش آنلاین"),
            "🎙 صدا و گفتار" to arrayOf("انتخاب صدای خروجی JARVIS", "دانلود / فعال‌سازی تشخیص گفتار Whisper", "راهنمای نصب صدای فارسی گوشی"),
            "📱 گوشی و دسترسی‌ها" to arrayOf("مدیریت مجوزهای گوشی", "برنامه‌های نصب‌شده", "تنظیمات وای‌فای", "تنظیمات بلوتوث", "صدا و روشنایی", "تنظیمات باتری"),
            "⚙️ دستیار و اطلاعات" to arrayOf("فعال / غیرفعال کردن گوش‌به‌زنگ", "پاک‌کردن تاریخچه گفتگو", "درباره JARVIS")
        )
        val actions = arrayOf(
            arrayOf<() -> Unit>({ promptRecommendedModelDownload() }, { selectLocalModel() }, { unloadLocalModel() }, { toggleLocalAi() }, { showOnlineAiSettings() }),
            arrayOf<() -> Unit>({ showVoicePicker() }, { promptWhisperModelDownload() }, { showTtsHelp() }),
            arrayOf<() -> Unit>({ showPermissionCenter() }, { showApps() }, { openWifi() }, { openSettings(Settings.ACTION_BLUETOOTH_SETTINGS) }, { volumeDialog() }, { openSettings(Settings.ACTION_BATTERY_SAVER_SETTINGS) }),
            arrayOf<() -> Unit>({ toggleWakeMode() }, { clearConversation() }, { showAboutJarvis() })
        )
        AlertDialog.Builder(this).setTitle("تنظیمات JARVIS")
            .setItems(groups.map { it.first }.toTypedArray()) { _, group ->
                AlertDialog.Builder(this).setTitle(groups[group].first)
                    .setItems(groups[group].second) { _, item -> actions[group][item].invoke() }
                    .setNegativeButton("بازگشت") { _, _ -> showJarvisSettings() }
                    .show()
            }.setNegativeButton("بستن", null).show()
    }

    private fun showAboutJarvis() {
        AlertDialog.Builder(this).setTitle("درباره JARVIS")
            .setMessage("دستیار هوشمند فارسی با گفتگوی متنی، ورودی و خروجی صوتی، هوش محلی و اتصال آنلاین اختیاری.")
            .setPositiveButton("باشه", null).show()
    }

    private fun showTtsHelp() {
        AlertDialog.Builder(this).setTitle("فعال‌سازی صدای فارسی")
            .setMessage("اگر JARVIS متن را می‌فهمد اما صدا پخش نمی‌کند، در تنظیمات گوشی وارد بخش تبدیل متن به گفتار (Text-to-speech output) شو، موتور گفتار را انتخاب کن و داده صوتی فارسی را نصب کن. سپس JARVIS را ببند و دوباره باز کن.")
            .setPositiveButton("باز کردن تنظیمات گفتار") { _, _ ->
                try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                catch (_: Exception) { openSettings(Settings.ACTION_SETTINGS) }
            }.setNegativeButton("بستن", null).show()
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
        if (::chatScroll.isInitialized) chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun sendTextMessage() {
        val message = input.text?.toString()?.trim().orEmpty()
        if (message.isBlank()) return
        input.setText("")
        addLog("شما", message)
        addConversationMessage("user", message)
        handleCommand(message)
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

    private fun startFirstLaunchPermissionFlow() {
        if (prefs.getBoolean("initial_permissions_prompted", false)) return
        prefs.edit().putBoolean("initial_permissions_prompted", true).apply()
        firstLaunchPermissionFlowPending = true
        addLog("JARVIS", "برای فعال‌سازی امکانات صوتی و کنترلی، مجوزهای لازم را درخواست می‌کنم.")
        requestCorePermissionsNow()
    }

    private fun finishFirstLaunchPermissionFlow() {
        if (!firstLaunchPermissionFlowPending) return
        firstLaunchPermissionFlowPending = false
        updatePermissionStatus()
        showPermissionCenter()
    }

    private fun requestCorePermissionsNow() {
        val requested = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        if (android.os.Build.VERSION.SDK_INT >= 33) requested.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = requested.distinct().filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            updatePermissionStatus()
            finishFirstLaunchPermissionFlow()
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
        val notifications = android.os.Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        permissionStatusView?.text = "وضعیت فعلی:\n${if (mic) "✓" else "○"} میکروفون\n${if (camera) "✓" else "○"} دوربین / چراغ‌قوه\n${if (notifications) "✓" else "○"} اعلان‌ها\n${if (write) "✓" else "○"} تغییر تنظیمات سیستم / روشنایی\n${if (dnd) "✓" else "○"} حالت مزاحم نشو\n\nعلامت ✓ یعنی مجوز فعال است."
    }

    override fun onResume() {
        super.onResume()
        activityVisible = true
        updatePermissionStatus()
        scanGrantedDownloadFolder()
        if (!wakeReceiverRegistered) {
            ContextCompat.registerReceiver(this, wakeDetectedReceiver,
                IntentFilter(WakeWordService.ACTION_WAKE_DETECTED), ContextCompat.RECEIVER_NOT_EXPORTED)
            wakeReceiverRegistered = true
        }
        // Push-to-talk only: never start microphone capture automatically on resume.
    }

    override fun onPause() {
        activityVisible = false
        if (wakeReceiverRegistered) {
            try { unregisterReceiver(wakeDetectedReceiver) } catch (_: Exception) {}
            wakeReceiverRegistered = false
        }
        super.onPause()
    }

    private fun beginListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), requestMic)
            return
        }
        if (voiceCaptureActive.get()) {
            status.text = "در حال شنیدن؛ کمی صبر کن…"
            return
        }
        if (!WhisperEngine.isAvailable()) {
            reply("موتور تشخیص گفتار در این نسخه فعال نیست.")
            return
        }
        if (!whisper.isLoaded()) {
            if (whisperLoadActive.get()) {
                status.text = "مدل فارسی در حال آماده‌سازی است…"
                return
            }
            if (whisperModelFile.isFile && whisperModelFile.length() > 50000000L) {
                loadWhisperModel(whisperModelFile)
                return
            }
            promptWhisperModelDownload()
            return
        }
        captureAndTranscribe()
    }

    private fun captureAndTranscribe() {
        if (!activityVisible || !whisper.isLoaded()) return
        if (!voiceCaptureActive.compareAndSet(false, true)) {
            status.text = "در حال پردازش صدای قبلی هستم…"
            return
        }
        status.text = "JARVIS گوش می‌دهد؛ واضح و نزدیک صحبت کن."
        setJarvisMode(JarvisOrbView.Mode.LISTENING)
        try {
            voiceExecutor.execute {
                var recorder: AudioRecord? = null
                var recordingStarted = false
                try {
                    val rate = 16000
                    val minBuffer = AudioRecord.getMinBufferSize(
                        rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                    )
                    check(minBuffer > 0) { "ضبط صدای ۱۶ کیلوهرتز در دسترس نیست." }
                    recorder = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        rate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        maxOf(minBuffer * 2, 12800)
                    )
                    check(recorder.state == AudioRecord.STATE_INITIALIZED) { "میکروفون آماده نشد." }
                    val chunk = ShortArray(320)
                    val samples = ArrayList<Float>(rate * 10)
                    var started = false
                    var silenceMs = 0
                    var elapsedMs = 0
                    recorder.startRecording()
                    recordingStarted = true
                    while (elapsedMs < 12000 && (!started || silenceMs < 900)) {
                        val n = recorder.read(chunk, 0, chunk.size)
                        if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE ||
                            n == AudioRecord.ERROR_DEAD_OBJECT) {
                            throw IllegalStateException("ارتباط میکروفون قطع شد؛ دوباره تلاش کن.")
                        }
                        if (n <= 0) continue
                        var energy = 0.0
                        for (i in 0 until n) {
                            val sample = chunk[i].toFloat() / 32768f
                            samples.add(sample)
                            energy += chunk[i].toDouble() * chunk[i].toDouble()
                        }
                        val rms = kotlin.math.sqrt(energy / n).toInt()
                        // Higher threshold rejects room noise that Whisper otherwise hallucinates as words.
                        if (rms > 650) {
                            started = true
                            silenceMs = 0
                        } else if (started) {
                            silenceMs += n * 1000 / rate
                        }
                        elapsedMs += n * 1000 / rate
                        val level = (rms / 5000f).coerceIn(0f, 1f)
                        runOnUiThread { if (::orbView.isInitialized) orbView.setAudioLevel(level) }
                    }
                    if (!started) throw IllegalStateException("صدای واضحی دریافت نشد؛ نزدیک‌تر و بلندتر صحبت کن.")
                    if (recordingStarted && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop()
                        recordingStarted = false
                    }
                    val audio = FloatArray(samples.size) { samples[it] }
                    if (audio.size < rate * 1 / 2) throw IllegalStateException("صدای ضبط‌شده کوتاه بود؛ دوباره بگو.")
                    runOnUiThread {
                        if (::orbView.isInitialized) {
                            orbView.setAudioLevel(0f)
                            setJarvisMode(JarvisOrbView.Mode.THINKING)
                        }
                        status.text = "در حال تبدیل صدای فارسی به متن…"
                    }
                    val words = whisper.transcribe(audio).trim()
                    runOnUiThread {
                        if (!activityVisible) return@runOnUiThread
                        if (words.isNotBlank() && words.length >= 2) {
                            addLog("شما", words)
                            handleCommand(words)
                        } else {
                            status.text = "گفتار واضحی تشخیص داده نشد؛ دوباره امتحان کن."
                            setJarvisMode(JarvisOrbView.Mode.IDLE)
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        if (activityVisible) {
                            status.text = "تشخیص صدا ناموفق بود."
                            reply("مشکل میکروفون یا تبدیل گفتار: " + (e.message ?: "خطای ناشناخته"))
                        }
                    }
                } finally {
                    try {
                        if (recordingStarted && recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                            recorder.stop()
                        }
                    } catch (_: Exception) { }
                    try { recorder?.release() } catch (_: Exception) { }
                    voiceCaptureActive.set(false)
                    runOnUiThread {
                        if (::orbView.isInitialized) {
                            orbView.setAudioLevel(0f)
                            if (!::tts.isInitialized || !tts.isSpeaking) {
                                setJarvisMode(JarvisOrbView.Mode.IDLE)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            voiceCaptureActive.set(false)
            status.text = "شروع میکروفون ممکن نشد؛ دوباره تلاش کن."
            reply("شروع ضبط صدا ناموفق بود: " + (e.message ?: "خطای ناشناخته"))
        }
    }

    private fun autoLoadWhisperModel() {
        if (whisperModelFile.isFile && whisperModelFile.length() > 50000000L && WhisperEngine.isAvailable()) {
            loadWhisperModel(whisperModelFile)
        }
    }

    private fun promptWhisperModelDownload() {
        showModelDownloadDialog(
            title = "مدل چندزبانه تشخیص گفتار Whisper",
            description = "مدل چندزبانه Whisper Base Q8 حدود ۷۸ مگابایت است و گفتار فارسی و انگلیسی و زبان‌های دیگر را تشخیص می‌دهد. دانلود مستقیم یا دانلود با Chrome و ADM را انتخاب کن.",
            url = WHISPER_MODEL_URL,
            fileName = WHISPER_MODEL_FILENAME,
            isWhisper = true
        )
    }

    private fun showModelDownloadDialog(
        title: String,
        description: String,
        url: String,
        fileName: String,
        isWhisper: Boolean
    ) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        panel.addView(TextView(this).apply {
            text = description
            textSize = 14f
            setTextColor(Color.rgb(220, 226, 248))
            setLineSpacing(dp(4).toFloat(), 1f)
        })
        panel.addView(TextView(this).apply {
            text = "لینک مستقیم دانلود"
            textSize = 12f
            setTextColor(Color.rgb(154, 174, 238))
            setPadding(0, dp(14), 0, dp(5))
        })
        panel.addView(TextView(this).apply {
            text = url
            textSize = 11f
            setTextColor(Color.rgb(111, 215, 229))
            setTextIsSelectable(true)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = glass(Color.rgb(7, 12, 27), Color.rgb(45, 70, 112), 12)
        })
        panel.addView(Button(this).apply {
            text = "کپی لینک مستقیم"
            isAllCaps = false
            setOnClickListener { copyDirectModelLink(url, fileName) }
        }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(9) })
        panel.addView(Button(this).apply {
            text = "انتخاب فایل مدل از گوشی"
            isAllCaps = false
            setOnClickListener {
                if (isWhisper) selectWhisperModelFile() else selectLocalModel()
            }
        }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(5) })

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(panel)
            .setPositiveButton("باز کردن لینک دانلود") { _, _ ->
                try {
                    val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addCategory(Intent.CATEGORY_BROWSABLE) }
                    startActivity(Intent.createChooser(view, "دانلود فایل مدل با مرورگر یا ADM"))
                } catch (_: Exception) { copyDirectModelLink(url, fileName) }
            }
            .setNeutralButton("دانلود مستقیم داخل JARVIS") { _, _ ->
                if (isWhisper) enqueueWhisperDownload() else enqueueRecommendedModelDownload()
            }
            .setNegativeButton("بستن", null)
            .create()
        dialog.show()
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

    private data class ModelDocument(val uri: Uri, val name: String, val isDirectory: Boolean, val length: Long)

    private fun scanGrantedDownloadFolder() {
        val raw = prefs.getString(PREF_DOWNLOAD_TREE, null) ?: return
        val treeUri = Uri.parse(raw)
        executor.execute {
            try {
                val kind = prefs.getString("pending_external_model", "") ?: ""
                if (kind.isBlank()) return@execute
                val expected = if (kind == "qwen") MODEL_FILENAME else WHISPER_MODEL_FILENAME
                val found = findModelDocument(treeUri, expected, 4) ?: return@execute
                val target = if (kind == "qwen") localModelFile else whisperModelFile
                if (target.isFile && target.length() == found.length) return@execute
                val temp = java.io.File(target.parentFile, target.name + ".importing")
                target.parentFile?.mkdirs()
                contentResolver.openInputStream(found.uri)?.use { input ->
                    temp.outputStream().buffered(1024 * 1024).use { output -> input.copyTo(output, 1024 * 1024) }
                } ?: throw IllegalStateException("فایل دانلودشده قابل خواندن نیست")
                if (kind == "qwen") {
                    require(temp.length() > 1_000_000_000L) { "فایل GGUF هنوز کامل دانلود نشده است" }
                    temp.inputStream().use { stream ->
                        val magic = ByteArray(4)
                        require(stream.read(magic) == 4 && String(magic, Charsets.US_ASCII) == "GGUF") { "فایل دانلودشده GGUF نیست" }
                    }
                } else require(temp.length() > 50_000_000L) { "فایل Whisper هنوز کامل دانلود نشده است" }
                if (target.exists()) target.delete()
                check(temp.renameTo(target)) { "ذخیره فایل مدل ناموفق بود" }
                prefs.edit().remove("pending_external_model").apply()
                if (kind == "qwen") {
                    prefs.edit().putString("local_model_path", target.absolutePath).putBoolean("local_ai_enabled", true).apply()
                    runOnUiThread { reply("مدل Qwen از پوشه دانلودها به‌صورت خودکار وارد شد؛ در حال فعال‌سازی…"); loadLocalModel(target) }
                } else runOnUiThread { reply("مدل Whisper از پوشه دانلودها به‌صورت خودکار وارد شد؛ در حال بارگذاری…"); loadWhisperModel(target) }
            } catch (e: Exception) {
                runOnUiThread { status.text = "واردسازی خودکار مدل: ${e.message ?: "در انتظار تکمیل دانلود"}" }
            }
        }
    }

    private fun findModelDocument(treeUri: Uri, expectedName: String, depth: Int): ModelDocument? {
        if (depth < 0) return null
        val parentId = try { DocumentsContract.getTreeDocumentId(treeUri) } catch (_: Exception) { return null }
        return findModelDocumentInFolder(treeUri, parentId, expectedName, depth)
    }

    private fun findModelDocumentInFolder(treeUri: Uri, parentId: String, expectedName: String, depth: Int): ModelDocument? {
        if (depth < 0) return null
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idCol)
                val name = cursor.getString(nameCol) ?: ""
                val mime = cursor.getString(mimeCol) ?: ""
                val size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else 0L
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                if (mime != DocumentsContract.Document.MIME_TYPE_DIR && name == expectedName)
                    return ModelDocument(uri, name, false, size)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val nested = findModelDocumentInFolder(treeUri, id, expectedName, depth - 1)
                    if (nested != null) return nested
                }
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
        voiceExecutor.execute {
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
        if (!whisperLoadActive.compareAndSet(false, true)) {
            status.text = "مدل فارسی در حال آماده‌سازی است…"
            return
        }
        status.text = "در حال بارگذاری مدل فارسی Whisper…"
        try {
            voiceExecutor.execute {
                try {
                    whisper.load(file.absolutePath)
                    runOnUiThread {
                        status.text = "مدل تشخیص فارسی آماده است."
                        if (activityVisible) captureAndTranscribe()
                    }
                } catch (e: Exception) {
                    runOnUiThread { reply("بارگذاری مدل گفتار ناموفق بود: " + (e.message ?: "خطا")) }
                } finally {
                    whisperLoadActive.set(false)
                }
            }
        } catch (e: Exception) {
            whisperLoadActive.set(false)
            reply("صف پردازش صوتی آماده نیست؛ برنامه را دوباره باز کن.")
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
        val q = normalize(raw).trim()
        if (q == "جارویس" || q == "هی جارویس" || q == "سلام جارویس" ||
            q == "جارویس بیدار شو" || q == "جارویس گوش کن") {
            reply("در خدمتم")
            return
        }
        val smsRequest = parseSmsRequest(raw)
        if (smsRequest != null) { beginSmsFlow(smsRequest.first, smsRequest.second); return }
        if (q.contains("پیام بده") || q.contains("پیامک بده") || q.contains("اس ام اس بده")) {
            reply("برای پیام‌دادن بگو: به نام مخاطب پیام بده، بنویس متن پیام"); return
        }
        val requestedApp = extractAppLaunchTarget(q)
        if (requestedApp != null) {
            if (requestedApp.isBlank()) reply("نام برنامه را هم بگو")
            else launchAppByName(requestedApp)
            return
        }
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
            else -> askLocal(raw)
        }
    }


    // SMS is deliberately handed to the system messaging app; JARVIS never sends silently.
    private fun parseSmsRequest(raw: String): Pair<String, String>? {
        val q = normalize(raw).replace(Regex("[،,؛;]+"), " ").replace(Regex("\\s+"), " ").trim()
        val recipientMatch = Regex("""به\s+(.+?)\s+(?:پیام\s*بده|پیامک\s*بده|اس\s*ام\s*اس\s*بده)""").find(q)
            ?: return null
        val recipient = recipientMatch.groupValues[1].trim().trim(',', '،', '.', '!')
        val bodyMatch = Regex("""(?:بنویس|متن(?:ش)?(?:\s+این)?(?:\s+باشه)?|بگو)\s*[:：،,-]*\s*(.+)$""").find(q)
        val body = bodyMatch?.groupValues?.get(1)?.trim().orEmpty()
        if (recipient.isBlank() || body.isBlank()) {
            reply("نام مخاطب یا متن پیام کامل نیست. مثال: به کوثر پیام بده، بنویس سلام")
            return Pair("", "")
        }
        return Pair(recipient, body)
    }

    private fun beginSmsFlow(recipient: String, body: String) {
        if (recipient.isBlank() || body.isBlank()) return
        pendingSmsRecipient = recipient
        pendingSmsBody = body
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), requestSmsContacts)
            reply("برای پیدا کردن مخاطب، اجازه دسترسی به مخاطبین را تأیید کن.")
            return
        }
        openSmsForContact(recipient, body)
    }

    private fun openSmsForContact(recipient: String, body: String) {
        data class ContactMatch(val name: String, val number: String, val score: Int)
        val matches = mutableListOf<ContactMatch>()
        try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")?.use { cursor ->
                val nameCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberCol = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol).orEmpty()
                    val number = cursor.getString(numberCol).orEmpty()
                    val normalizedName = normalize(name).replace(Regex("\\s+"), "")
                    val target = normalize(recipient).replace(Regex("\\s+"), "")
                    val score = when {
                        normalizedName == target -> 100
                        normalizedName.startsWith(target) || target.startsWith(normalizedName) -> 80
                        normalizedName.contains(target) || target.contains(normalizedName) -> 60
                        else -> 0
                    }
                    if (score > 0 && number.isNotBlank()) matches.add(ContactMatch(name, number, score))
                }
            }
        } catch (e: Exception) {
            reply("خواندن مخاطبین ممکن نشد: " + (e.message ?: "خطای دسترسی"))
            return
        }
        val best = matches.sortedByDescending { it.score }.distinctBy { it.number }
        if (best.isEmpty()) {
            reply("مخاطبی با نام «" + recipient + "» پیدا نکردم. نام ذخیره‌شده را بررسی کن.")
            return
        }
        val secondScore = best.getOrNull(1)?.score ?: 0
        if (best.size > 1 && best[0].score <= secondScore) {
            val labels = best.take(8).map { it.name + "  •  " + it.number }.toTypedArray()
            AlertDialog.Builder(this).setTitle("کدام مخاطب؟")
                .setItems(labels) { _, which -> launchSmsComposer(best[which].number, body, best[which].name) }
                .setNegativeButton("لغو", null).show()
            return
        }
        val chosen = best.first()
        launchSmsComposer(chosen.number, body, chosen.name)
    }

    private fun launchSmsComposer(number: String, body: String, contactName: String) {
        try {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).apply {
                putExtra("sms_body", body)
            }
            if (intent.resolveActivity(packageManager) == null) {
                reply("برنامه پیامک پیش‌فرض روی گوشی پیدا نشد.")
                return
            }
            startActivity(intent)
            reply("پیام برای " + contactName + " آماده شد. متن را بررسی کن و دکمه ارسال را خودت بزن.")
        } catch (e: Exception) {
            reply("بازکردن صفحه پیامک ممکن نشد: " + (e.message ?: "خطا"))
        }
    }

    private fun extractAppLaunchTarget(command: String): String? {
        var q = command.trim()
            .replace(Regex("^(لطفا|لطفاً|خواهشا|خواهشاً|جارویس|jarvis)[،, ]+"), "")
            .replace(Regex("\\s+"), " ")
        val openVerbs = listOf("باز کن", "بازکن", "اجرا کن", "اجراکن", "راه اندازی کن", "راه‌اندازی کن", "راه بنداز", "بازش کن", "open", "launch", "start", "run")
        for (verb in openVerbs) {
            if (q.startsWith(verb + " ")) return q.removePrefix(verb).trim()
            if (q == verb) return ""
        }
        val endings = listOf(" را برام باز کن", " رو برام باز کن", " را باز کن", " رو باز کن",
            " را اجرا کن", " رو اجرا کن", " باز کن", " بازکن", " اجرا کن", " راه بنداز", " رو بازش کن", " را بازش کن")
        for (ending in endings) if (q.endsWith(ending)) return q.removeSuffix(ending).trim()
        return null
    }

    private fun appAliases(packageName: String): List<String> {
        val aliases = mapOf(
            "com.instagram.android" to listOf("instagram", "insta", "اینستاگرام", "اینستا", "اینستا گرام", "اینستا‌گرام"),
            "com.whatsapp" to listOf("whatsapp", "واتساپ", "واتس اپ", "واتسآپ"),
            "org.telegram.messenger" to listOf("telegram", "تلگرام"),
            "com.google.android.youtube" to listOf("youtube", "you tube", "یوتیوب", "یو تیوب"),
            "com.android.chrome" to listOf("chrome", "google chrome", "کروم", "گوگل کروم"),
            "com.google.android.gm" to listOf("gmail", "جیمیل", "جی میل"),
            "com.google.android.apps.maps" to listOf("google maps", "maps", "نقشه", "گوگل مپ", "مپس"),
            "com.google.android.apps.photos" to listOf("google photos", "photos", "گوگل فوتوز", "عکس ها"),
            "com.spotify.music" to listOf("spotify", "اسپاتیفای", "اسپاتی فای"),
            "com.google.android.apps.messaging" to listOf("messages", "google messages", "پیامک", "پیام ها"),
            "com.google.android.dialer" to listOf("phone", "dialer", "تلفن", "تماس", "شماره گیر"),
            "com.google.android.contacts" to listOf("contacts", "مخاطبین", "دفترچه تلفن"),
            "com.facebook.katana" to listOf("facebook", "فیسبوک", "فیس بوک"),
            "com.zhiliaoapp.musically" to listOf("tiktok", "تیک تاک", "تیکتاک"),
            "com.snapchat.android" to listOf("snapchat", "اسنپ چت", "اسنپ‌چت"),
            "com.netflix.mediaclient" to listOf("netflix", "نتفلیکس"),
            "com.google.android.googlequicksearchbox" to listOf("google", "گوگل", "دستیار گوگل")
        )
        return aliases[packageName].orEmpty()
    }

    private fun launchAppByName(name: String) {
        val target = normalize(name).trim().replace(Regex("\\s+"), " ")
        if (target.isBlank()) { reply("نام برنامه را هم بگو"); return }
        val launcher = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        val candidates = packageManager.queryIntentActivities(launcher, 0)
        data class AppMatch(val info: android.content.pm.ResolveInfo, val score: Int)
        val matches = candidates.mapNotNull { info ->
            val label = normalize(info.loadLabel(packageManager).toString()).trim()
            val packageName = info.activityInfo.packageName.lowercase(Locale.ROOT)
            val packageWords = packageName.substringAfterLast('.').replace(Regex("[._-]"), " ")
            val allNames = (listOf(label, packageName, packageWords) + appAliases(packageName)).map { normalize(it).trim() }.filter { it.isNotBlank() }.distinct()
            val score = when {
                allNames.any { it == target } -> 100
                allNames.any { it.replace(" ", "") == target.replace(" ", "") } -> 95
                allNames.any { it.startsWith(target) || target.startsWith(it) } -> 80
                allNames.any { it.contains(target) || target.contains(it) } -> 60
                else -> 0
            }
            if (score > 0) AppMatch(info, score) else null
        }.sortedByDescending { it.score }
        val match = matches.firstOrNull()
        if (match == null) {
            reply("برنامه «$name» را در برنامه‌های نصب‌شده پیدا نکردم. نام فارسی یا انگلیسی آن را امتحان کن.")
            return
        }
        val label = match.info.loadLabel(packageManager).toString()
        val launch = packageManager.getLaunchIntentForPackage(match.info.activityInfo.packageName)
        if (launch != null) {
            reply("$label را باز می‌کنم")
            try { startActivity(launch) } catch (_: Exception) { reply("باز کردن $label ممکن نشد") }
        } else reply("برای $label میانبر اجرای مستقیم پیدا نکردم")
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
        if (localModelFile.exists() && isValidGguf(localModelFile, 1_000_000_000L)) {
            if (!localAi.isLoaded()) loadLocalModel(localModelFile)
            else reply("مغز هوشمند JARVIS از قبل فعال است.")
            return
        }
        showModelDownloadDialog(
            title = "دریافت مغز هوشمند JARVIS",
            description = "مدل Qwen3-1.7B با حجم حدود ۱٫۲۸ گیگابایت است. لینک مستقیم فایل رسمی Hugging Face باز می‌شود. برای دانلود کامل فضای خالی کافی داشته باش و نام فایل را تغییر نده. پس از دانلود، فایل را از گزینه انتخاب فایل مدل وارد کن.",
            url = MODEL_URL,
            fileName = MODEL_FILENAME,
            isWhisper = false
        )
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
                .setDescription("دریافت مدل Qwen3-1.7B؛ حدود ۱.۲۸ گیگابایت")
                .setMimeType("application/octet-stream")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
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
        if (successful && isValidGguf(localModelFile, 1_000_000_000L)) {
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
                require(file.length() <= 1_800_000_000L) { "این مدل برای حافظه گوشی سنگین است؛ مدل Qwen3-1.7B را انتخاب کن." }
                localAi.load(file.absolutePath, 2, 1024)
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
        val savedModelIsSafe = savedFile != null && savedFile.isFile && savedFile.length() <= 1_800_000_000L && isValidGguf(savedFile)
        val file = when {
            isValidGguf(localModelFile, 1_000_000_000L) -> localModelFile
            savedModelIsSafe == true -> savedFile!!
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
            .setMessage("یک فایل GGUF سازگار انتخاب کن. مدل اصلی پیشنهادی JARVIS، Qwen3-1.7B با کوانت Q4_K_M است. مدل‌های دیگر هم در صورت سازگاری با llama.cpp قابل آزمایش‌اند.")
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
                require(temporaryFile.length() in (100L * 1024L * 1024L)..1_800_000_000L) { "برای جلوگیری از کمبود حافظه، مدل GGUF باید حداکثر ۱٫۸ گیگابایت باشد؛ مدل پیشنهادی JARVIS حدود ۱٫۲۸ گیگابایت است." }
                temporaryFile.inputStream().use { stream ->
                    val magic = ByteArray(4)
                    require(stream.read(magic) == 4 && String(magic, Charsets.US_ASCII) == "GGUF") { "فایل انتخاب‌شده فرمت GGUF ندارد." }
                }
                if (localModelFile.exists()) localModelFile.delete()
                check(temporaryFile.renameTo(localModelFile)) { "ذخیره نهایی فایل مدل ناموفق بود." }
                runOnUiThread { status.text = "فایل ذخیره شد؛ در حال بارگذاری موتور هوش مصنوعی…" }
                check(LocalAiEngine.isNativeAvailable()) { "کتابخانه موتور llama.cpp در APK وجود ندارد. پروژه باید با NDK کامپایل شود." }
                localAi.load(localModelFile.absolutePath, 2, 1024)
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
            val modelFile = if (savedFile != null && savedFile.isFile && savedFile.length() <= 1_800_000_000L && isValidGguf(savedFile)) savedFile else localModelFile
            if (modelFile.isFile && isValidGguf(modelFile) && !localAi.isLoaded()) {
                loadLocalModel(modelFile)
            } else if (localAi.isLoaded()) reply("هوش مصنوعی محلی فعال است.") else promptRecommendedModelDownload()
        } else {
            unloadLocalModel()
        }
    }

    private fun showOnlineAiSettings() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        val endpoint = EditText(this).apply {
            hint = "آدرس API"
            setText(agentCore.endpoint())
            textSize = 13f
            setSingleLine(false)
        }
        val key = EditText(this).apply {
            hint = "کلید API (اختیاری برای سرویس‌های بدون کلید)"
            setText(agentCore.apiKey())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            textSize = 13f
        }
        val model = EditText(this).apply {
            hint = "مدل رایگان Groq"
            setText(agentCore.model())
            textSize = 13f
        }
        panel.addView(TextView(this).apply {
            text = "پیشنهاد رایگان: GroqCloud\\nآدرس و مدل از قبل تنظیم شده‌اند. فقط کلید API شخصی‌ات را وارد کن. کلید فقط روی همین گوشی ذخیره می‌شود و داخل APK قرار نمی‌گیرد."
            setTextColor(Color.LTGRAY); textSize = 13f
        })
        panel.addView(TextView(this).apply {
            text = "دریافت کلید رایگان Groq: console.groq.com/keys"
            setTextColor(Color.CYAN); textSize = 13f
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys"))) }
                catch (_: Exception) { reply("برای دریافت کلید، آدرس console.groq.com/keys را باز کن.") }
            }
        })
        panel.addView(endpoint); panel.addView(key); panel.addView(model)
        AlertDialog.Builder(this).setTitle("موتور آنلاین JARVIS")
            .setView(panel)
            .setPositiveButton("ذخیره") { _, _ ->
                agentCore.saveConfig(endpoint.text.toString().trim(), key.text.toString(), model.text.toString().trim())
                reply(if (agentCore.isOnlineConfigured()) "تنظیمات موتور آنلاین ذخیره شد؛ ابتدا آنلاین و در صورت خطا محلی پاسخ می‌دهم." else "اتصال آنلاین غیرفعال شد؛ JARVIS از موتور محلی استفاده می‌کند.")
            }
            .setNeutralButton("پاک‌کردن اتصال") { _, _ ->
                agentCore.saveConfig("", "", "")
                reply("اطلاعات اتصال آنلاین پاک شد؛ حالت محلی فعال است.")
            }
            .setNegativeButton("بستن", null).show()
    }

    private fun askLocal(prompt: String) {
        val onlineConfigured = agentCore.isOnlineConfigured()
        val localEnabled = prefs.getBoolean("local_ai_enabled", true)
        if (!onlineConfigured && (!localEnabled || !localAi.isLoaded())) {
            reply("برای گفت‌وگو باید اتصال هوش آنلاین را در تنظیمات وارد کنی یا مدل محلی را دانلود و فعال کنی.")
            return
        }
        status.text = if (onlineConfigured) "در حال اتصال امن به دستیار آنلاین…" else "در حال فکر کردن روی گوشی…"
        setJarvisMode(JarvisOrbView.Mode.THINKING)
        executor.execute {
            try {
                val historyText = buildString {
                    val first = (conversation.length() - 8).coerceAtLeast(0)
                    for (i in first until conversation.length()) {
                        val item = conversation.optJSONObject(i) ?: continue
                        val role = item.optString("role", "user")
                        val content = item.optString("content", "").takeLast(900)
                        append(if (role == "assistant") "JARVIS: " else "کاربر: ")
                        append(content).append("\\n")
                    }
                }
                val answer = if (onlineConfigured) {
                    try {
                        agentCore.askOnline(
                            "تاریخچه کوتاه گفتگو:\\n$historyText\\nپیام جدید کاربر:\\n${prompt.take(4000)}"
                        ).trim()
                    } catch (onlineError: Exception) {
                        if (!localEnabled || !localAi.isLoaded()) throw onlineError
                        runOnUiThread { status.text = "اتصال آنلاین ناموفق؛ تلاش با مدل محلی…" }
                        val localPrompt = agentCore.buildLocalPrompt(prompt, conversation)
                        localAi.generate(localPrompt, 220).trim()
                    }
                } else {
                    val localPrompt = agentCore.buildLocalPrompt(prompt, conversation)
                    localAi.generate(localPrompt, 220).trim()
                }
                val cleaned = answer.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
                if (cleaned.isBlank()) throw IllegalStateException("پاسخ دستیار خالی بود.")
                runOnUiThread {
                    val action = parseAiAction(cleaned)
                    if (action != null) {
                        val command = action.optString("command", "").trim()
                        if (isAllowedAiCommand(command)) {
                            val message = "برای اجرای این فرمان تأیید تو لازم است: $command"
                            addLog("JARVIS", message)
                            addConversationMessage("assistant", message)
                            confirmAiAction(command)
                        } else {
                            val message = "این فرمان در فهرست عملیات امن و پشتیبانی‌شده نیست؛ اجرا نشد."
                            addLog("JARVIS", message)
                            addConversationMessage("assistant", message)
                            speak(message)
                        }
                    } else {
                        addLog("JARVIS", cleaned)
                        addConversationMessage("assistant", cleaned)
                        status.text = if (onlineConfigured) "پاسخ دستیار آنلاین آماده شد" else "پاسخ محلی آماده شد"
                        speak(cleaned)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { reply("پاسخ‌گویی ناموفق بود: ${e.message ?: "خطای ناشناخته"}") }
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
    private fun speak(message: String) {
        if (!ttsReady) { pendingSpeech = message; return }
        if (::orbView.isInitialized && ::tts.isInitialized) {
            setJarvisMode(JarvisOrbView.Mode.SPEAKING)
            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "jarvis_reply")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestMic && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) beginListening()
        else if (requestCode == requestMic) reply("برای فرمان صوتی باید مجوز میکروفون را بدهی")
        if (requestCode == 104) {
            if (android.os.Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) startWakeServiceNow()
            else reply("مجوز اعلان داده نشد؛ برای نمایش اعلان دائمی باید آن را از تنظیمات مجاز کنی")
        }
        if (requestCode == requestSmsContacts) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) openSmsForContact(pendingSmsRecipient, pendingSmsBody)
            else reply("بدون اجازه مخاطبین نمی‌توانم نام مخاطب را پیدا کنم.")
            return
        }
        if (requestCode == requestCorePermissions) {
            updatePermissionStatus()
            val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            reply("نتیجه مجوزها بررسی شد. مجوزهای ویژه را می‌توانی از مرکز مجوزها فعال کنی.")
            finishFirstLaunchPermissionFlow()
        }
    }

    override fun onInit(code: Int) {
        if (code == TextToSpeech.SUCCESS) {
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    runOnUiThread { if (::orbView.isInitialized) { setJarvisMode(JarvisOrbView.Mode.SPEAKING); orbView.setAudioLevel(0.15f) } }
                }
                override fun onDone(utteranceId: String?) {
                    runOnUiThread {
                        if (::orbView.isInitialized) { orbView.setAudioLevel(0f); setJarvisMode(JarvisOrbView.Mode.IDLE) }
                        // Do not reopen the microphone after speech: prevents JARVIS hearing its own TTS.
                    }
                }
                override fun onError(utteranceId: String?) {
                    runOnUiThread { if (::orbView.isInitialized) { orbView.setAudioLevel(0f); setJarvisMode(JarvisOrbView.Mode.IDLE) } }
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
            val persianVoices = tts.voices?.filter { it.locale.language == "fa" }.orEmpty()
            val savedVoice = prefs.getString("tts_voice", "")
            val selectedPersianVoice = persianVoices.firstOrNull { it.name == savedVoice }
                ?: persianVoices.maxByOrNull { it.quality }
            if (selectedPersianVoice != null) tts.setVoice(selectedPersianVoice)
            // Do not require an explicitly enumerated Persian Voice: some Android TTS engines
            // support fa-IR through setLanguage while exposing an empty voices list.
            ttsReady = result >= TextToSpeech.LANG_AVAILABLE
            if (!ttsReady) {
                status.text = "صدای فارسی در موتور گفتار گوشی موجود نیست؛ از تنظیمات، داده گفتار فارسی را نصب کن."
            } else if (persianVoices.isEmpty()) {
                status.text = "موتور گفتار آماده است؛ اگر خروجی فارسی پخش نشد، صدای فارسی را از تنظیمات گوشی نصب کن."
            }
            if (ttsReady) pendingSpeech?.let { queued -> pendingSpeech = null; speak(queued) }
        }
    }

    override fun onDestroy() {
        if (modelReceiverRegistered) {
            try { unregisterReceiver(modelDownloadReceiver) } catch (_: Exception) { }
            modelReceiverRegistered = false
        }
        if (wakeReceiverRegistered) {
            try { unregisterReceiver(wakeDetectedReceiver) } catch (_: Exception) {}
            wakeReceiverRegistered = false
        }
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
        // Queue native cleanup behind any active inference to avoid freeing the model mid-generation.
        try {
            executor.execute { try { localAi.unload() } catch (_: Exception) { } }
            executor.shutdown()
            voiceExecutor.shutdownNow()
        } catch (_: Exception) {
            executor.shutdownNow()
        }
        super.onDestroy()
    }

    companion object {
        private const val WHISPER_MODEL_FILENAME = "ggml-base-q8_0.bin"
        private const val WHISPER_MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q8_0.bin"
        private const val KEY_WHISPER_DOWNLOAD_ID = "jarvis_whisper_download_id"
        private const val MODEL_FILENAME = "Qwen_Qwen3-1.7B-Q4_K_M.gguf"
        private const val MODEL_URL = "https://huggingface.co/bartowski/Qwen_Qwen3-1.7B-GGUF/resolve/main/Qwen_Qwen3-1.7B-Q4_K_M.gguf"
        private const val KEY_MODEL_DOWNLOAD_ID = "jarvis_model_download_id_17b"
    }

}
