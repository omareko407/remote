package com.remotesupport.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.UUID

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 101
        private const val PREFS_NAME = "ProfessorPrefs"
        private const val KEY_DEVICE_ID = "KEY_DEVICE_ID"
        private const val KEY_SERVER_URL = "KEY_SERVER_URL"
        private const val DEFAULT_SERVER_URL = "http://192.168.0.102:3000"

        fun formatServerUrl(input: String?): String {
            var url = (input ?: "").trim()
            if (url.isEmpty()) return DEFAULT_SERVER_URL
            if (url.contains("192.168.0.107")) {
                url = url.replace("192.168.0.107", "192.168.0.102")
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://$url"
            }
            val parts = url.substringAfter("://").split("/")
            val hostPort = parts[0]
            if (!hostPort.contains(":")) {
                val remainingPath = if (parts.size > 1) "/" + parts.drop(1).joinToString("/") else ""
                val scheme = if (url.startsWith("https://")) "https" else "http"
                url = "$scheme://$hostPort:3000$remainingPath"
            }
            return url
        }
    }

    private lateinit var tvDeviceId: TextView
    private lateinit var etServerUrl: EditText
    private lateinit var tvStatus: TextView
    private lateinit var btnEnableAccessibility: Button
    private lateinit var btnGrantScreen: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private lateinit var prefs: SharedPreferences
    private var deviceId: String = ""
    private var screenCaptureIntentData: Intent? = null
    private var screenCaptureResultCode: Int = Activity.RESULT_CANCELED
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var hasRequestedCaptureThisResume = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        initDeviceId()

        // Bind Views
        tvDeviceId = findViewById(R.id.tvDeviceId)
        etServerUrl = findViewById(R.id.etServerUrl)
        tvStatus = findViewById(R.id.tvStatus)
        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility)
        btnGrantScreen = findViewById(R.id.btnGrantScreen)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        tvDeviceId.text = deviceId

        // Handle URL migration and formatting
        var savedUrl = prefs.getString(KEY_SERVER_URL, null)
        if (savedUrl == null || savedUrl.contains("192.168.0.107")) {
            savedUrl = DEFAULT_SERVER_URL
            prefs.edit().putString(KEY_SERVER_URL, savedUrl).apply()
        }
        etServerUrl.setText(formatServerUrl(savedUrl))

        // Ensure stop button is permanently hidden on the phone
        btnStop.visibility = View.GONE

        setupListeners()
        showSecurityWarningDialogOnce()
    }

    override fun onResume() {
        super.onResume()
        hasRequestedCaptureThisResume = false

        // If the service is already running, minimize immediately to save battery & screen
        if (ScreenCaptureService.isRunning) {
            moveTaskToBack(true)
            return
        }

        updatePermissionStatus()

        val hasSeenWarning = prefs.getBoolean("has_seen_security_warning", false)
        if (hasSeenWarning) {
            autoProgressSession()
        }
    }

    private fun initDeviceId() {
        deviceId = prefs.getString(KEY_DEVICE_ID, null) ?: run {
            val randomId = "PROFESSOR-" + UUID.randomUUID().toString().substring(0, 6).uppercase()
            prefs.edit().putString(KEY_DEVICE_ID, randomId).apply()
            randomId
        }
    }

    private fun setupListeners() {
        // 1. Accessibility Service Settings
        btnEnableAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "ابحث عن 'Professor Assistance Service' وفعّلها", Toast.LENGTH_LONG).show()
        }

        // 2. Request MediaProjection
        btnGrantScreen.setOnClickListener {
            requestScreenCapture()
        }

        // 3. Start Session manually
        btnStart.setOnClickListener {
            startSessionIfReady()
        }

        // 4. Stop Session is not needed on phone UI (controlled exclusively from PC)
        btnStop.visibility = View.GONE
    }

    private fun requestScreenCapture() {
        try {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر طلب إذن الشاشة: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun autoProgressSession() {
        // If accessibility is enabled but screen projection is not yet granted, auto-prompt projection dialog
        if (RemoteAccessibilityService.isServiceRunning && screenCaptureIntentData == null && !hasRequestedCaptureThisResume) {
            hasRequestedCaptureThisResume = true
            requestScreenCapture()
        } else if (screenCaptureIntentData != null && !ScreenCaptureService.isRunning) {
            startSessionIfReady()
        }
    }

    private fun startSessionIfReady() {
        if (screenCaptureIntentData == null) {
            requestScreenCapture()
            return
        }

        if (!RemoteAccessibilityService.isServiceRunning) {
            Toast.makeText(this, "تنبيه: تحكم اللمس غير مفعل، سيعمل بث الشاشة فقط", Toast.LENGTH_SHORT).show()
        }

        val rawUrl = etServerUrl.text.toString().trim()
        val cleanUrl = formatServerUrl(rawUrl)
        prefs.edit().putString(KEY_SERVER_URL, cleanUrl).apply()
        etServerUrl.setText(cleanUrl)

        startScreenCaptureService(cleanUrl)
    }

    private fun startScreenCaptureService(serverUrl: String) {
        try {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, screenCaptureResultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, screenCaptureIntentData)
                putExtra(ScreenCaptureService.EXTRA_SERVER_URL, serverUrl)
                putExtra(ScreenCaptureService.EXTRA_DEVICE_ID, deviceId)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            btnStart.visibility = View.GONE
            btnStop.visibility = View.GONE
            tvStatus.text = "التطبيق يعمل في الخلفية ⚡"
            tvStatus.setTextColor(getColor(R.color.success))
            Toast.makeText(this, "تم بدء الخدمة، التطبيق يعمل في الخلفية 🟢", Toast.LENGTH_SHORT).show()

            // Minimize activity immediately to save screen rendering, CPU, and battery
            mainHandler.postDelayed({
                moveTaskToBack(true)
            }, 300L)
        } catch (e: Exception) {
            Toast.makeText(this, "خطأ في بدء الخدمة: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                screenCaptureResultCode = resultCode
                screenCaptureIntentData = data
                btnGrantScreen.text = getString(R.string.perm_granted)
                btnGrantScreen.setBackgroundColor(getColor(R.color.success))
                Toast.makeText(this, "تم منح إذن الشاشة بنجاح", Toast.LENGTH_SHORT).show()
                startSessionIfReady()
            } else {
                Toast.makeText(this, "تم رفض إذن مشاركة الشاشة", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updatePermissionStatus() {
        if (RemoteAccessibilityService.isServiceRunning) {
            btnEnableAccessibility.text = getString(R.string.perm_granted)
            btnEnableAccessibility.setBackgroundColor(getColor(R.color.success))
        } else {
            btnEnableAccessibility.text = getString(R.string.btn_enable_accessibility)
            btnEnableAccessibility.setBackgroundColor(getColor(R.color.primary))
        }

        if (screenCaptureIntentData != null) {
            btnGrantScreen.text = getString(R.string.perm_granted)
            btnGrantScreen.setBackgroundColor(getColor(R.color.success))
        }

        if (ScreenCaptureService.isRunning) {
            btnStart.visibility = View.GONE
            btnStop.visibility = View.GONE
            tvStatus.text = "التطبيق يعمل في الخلفية 🟢"
        }
    }

    private fun showSecurityWarningDialogOnce() {
        val hasSeenWarning = prefs.getBoolean("has_seen_security_warning", false)
        if (!hasSeenWarning) {
            AlertDialog.Builder(this)
                .setTitle("⚠️ تحذير أمني وموافقة صريحة")
                .setMessage("تطبيق Professor مخصص للدعم الفني عن بُعد. عند بدء الجلسة، سيتمكن الطرف الآخر من مشاهدة شاشتك والتحكم في هاتفك.\n\nهل توافق على استخدام هذا التطبيق لأغراض الدعم الفني فقط؟")
                .setPositiveButton("موافق وأفهم ذلك") { dialog, _ ->
                    prefs.edit().putBoolean("has_seen_security_warning", true).apply()
                    dialog.dismiss()
                    autoProgressSession()
                }
                .setNegativeButton("إلغاء وإغلاق") { _, _ ->
                    finish()
                }
                .setCancelable(false)
                .show()
        }
    }
}
