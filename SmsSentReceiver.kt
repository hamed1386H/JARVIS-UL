package com.chtai.assistant

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Receives carrier/network send results independently of the JARVIS activity lifecycle. */
class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_SMS_SENT) return
        val batchId = intent.getLongExtra(EXTRA_BATCH_ID, -1L)
        if (batchId < 0L) return
        val recipient = intent.getStringExtra(EXTRA_RECIPIENT) ?: "مخاطب"
        val total = intent.getIntExtra(EXTRA_PART_COUNT, 1).coerceAtLeast(1)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = batchId.toString()
        val completedKey = "${key}_completed"
        val failedKey = "${key}_failed"
        val errorKey = "${key}_error"

        val success = resultCode == android.app.Activity.RESULT_OK
        val completed = prefs.getInt(completedKey, 0) + 1
        val failedBefore = prefs.getBoolean(failedKey, false)
        val errorBefore = prefs.getString(errorKey, null)
        val errorNow = if (success) null else errorText(resultCode)
        prefs.edit()
            .putInt(completedKey, completed)
            .putBoolean(failedKey, failedBefore || !success)
            .putString(errorKey, errorNow ?: errorBefore)
            .putString("${key}_recipient", recipient)
            .putInt("${key}_total", total)
            .apply()

        if (completed >= total) {
            val failed = failedBefore || !success
            val reason = errorNow ?: errorBefore
            val message = if (!failed) {
                "پیام به $recipient ارسال شد"
            } else {
                "ارسال پیام به $recipient با مشکل مواجه شد" + (reason?.let { "؛ $it" } ?: "")
            }
            showNotification(context, message)
            prefs.edit().remove(completedKey).remove(failedKey).remove(errorKey)
                .remove("${key}_recipient").remove("${key}_total").apply()
        }
    }

    private fun errorText(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_NO_SERVICE -> "شبکهٔ اپراتور در دسترس نیست؛ آنتن را بررسی کن"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "رادیوی تلفن خاموش است"
        SmsManager.RESULT_ERROR_NULL_PDU -> "اطلاعات پیامک نامعتبر است"
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "محدودیت ارسال پیامک پر شده است"
        SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE -> "ارسال به‌دلیل محدودیت شماره مجاز نشد"
        SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED -> "ارسال به این شماره کوتاه مجاز نیست"
        SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> "ارسال به این شماره کوتاه مسدود است"
        else -> "اپراتور یا شبکه پیامک را نپذیرفت (کد $code)"
    }

    private fun showNotification(context: Context, message: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "گزارش عملیات JARVIS", NotificationManager.IMPORTANCE_HIGH))
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) NotificationCompat.Builder(context, CHANNEL_ID)
            else NotificationCompat.Builder(context)
        val notification = builder
            .setSmallIcon(android.R.drawable.sym_action_email)
            .setContentTitle("گزارش جارویس")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify((System.currentTimeMillis() and 0x7fffffff).toInt(), notification)
    }

    companion object {
        const val ACTION_SMS_SENT = "com.chtai.assistant.SMS_SENT"
        const val EXTRA_BATCH_ID = "sms_batch_id"
        const val EXTRA_RECIPIENT = "sms_recipient"
        const val EXTRA_PART_COUNT = "sms_part_count"
        const val EXTRA_PART_INDEX = "sms_part_index"
        private const val PREFS = "jarvis_sms_send_results"
        private const val CHANNEL_ID = "jarvis_action_results"
    }
}
