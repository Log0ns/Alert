package com.loganapps.drowsyalert

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.animation.DecelerateInterpolator
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.loganapps.drowsyalert.databinding.ActivityMainBinding
import com.loganapps.drowsyalert.databinding.BottomSheetSettingsBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var thresholdMillis: Long = 2_000L

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results[Manifest.permission.CAMERA] == true) {
                startMonitoring()
            } else {
                Toast.makeText(this, "Camera permission is required to detect drowsiness.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(DrowsinessService.PREFS_NAME, Context.MODE_PRIVATE)
        thresholdMillis = prefs.getLong(DrowsinessService.PREF_THRESHOLD_MS, 2_000L)

        binding.settingsButton.setOnClickListener { showSettingsSheet() }

        binding.startStopButton.setOnClickListener {
            if (DrowsinessService.isRunning.value == true) stopMonitoring()
            else requestPermissionsAndStart()
        }

        observeService()
        renderState()
    }

    private fun showSettingsSheet() {
        val sheet = BottomSheetDialog(this, R.style.BottomSheetStyle)
        val sheetBinding = BottomSheetSettingsBinding.inflate(LayoutInflater.from(this))
        sheet.setContentView(sheetBinding.root)

        val initialProgress = (thresholdMillis / 1000).toInt().coerceIn(1, 10)
        sheetBinding.thresholdSeekBar.progress = initialProgress
        sheetBinding.thresholdValue.text = getString(R.string.seconds_format, initialProgress)

        sheetBinding.thresholdSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val seconds = progress.coerceAtLeast(1)
                thresholdMillis = seconds * 1000L
                sheetBinding.thresholdValue.text = getString(R.string.seconds_format, seconds)
                prefs.edit().putLong(DrowsinessService.PREF_THRESHOLD_MS, thresholdMillis).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sheetBinding.glassesSwitch.isChecked = prefs.getBoolean(DrowsinessService.PREF_GLASSES_MODE, false)
        sheetBinding.glassesSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(DrowsinessService.PREF_GLASSES_MODE, checked).apply()
        }

        sheetBinding.earlyWarningSwitch.isChecked = prefs.getBoolean(DrowsinessService.PREF_EARLY_WARNING, true)
        sheetBinding.earlyWarningSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(DrowsinessService.PREF_EARLY_WARNING, checked).apply()
        }

        sheet.show()
    }

    private fun requestPermissionsAndStart() {
        val needed = mutableListOf(Manifest.permission.CAMERA).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val notGranted = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        when {
            notGranted.isEmpty() -> startMonitoring()
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.camera_needed_title))
                    .setMessage(getString(R.string.permission_rationale))
                    .setPositiveButton(getString(R.string.continue_label)) { _, _ ->
                        requestPermissions.launch(notGranted.toTypedArray())
                    }
                    .setNegativeButton(getString(R.string.cancel_label), null)
                    .show()
            }
            else -> requestPermissions.launch(notGranted.toTypedArray())
        }
    }

    private fun startMonitoring() {
        ContextCompat.startForegroundService(this, Intent(this, DrowsinessService::class.java))
    }

    private fun stopMonitoring() {
        startService(Intent(this, DrowsinessService::class.java).apply {
            action = DrowsinessService.ACTION_STOP
        })
    }

    private fun observeService() {
        DrowsinessService.isRunning.observe(this) { renderState() }
        DrowsinessService.isAlarming.observe(this) { renderState() }
        DrowsinessService.isDrooping.observe(this) { renderState() }
        DrowsinessService.eyeState.observe(this) { renderState() }
        DrowsinessService.closedElapsedMs.observe(this) { renderState() }
    }

    private fun renderState() {
        val running = DrowsinessService.isRunning.value == true
        val alarming = DrowsinessService.isAlarming.value == true
        val drooping = DrowsinessService.isDrooping.value == true
        val state = DrowsinessService.eyeState.value
        val elapsedMs = DrowsinessService.closedElapsedMs.value ?: 0L

        when {
            alarming -> {
                setButton(getString(R.string.stop_monitoring), R.drawable.ic_stop, R.color.alarm_red, R.color.white)
                setCardAccent(R.color.alarm_red_dim)
                binding.statusDot.setBackgroundResource(R.drawable.dot_alert)
                binding.statusLabel.text = getString(R.string.status_alarm)
                binding.statusLabel.setTextColor(ContextCompat.getColor(this, R.color.alarm_red))
                setEyeIcon(R.drawable.ic_eye_closed, R.color.alarm_red)
                binding.closedTimerLabel.text = getString(R.string.hint_alarm)
            }
            drooping -> {
                setButton(getString(R.string.stop_monitoring), R.drawable.ic_stop, R.color.card_elevated, R.color.white)
                setCardAccent(R.color.amber_dim)
                binding.statusDot.setBackgroundResource(R.drawable.dot_amber)
                binding.statusLabel.text = getString(R.string.status_drooping)
                binding.statusLabel.setTextColor(ContextCompat.getColor(this, R.color.amber))
                setEyeIcon(R.drawable.ic_eye_closed, R.color.amber)
                binding.closedTimerLabel.text = getString(R.string.hint_drooping)
            }
            running -> {
                setButton(getString(R.string.stop_monitoring), R.drawable.ic_stop, R.color.card_elevated, R.color.white)
                when (state) {
                    is EyeAnalyzer.EyeState.EyesClosed -> {
                        setCardAccent(R.color.amber_dim)
                        binding.statusDot.setBackgroundResource(R.drawable.dot_amber)
                        binding.statusLabel.text = getString(R.string.status_eyes_closed)
                        binding.statusLabel.setTextColor(ContextCompat.getColor(this, R.color.amber))
                        setEyeIcon(R.drawable.ic_eye_closed, R.color.amber)
                        binding.closedTimerLabel.text = getString(
                            R.string.closed_progress_format, elapsedMs / 1000, thresholdMillis / 1000
                        )
                    }
                    is EyeAnalyzer.EyeState.EyesOpen -> {
                        setCardAccent(R.color.accent_teal_dim)
                        binding.statusDot.setBackgroundResource(R.drawable.dot_watching)
                        binding.statusLabel.text = getString(R.string.status_eyes_open)
                        binding.statusLabel.setTextColor(ContextCompat.getColor(this, R.color.accent_teal))
                        setEyeIcon(R.drawable.ic_eye, R.color.accent_teal)
                        binding.closedTimerLabel.text = getString(R.string.hint_watching)
                    }
                    else -> {
                        setCardAccent(R.color.card_dark)
                        binding.statusDot.setBackgroundResource(R.drawable.dot_watching)
                        binding.statusLabel.text = getString(R.string.status_no_face)
                        binding.statusLabel.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                        setEyeIcon(R.drawable.ic_eye, R.color.muted_text)
                        binding.closedTimerLabel.text = getString(R.string.hint_no_face)
                    }
                }
            }
            else -> {
                setButton(getString(R.string.start_monitoring), R.drawable.ic_play, R.color.accent_teal, R.color.bg_dark)
                setCardAccent(R.color.card_dark)
                binding.statusDot.setBackgroundResource(R.drawable.dot_idle)
                binding.statusLabel.text = getString(R.string.status_idle)
                binding.statusLabel.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                setEyeIcon(R.drawable.ic_eye, R.color.muted_text)
                binding.closedTimerLabel.text = getString(R.string.hint_start)
            }
        }
    }

    private fun setButton(text: String, iconRes: Int, colorRes: Int, textColorRes: Int) {
        binding.startStopButton.text = text
        binding.startStopButton.setIconResource(iconRes)
        binding.startStopButton.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
        binding.startStopButton.setTextColor(ContextCompat.getColor(this, textColorRes))
        binding.startStopButton.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, textColorRes))
    }

    private fun setEyeIcon(iconRes: Int, colorRes: Int) {
        val color = ContextCompat.getColor(this, colorRes)
        if (binding.eyeIcon.tag != iconRes) {
            binding.eyeIcon.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f)
                .setDuration(120).setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    binding.eyeIcon.setImageResource(iconRes)
                    binding.eyeIcon.imageTintList = ColorStateList.valueOf(color)
                    binding.eyeIcon.tag = iconRes
                    binding.eyeIcon.animate().alpha(1f).scaleX(1f).scaleY(1f)
                        .setDuration(180).setInterpolator(DecelerateInterpolator()).start()
                }.start()
        } else {
            binding.eyeIcon.imageTintList = ColorStateList.valueOf(color)
        }
    }

    private fun setCardAccent(colorRes: Int) {
        binding.statusCard.setCardBackgroundColor(ContextCompat.getColor(this, colorRes))
    }

    override fun onStart() {
        super.onStart()
        renderState()
    }
}
