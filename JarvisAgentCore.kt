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

    fun askOnline(userText: String): String {
        require(userText.isNotBlank()) { "پیام خالی است." }
        val target = normalizedEndpoint()
        val modelName = model().trim()
        require(modelName.isNotBlank() && modelName.length <= 200) { "نام مدل معتبر نیست." }

        val connection = (URL(target).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 60000
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
                    "You are JARVIS, a careful Persian-first assistant and coding helper. Reply naturally in Persian unless asked otherwise. Explain code clearly. Never claim a phone action was completed unless the Android app confirms it. Do not request secrets."))
                .put(JSONObject().put("role", "user").put("content", userText.take(12000)))
            val body = JSONObject()
                .put("model", modelName)
                .put("messages", messages)
                .put("temperature", 0.35)
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

    fun buildLocalPrompt(userText: String, history: JSONArray): String {
        val prompt = StringBuilder()
        prompt.append("<|im_start|>system\n/no_think\n")
            .append("تو JARVIS هستی؛ دستیار فارسی‌زبان، دقیق و مسئولیت‌پذیر و کمک‌برنامه‌نویس. فارسی روان پاسخ بده. در برنامه‌نویسی کد کامل و قابل استفاده، توضیح و مراحل عیب‌یابی بده. اگر مطمئن نیستی صادقانه بگو. فقط فرمان‌های واقعی و مجاز برنامه را پیشنهاد بده و هرگز ادعا نکن عملی انجام شده است.\n<|im_end|>\n")
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
