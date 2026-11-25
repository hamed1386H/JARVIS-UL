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

    fun endpoint(): String = prefs.getString("endpoint", "") ?: ""
    fun apiKey(): String = prefs.getString("api_key", "") ?: ""
    fun model(): String = prefs.getString("model", "gpt-4o-mini") ?: "gpt-4o-mini"

    fun saveConfig(endpoint: String, key: String, model: String) {
        prefs.edit()
            .putString("endpoint", endpoint.trim())
            .putString("api_key", key.trim())
            .putString("model", model.trim().ifBlank { "gpt-4o-mini" })
            .apply()
    }

    fun isOnlineConfigured(): Boolean {
        val value = endpoint()
        return value.startsWith("https://") && value.length < 500 && model().isNotBlank()
    }

    fun askOnline(userText: String): String {
        require(isOnlineConfigured()) { "اتصال آنلاین تنظیم نشده است." }
        val connection = (URL(endpoint()).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 90000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            if (apiKey().isNotBlank()) setRequestProperty("Authorization", "Bearer ${apiKey()}")
        }
        try {
            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content",
                    "You are JARVIS, a careful Persian-first assistant and coding helper. Reply naturally in Persian unless asked otherwise. Explain code clearly. Never claim a phone action was completed unless the Android app confirms it. Do not request secrets."))
                .put(JSONObject().put("role", "user").put("content", userText.take(12000)))
            val body = JSONObject()
                .put("model", model())
                .put("messages", messages)
                .put("temperature", 0.35)
                .put("stream", false)
                .toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("خطای سرویس آنلاین ($code): ${response.take(500)}")
            val json = JSONObject(response)
            val answer = json.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content").orEmpty().trim()
            if (answer.isBlank()) throw IllegalStateException("پاسخ سرویس آنلاین خالی بود.")
            return answer.take(12000)
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
