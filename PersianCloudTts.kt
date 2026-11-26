package com.chtai.assistant

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Optional Persian cloud TTS. Credentials are stored locally and never embedded in the APK. */
class PersianCloudTts(context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_persian_tts", Context.MODE_PRIVATE)
    fun apiKey(): String = prefs.getString("api_key", "") ?: ""
    fun voiceId(): String {
        // Adam is used as the default male voice. Persian is synthesized with
        // Eleven v3 (Persian-capable), not the phone's missing fa-IR voice pack.
        val saved = prefs.getString("voice_id", "").orEmpty().trim()
        return saved.ifBlank { "pNInz6obpgDQGcFmaJgB" }
    }
    fun saveConfig(key: String, voice: String) {
        prefs.edit().putString("api_key", key.trim()).putString("voice_id", voice.trim()).apply()
    }
    fun isConfigured(): Boolean = apiKey().isNotBlank() && voiceId().isNotBlank()

    fun synthesize(text: String): File {
        require(isConfigured()) { "کلید و شناسه صدای ElevenLabs تنظیم نشده است." }
        val safeText = text.trim().take(2400)
        require(safeText.isNotBlank()) { "متن برای گفتار خالی است." }
        val voice = voiceId()
        require(voice.matches(Regex("[A-Za-z0-9_-]{8,80}"))) { "شناسه Voice ID معتبر نیست." }
        val url = URL("https://api.elevenlabs.io/v1/text-to-speech/$voice?output_format=mp3_44100_128")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 20000
            doOutput = true
            useCaches = false
            setRequestProperty("xi-api-key", apiKey())
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "audio/mpeg")
        }
        try {
            val body = JSONObject()
                .put("text", safeText)
                .put("model_id", "eleven_v3")
                .put("language_code", "fa")
                .put("voice_settings", JSONObject().put("stability", 0.68).put("similarity_boost", 0.82).put("style", 0.35).put("speed", 0.92).put("use_speaker_boost", true))
                .toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val message = try { JSONObject(detail).optJSONObject("detail")?.optString("message") } catch (_: Exception) { null }
                val safeError = when (code) {
                    401 -> "کلید سرویس صدا نامعتبر است؛ کلید ElevenLabs را در تنظیمات بررسی کن."
                    403 -> "دسترسی سرویس صدا رد شد (403). احتمالاً کلید یا سطح دسترسی حساب اجازه ساخت صدا نمی‌دهد؛ کلید را بررسی کن."
                    429 -> "سهمیه یا محدودیت درخواست سرویس صدا تمام شده؛ کمی بعد دوباره تلاش کن."
                    else -> "سرویس صدای فارسی پاسخ مناسبی نداد (کد $code)."
                }
                throw IllegalStateException(safeError)
            }
            val file = File.createTempFile("jarvis-fa-", ".mp3")
            connection.inputStream.use { input -> file.outputStream().buffered(65536).use { output -> input.copyTo(output) } }
            check(file.length() > 1024L) { "فایل صوتی دریافتی خالی است." }
            return file
        } finally { connection.disconnect() }
    }
}
