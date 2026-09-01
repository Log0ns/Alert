package com.loganapps.drowsyalert

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.view.animation.AnimationUtils
import androidx.appcompat.app.AppCompatActivity
import com.loganapps.drowsyalert.databinding.ActivityAlarmBinding

class AlarmActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLockedAndTurnScreenOn()

        binding = ActivityAlarmBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Pulse the glow and eye icon to reinforce urgency
        val pulse = AnimationUtils.loadAnimation(this, R.anim.pulse)
        binding.alarmGlow.startAnimation(pulse)
        binding.alarmEyeIcon.startAnimation(pulse)

        // Auto-finish if the service clears the alarm (eyes opened)
        DrowsinessService.isAlarming.observe(this) { alarming ->
            if (alarming == false) finish()
        }

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
        // Ignored: alarm screen must not be dismissed with back press.
    }
}
