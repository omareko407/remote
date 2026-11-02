package com.remotesupport.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class RemoteAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "RemoteAccessibility"
        var instance: RemoteAccessibilityService? = null
            private set

        val isServiceRunning: Boolean
            get() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Professor Accessibility Service Connected and Ready.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No event interception needed; only gesture dispatching
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
