package com.chtai.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * JARVIS assistant orchestration helpers.
 * Online mode is optional and only talks to a user-configured OpenAI-compatible endpoint.
 */
class JarvisAgentCore(context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_agent", Context.MODE_PRIVATE)

    // GroqCloud offers a free developer tier and an OpenAI-compatible Chat Completions API.
    // The user must create and enter their own API key; no secret is bundled in the APK.
    fun endpoint(): String = prefs.getString("endpoint", "https://api.groq.com/openai/v1") ?: "https://api.groq.com/openai/v1"
    fun apiKey(): String = prefs.getString("api_key", "") ?: ""
    fun model(): String = prefs.getString("model", "openai/gpt-oss-20b") ?: "openai/gpt-oss-20b"

    fun saveConfig(endpoint: String, key: String, model: String) {
        prefs.edit()
            .putString("endpoint", endpoint.trim().ifBlank { "https://api.groq.com/openai/v1" })
            .putString("api_key", key.trim())
            .putString("model", model.trim().ifBlank { "openai/gpt-oss-20b" })
            .apply()
    }

    private fun normalizedEndpoint(): String {
        val raw = endpoint().trim().trimEnd('/')
        require(raw.isNotBlank()) { "آدرس API وارد نشده است." }
        val parsed = try { URL(raw) } catch (_: Exception) {
            throw IllegalArgumentException("آدرس API معتبر نیست؛ آدرس کامل HTTPS را وارد کن.")
        }
        require(parsed.protocol.equals("https", true) && !parsed.host.isNullOrBlank()) {
            "آدرس API باید با https:// شروع شود و دامنه معتبر داشته باشد."
        }
        return when {
            raw.endsWith("/chat/completions", true) -> raw
            raw.endsWith("/v1", true) -> "$raw/chat/completions"
            else -> "$raw/v1/chat/completions"
        }
    }

    fun isOnlineConfigured(): Boolean {
        return try {
            normalizedEndpoint()
            apiKey().isNotBlank() && model().isNotBlank() && model().length <= 200
        } catch (_: Exception) {
            false
        }
    }

    fun askOnline(userText: String, maxTokens: Int = 384, connectTimeoutMs: Int = 8000, readTimeoutMs: Int = 18000): String {
        require(userText.isNotBlank()) { "پیام خالی است." }
        val target = normalizedEndpoint()
        val modelName = model().trim()
        require(modelName.isNotBlank() && modelName.length <= 200) { "نام مدل معتبر نیست." }

        val connection = (URL(target).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs.coerceIn(1000, 30000)
            readTimeout = readTimeoutMs.coerceIn(1000, 60000)
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Connection", "close")
            if (URL(target).host.endsWith(".p.rapidapi.com", ignoreCase = true)) {
                if (apiKey().isNotBlank()) setRequestProperty("X-RapidAPI-Key", apiKey().trim())
                setRequestProperty("X-RapidAPI-Host", URL(target).host)
            } else if (apiKey().isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${apiKey().trim()}")
            }
        }

        try {
            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content",
                    "تو جارویس هستی؛ دستیار فارسی‌زبان و خودمونی کاربر. همیشه فارسی روان و محاوره‌ای جواب بده؛ مثل «چیکار می‌تونم برات انجام بدم؟»، «واسه چی می‌خوای؟»، «یه لحظه صبر کن» و «انجام شد، دیگه چی؟». رسمی و کتابی حرف نزن، ولی محترمانه و واضح بمون و شوخی را زیاده‌روی نکن. پاسخ را با کلمات انگلیسی مثل Sure، Okay، Hello یا عبارت‌های مشابه شروع نکن؛ مگر اینکه کاربر مشخصاً پاسخ انگلیسی بخواهد. گفتار محاوره‌ای، مترادف‌ها، غلط املایی، خطای تبدیل گفتار به متن، چسبیده یا جدا نوشته‌شدن واژه‌ها و جاافتادن حرف آخر کلمه را با کمک بافت جمله درک کن. فقط وقتی واقعاً مبهم است یک سؤال کوتاه بپرس. به پرسش‌های عمومی، توضیح، برنامه‌ریزی، نگارش، ترجمه، محاسبه و عیب‌یابی پاسخ بده. هرگز ادعا نکن کاری روی گوشی انجام شده مگر برنامه موفقیت آن را تأیید کرده باشد. رمز یا اطلاعات محرمانه درخواست نکن."))
                .put(JSONObject().put("role", "user").put("content", userText.take(12000)))
            val body = JSONObject()
                .put("model", modelName)
                .put("messages", messages)
                .put("temperature", 0.35)
                .put("max_tokens", maxTokens.coerceIn(64, 768))
                .put("stream", false)
                .toString()

            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (code !in 200..299) {
                val detail = try {
                    JSONObject(response).optJSONObject("error")?.optString("message")
                        ?.takeIf { it.isNotBlank() }
                } catch (_: Exception) { null }
                val safeDetail = (detail ?: response).replace(Regex("\\s+"), " ").take(280)
                throw IllegalStateException("خطای API ($code)${if (safeDetail.isNotBlank()) ": $safeDetail" else ""}")
            }

            val json = try { JSONObject(response) } catch (_: Exception) {
                throw IllegalStateException("پاسخ API قابل‌خواندن نیست؛ آدرس باید به Chat Completions سازگار باشد.")
            }
            val message = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            val content = message?.opt("content")
            val answer = when (content) {
                is String -> content.trim()
                is JSONArray -> buildString {
                    for (i in 0 until content.length()) {
                        val part = content.optJSONObject(i)
                        val text = part?.optString("text").orEmpty()
                        if (text.isNotBlank()) append(text)
                    }
                }.trim()
                else -> ""
            }
            if (answer.isBlank()) {
                val apiMessage = json.optJSONObject("error")?.optString("message").orEmpty()
                throw IllegalStateException(apiMessage.ifBlank { "پاسخ سرویس آنلاین خالی بود یا قالب آن پشتیبانی نمی‌شود." }.take(300))
            }
            return answer.take(12000)
        } catch (e: Exception) {
            when (e) {
                is IllegalStateException, is IllegalArgumentException -> throw e
                else -> throw IllegalStateException(
                    when (e) {
                        is java.net.UnknownHostException -> "سرور API پیدا نشد؛ اینترنت یا دامنه را بررسی کن."
                        is java.net.SocketTimeoutException -> "اتصال به API بیش از حد طول کشید؛ دوباره تلاش کن."
                        is javax.net.ssl.SSLException -> "اتصال امن HTTPS برقرار نشد."
                        else -> "ارتباط با API ناموفق بود: ${e.message ?: "خطای شبکه"}"
                    }, e
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    fun askOnlineImage(userText: String, jpegBase64: String): String {
        require(jpegBase64.isNotBlank()) { "تصویر خالی است." }
        require(isOnlineConfigured()) { "برای تحلیل تصویر، ابتدا اتصال هوش آنلاین را تنظیم کن." }
        val target = normalizedEndpoint()
        val host = URL(target).host
        // Groq's default GPT-OSS model is text-only; use its documented vision model
        // for image requests without changing the user's saved chat model.
        val modelName = if (host.endsWith("groq.com", true) && model() == "openai/gpt-oss-20b")
            "qwen/qwen3.8-27b" else model()
        val connection = (URL(target).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 60000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            if (host.endsWith(".p.rapidapi.com", true)) {
                setRequestProperty("X-RapidAPI-Key", apiKey().trim())
                setRequestProperty("X-RapidAPI-Host", host)
            } else setRequestProperty("Authorization", "Bearer ${apiKey().trim()}")
        }
        try {
            val content = JSONArray()
                .put(JSONObject().put("type", "text").put("text", userText.take(3000)))
                .put(JSONObject().put("type", "image_url").put("image_url",
                    JSONObject().put("url", "data:image/jpeg;base64,$jpegBase64")))
            val messages = JSONArray().put(JSONObject().put("role", "user").put("content", content))
            val body = JSONObject().put("model", modelName).put("messages", messages)
                .put("temperature", 0.25).put("max_tokens", 600).toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = try { JSONObject(response).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                throw IllegalStateException("تحلیل تصویر از سرویس آنلاین ناموفق بود ($code): ${(detail ?: response).take(260)}")
            }
            val answer = JSONObject(response).optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content").orEmpty().trim()
            if (answer.isBlank()) throw IllegalStateException("مدل بینایی پاسخ متنی برنگرداند.")
            return answer.take(10000)
        } finally { connection.disconnect() }
    }

    fun transcribeAudio(bytes: ByteArray, mimeType: String, fileName: String): String {
        require(bytes.isNotEmpty()) { "فایل صوتی خالی است." }
        require(bytes.size <= 25 * 1024 * 1024) { "حجم فایل صوتی بیشتر از ۲۵ مگابایت است." }
        require(isOnlineConfigured()) { "برای تبدیل ویس به متن، ابتدا اتصال هوش آنلاین را تنظیم کن." }
        val base = normalizedEndpoint().removeSuffix("/chat/completions")
        val target = URL("$base/audio/transcriptions")
        val boundary = "JarvisBoundary${System.nanoTime()}"
        val connection = (target.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 90000
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
            val host = target.host
            if (host.endsWith(".p.rapidapi.com", true)) {
                setRequestProperty("X-RapidAPI-Key", apiKey().trim())
                setRequestProperty("X-RapidAPI-Host", host)
            } else setRequestProperty("Authorization", "Bearer ${apiKey().trim()}")
        }
        try {
            val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "voice.m4a" }
            connection.outputStream.use { output ->
                fun field(name: String, value: String) {
                    output.write("--$boundary\\r\\n".toByteArray())
                    output.write("Content-Disposition: form-data; name=\"$name\"\\r\\n\\r\\n".toByteArray())
                    output.write(value.toByteArray(Charsets.UTF_8))
                    output.write("\\r\\n".toByteArray())
                }
                field("model", "whisper-large-v3-turbo")
                field("language", "fa")
                output.write("--$boundary\\r\\n".toByteArray())
                output.write("Content-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\\r\\n".toByteArray())
                output.write("Content-Type: ${mimeType.ifBlank { "application/octet-stream" }}\\r\\n\\r\\n".toByteArray())
                output.write(bytes)
                output.write("\\r\\n--$boundary--\\r\\n".toByteArray())
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = try { JSONObject(response).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                throw IllegalStateException("تبدیل ویس ناموفق بود ($code): ${(detail ?: response).take(260)}")
            }
            val text = JSONObject(response).optString("text").trim()
            if (text.isBlank()) throw IllegalStateException("متنی از فایل صوتی دریافت نشد.")
            return text.take(12000)
        } finally { connection.disconnect() }
    }

    fun buildLocalPrompt(userText: String, history: JSONArray): String {
        val prompt = StringBuilder()
        prompt.append("<|im_start|>system\n/no_think\n")
            .append("تو جارویس هستی؛ دستیار فارسی‌زبان، خودمونی، دقیق و مسئولیت‌پذیر. طبیعی و محاوره‌ای حرف بزن؛ مثلاً بگو «چیکار می‌تونم برات انجام بدم؟»، «واسه چی می‌خوای؟»، «یه لحظه صبر کن» و «انجام شد، دیگه چی؟». رسمی و کتابی نباش، اما محترمانه و روشن بمان. جواب را با کلمه یا عبارت انگلیسی شروع نکن، مگر اینکه کاربر پاسخ انگلیسی خواسته باشد. گفتار محاوره‌ای، مترادف‌ها، غلط املایی، خطای تبدیل گفتار به متن، چسبیده یا جدا نوشته‌شدن واژه‌ها و جاافتادن حرف آخر کلمه را با کمک بافت جمله درک کن. فقط وقتی واقعاً مبهم است یک سؤال کوتاه بپرس. به پرسش‌های عمومی، توضیح، برنامه‌ریزی، نگارش، ترجمه، محاسبه و عیب‌یابی پاسخ بده. اگر مطمئن نیستی صادقانه بگو. فقط فرمان‌های واقعی و مجاز برنامه را پیشنهاد بده و هرگز ادعا نکن عملی انجام شده است.\n<|im_end|>\n")
        val first = (history.length() - 8).coerceAtLeast(0)
        for (i in first until history.length()) {
            val item = history.optJSONObject(i) ?: continue
            val role = item.optString("role", "user")
            val content = item.optString("content", "").takeLast(900)
            prompt.append("<|im_start|>").append(role).append("\n")
                .append(content).append("<|im_end|>\n")
        }
        prompt.append("<|im_start|>user\n").append(userText.take(4000))
            .append("<|im_end|>\n<|im_start|>assistant\n/no_think\n")
        return prompt.toString()
    }
}
