package com.remotesupport.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class RemoteAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "RemoteAccessibility"
        var instance: RemoteAccessibilityService? = null
            private set

        val isServiceRunning: Boolean
            get() = instance != null
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Professor Accessibility Service Connected and Ready.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No event interception needed
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service Interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "Accessibility Service Destroyed.")
    }

    /**
     * Simulates a single tap at normalized coordinates (0.0 to 1.0)
     */
    fun performTap(ratioX: Float, ratioY: Float) {
        val metrics = resources.displayMetrics
        val actualX = ratioX * metrics.widthPixels
        val actualY = ratioY * metrics.heightPixels

        val clickPath = Path().apply {
            moveTo(actualX, actualY)
        }

        val stroke = GestureDescription.StrokeDescription(clickPath, 0L, 50L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Tap executed at ($actualX, $actualY)")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Tap cancelled at ($actualX, $actualY)")
            }
        }, null)
    }

    /**
     * Simulates a drag or swipe gesture
     */
    fun performSwipe(
        startXRatio: Float,
        startYRatio: Float,
        endXRatio: Float,
        endYRatio: Float,
        durationMs: Long
    ) {
        val metrics = resources.displayMetrics
        val x1 = startXRatio * metrics.widthPixels
        val y1 = startYRatio * metrics.heightPixels
        val x2 = endXRatio * metrics.widthPixels
        val y2 = endYRatio * metrics.heightPixels

        val swipePath = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }

        val stroke = GestureDescription.StrokeDescription(swipePath, 0L, durationMs.coerceIn(100L, 1000L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, null, null)
    }

    /**
     * Pastes or types remote text into the focused field and copies to clipboard
     */
    fun insertOrCopyText(text: String) {
        mainHandler.post {
            try {
                // 1. Copy to Android System Clipboard
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("ProfessorRemoteText", text)
                clipboard.setPrimaryClip(clip)

                // 2. Try to type directly into the focused input field
                val rootNode = rootInActiveWindow
                val focusedNode = rootNode?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focusedNode != null) {
                    val arguments = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    }
                    val typed = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                    if (!typed) {
                        focusedNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    }
                }

                // 3. Show prominent popup notification on phone screen
                Toast.makeText(applicationContext, "💬 تم استلام نص من الكمبيوتر:\n$text", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error inserting remote text", e)
            }
        }
    }

    /**
     * Wakes up the screen and automatically performs upward swipe to dismiss the lock screen
     */
    fun wakeUpAndUnlock() {
        mainHandler.post {
            try {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                val wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                    "Professor:WakeLock"
                )
                wakeLock.acquire(3000L)
                wakeLock.release()

                // Swipe upwards to unlock / dismiss keyguard
                mainHandler.postDelayed({
                    performSwipe(0.5f, 0.85f, 0.5f, 0.2f, 300L)
                }, 400L)
            } catch (e: Exception) {
                Log.e(TAG, "Error waking screen", e)
            }
        }
    }

    /**
     * Executes standard system navigation actions & smart shortcuts
     */
    fun executeNavigationKey(keyName: String) {
        when (keyName.uppercase()) {
            "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "NOTIFICATIONS" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "QUICK_SETTINGS" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            "POWER_DIALOG" -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
            "WAKE" -> wakeUpAndUnlock()
            "UNLOCK" -> wakeUpAndUnlock()
            "LOCK" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                }
            }
            "SCREENSHOT" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                }
            }
        }
    }
}
