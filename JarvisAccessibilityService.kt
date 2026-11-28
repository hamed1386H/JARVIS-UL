package com.chtai.assistant

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat

/**
 * User-enabled accessibility bridge for explicit JARVIS commands.
 * It only performs a requested click/text/global action; it does not inspect or
 * transmit screen contents, passwords, or other app data.
 */
class JarvisAccessibilityService : AccessibilityService() {
    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_AUTOMATE) return
            val action = intent.getStringExtra(EXTRA_ACTION).orEmpty()
            val value = intent.getStringExtra(EXTRA_VALUE).orEmpty()
            val ok = try { execute(action, value) } catch (_: Exception) { false }
            sendBroadcast(Intent(ACTION_RESULT).setPackage(packageName)
                .putExtra(EXTRA_ACTION, action)
                .putExtra(EXTRA_SUCCESS, ok))
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val filter = IntentFilter(ACTION_AUTOMATE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(commandReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(commandReceiver, filter)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        try { unregisterReceiver(commandReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun execute(action: String, value: String): Boolean {
        return when (action) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recent" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "tap" -> {
                if (value.isBlank()) return false
                val root = rootInActiveWindow ?: return false
                val node = findNode(root, value) ?: return false
                clickNode(node)
            }
            "type" -> {
                if (value.isEmpty()) return false
                val root = rootInActiveWindow ?: return false
                val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: findEditable(root)
                    ?: return false
                target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                }
                target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            else -> false
        }
    }

    private fun findNode(node: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val wanted = query.trim()
        if (wanted.isEmpty()) return null
        val label = listOf(node.text?.toString(), node.contentDescription?.toString())
            .filterNotNull().joinToString(" ")
        if (label.contains(wanted, ignoreCase = true)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNode(child, wanted)
            if (found != null) return found
        }
        return null
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isEnabled) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditable(child)
            if (found != null) return found
        }
        return null
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.isEnabled) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val parent = node.parent ?: return false
        return clickNode(parent)
    }

    companion object {
        const val ACTION_AUTOMATE = "com.chtai.assistant.ACCESSIBILITY_AUTOMATE"
        const val ACTION_RESULT = "com.chtai.assistant.ACCESSIBILITY_RESULT"
        const val EXTRA_ACTION = "automation_action"
        const val EXTRA_VALUE = "automation_value"
        const val EXTRA_SUCCESS = "automation_success"
    }
}
