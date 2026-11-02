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
        private const val DEFAULT_SERVER_URL = "http://192.168.0.107:3000"
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
        etServerUrl.setText(prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL))

        setupListeners()
        showSecurityWarningDialogOnce()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        checkAutoConnect()
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
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
        }

        // 3. Start Session manually
        btnStart.setOnClickListener {
            startSessionIfReady()
        }

        // 4. Stop Session
        btnStop.setOnClickListener {
            stopScreenCaptureService()
        }
    }

    private fun checkAutoConnect() {
        // Auto-connect if both permissions are granted and not already streaming
        if (RemoteAccessibilityService.isServiceRunning && screenCaptureIntentData != null && !ScreenCaptureService.isRunning) {
            startSessionIfReady()
        }
    }

    private fun startSessionIfReady() {
        if (!RemoteAccessibilityService.isServiceRunning) {
            Toast.makeText(this, "يرجى تفعيل إذن إمكانية الوصول أولاً للتحكم", Toast.LENGTH_SHORT).show()
            return
        }

        if (screenCaptureIntentData == null) {
            Toast.makeText(this, "يرجى منح إذن تسجيل الشاشة أولاً", Toast.LENGTH_SHORT).show()
            return
        }

        val serverUrl = etServerUrl.text.toString().trim()
        if (serverUrl.isEmpty()) {
            Toast.makeText(this, "يرجى كتابة عنوان الخادم", Toast.LENGTH_SHORT).show()
            return
        }

        // Save server URL for future auto-connects
        prefs.edit().putString(KEY_SERVER_URL, serverUrl).apply()

        startScreenCaptureService(serverUrl)
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
            btnStop.visibility = View.VISIBLE
            tvStatus.text = getString(R.string.status_streaming)
            tvStatus.setTextColor(getColor(R.color.warning_border))
            Toast.makeText(this, "تم الاتصال بالخادم وبدء البث بنجاح 🟢", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "خطأ في بدء الخدمة: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopScreenCaptureService() {
        try {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_STOP
            }
            startService(serviceIntent)

            btnStart.visibility = View.VISIBLE
            btnStop.visibility = View.GONE
            tvStatus.text = getString(R.string.status_ready)
            tvStatus.setTextColor(getColor(R.color.text_muted))
            Toast.makeText(this, "تم قطع الاتصال بنجاح", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "خطأ: ${e.message}", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, "تم منح إذن مشاركة الشاشة بنجاح", Toast.LENGTH_SHORT).show()
                checkAutoConnect()
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

        if (ScreenCaptureService.isRunning) {
            btnStart.visibility = View.GONE
            btnStop.visibility = View.VISIBLE
            tvStatus.text = getString(R.string.status_streaming)
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
                }
                .setNegativeButton("إلغاء وإغلاق") { _, _ ->
                    finish()
                }
                .setCancelable(false)
                .show()
        }
    }
}
