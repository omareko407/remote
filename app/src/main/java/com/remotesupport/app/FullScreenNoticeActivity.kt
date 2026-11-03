package com.remotesupport.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class FullScreenNoticeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE = "EXTRA_TITLE"
        const val EXTRA_MESSAGE = "EXTRA_MESSAGE"
        const val EXTRA_DURATION_SEC = "EXTRA_DURATION_SEC"

        private var activeInstance: FullScreenNoticeActivity? = null

        fun showNotice(context: Context, title: String, message: String, durationSec: Int) {
            val intent = Intent(context, FullScreenNoticeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_MESSAGE, message)
                putExtra(EXTRA_DURATION_SEC, durationSec)
            }
            context.startActivity(intent)
        }

        fun dismissNotice() {
            activeInstance?.runOnUiThread {
                activeInstance?.finish()
                activeInstance = null
            }
        }
    }

    private var countDownTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeInstance = this

        // Configure Fullscreen and Screen On Flags
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        setContentView(R.layout.activity_notice)
        applyIntentData(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntentData(intent)
    }

    private fun applyIntentData(intent: Intent?) {
        val tvTitle = findViewById<TextView>(R.id.tvNoticeTitle)
        val tvMessage = findViewById<TextView>(R.id.tvNoticeMessage)
        val tvTimer = findViewById<TextView>(R.id.tvNoticeTimer)

        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "تنبيه الدعم الفني"
        val message = intent?.getStringExtra(EXTRA_MESSAGE) ?: "جاري فحص الجهاز، يرجى الانتظار..."
        val durationSec = intent?.getIntExtra(EXTRA_DURATION_SEC, 0) ?: 0

        tvTitle.text = title
        tvMessage.text = message

        countDownTimer?.cancel()

        if (durationSec > 0) {
            tvTimer.text = "الوقت المتبقي: $durationSec ثانية"
            countDownTimer = object : CountDownTimer(durationSec * 1000L, 1000L) {
                override fun onTick(millisUntilFinished: Long) {
                    val remaining = millisUntilFinished / 1000
                    tvTimer.text = "الوقت المتبقي: $remaining ثانية"
                }

                override fun onFinish() {
                    tvTimer.text = "انتهت مدة التنبيه"
                    finish()
                }
            }.start()
        } else {
            tvTimer.text = "تنبيه مستمر (يتم إغلاقه من لوحة التحكم)"
        }
    }

    override fun onDestroy() {
        countDownTimer?.cancel()
        countDownTimer = null
        if (activeInstance == this) {
            activeInstance = null
        }
        super.onDestroy()
    }
}
