package com.loganapps.drowsyalert

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.loganapps.drowsyalert.databinding.ActivityAlarmBinding

/**
 * Full-screen alert shown when the closed-eye threshold is exceeded. Reached
 * via a full-screen-intent notification, so it can appear over the lock
 * screen or on top of whatever app the user currently has open.
 */
class AlarmActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLockedAndTurnScreenOn()

        binding = ActivityAlarmBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.dismissButton.setOnClickListener {
            val intent = Intent(this, DrowsinessService::class.java).apply {
                action = DrowsinessService.ACTION_DISMISS_ALARM
            }
            startService(intent)
            finish()
        }
    }

    private fun setShowWhenLockedAndTurnScreenOn() {
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Ignored on purpose: an alarm screen shouldn't be dismissible with
        // a stray back press while it's still actively sounding.
    }
}
