package com.loganapps.drowsyalert

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.loganapps.drowsyalert.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var thresholdMillis: Long = 15_000L

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val cameraGranted = results[Manifest.permission.CAMERA] == true
            if (cameraGranted) {
                startMonitoring()
            } else {
                Toast.makeText(
                    this,
                    "Camera permission is required to detect drowsiness.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(DrowsinessService.PREFS_NAME, Context.MODE_PRIVATE)
        thresholdMillis = prefs.getLong(DrowsinessService.PREF_THRESHOLD_MS, 15_000L)
        val initialProgress = (thresholdMillis / 1000).toInt().coerceIn(3, 45)
        binding.thresholdSeekBar.progress = initialProgress
        updateThresholdLabel(initialProgress)

        binding.thresholdSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val seconds = progress.coerceAtLeast(3)
                thresholdMillis = seconds * 1000L
                updateThresholdLabel(seconds)
                prefs.edit().putLong(DrowsinessService.PREF_THRESHOLD_MS, thresholdMillis).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.startStopButton.setOnClickListener {
            if (DrowsinessService.isRunning.value == true) {
                stopMonitoring()
            } else {
                requestPermissionsAndStart()
            }
        }

        observeService()
        renderState()
    }

    private fun updateThresholdLabel(seconds: Int) {
        binding.thresholdValue.text = getString(R.string.seconds_format, seconds)
    }

    private fun requestPermissionsAndStart() {
        val needed = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
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
        val intent = Intent(this, DrowsinessService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopMonitoring() {
        val intent = Intent(this, DrowsinessService::class.java).apply {
            action = DrowsinessService.ACTION_STOP
        }
        startService(intent)
    }

    private fun observeService() {
        DrowsinessService.isRunning.observe(this) { renderState() }
        DrowsinessService.isAlarming.observe(this) { renderState() }
        DrowsinessService.eyeState.observe(this) { renderState() }
        DrowsinessService.closedElapsedMs.observe(this) { renderState() }
    }

    private fun renderState() {
        val running = DrowsinessService.isRunning.value == true
        val alarming = DrowsinessService.isAlarming.value == true
        val state = DrowsinessService.eyeState.value
        val elapsedMs = DrowsinessService.closedElapsedMs.value ?: 0L

        when {
            alarming -> {
                setButton(getString(R.string.stop_monitoring), R.drawable.ic_stop, R.color.alarm_red)
                binding.statusDot.setBackgroundResource(R.drawable.dot_alert)
                binding.statusLabel.text = getString(R.string.status_alarm)
                setEyeIcon(R.drawable.ic_eye_closed, R.color.alarm_red)
                binding.closedTimerLabel.text = getString(R.string.hint_alarm)
            }
            running -> {
                setButton(getString(R.string.stop_monitoring), R.drawable.ic_stop, R.color.muted_text)
                when (state) {
                    is EyeAnalyzer.EyeState.EyesClosed -> {
                        binding.statusDot.setBackgroundResource(R.drawable.dot_amber)
                        binding.statusLabel.text = getString(R.string.status_eyes_closed)
                        setEyeIcon(R.drawable.ic_eye_closed, R.color.amber)
                        val secs = elapsedMs / 1000
                        binding.closedTimerLabel.text = getString(
                            R.string.closed_progress_format, secs, thresholdMillis / 1000
                        )
                    }
                    is EyeAnalyzer.EyeState.EyesOpen -> {
                        binding.statusDot.setBackgroundResource(R.drawable.dot_watching)
                        binding.statusLabel.text = getString(R.string.status_eyes_open)
                        setEyeIcon(R.drawable.ic_eye, R.color.accent_teal)
                        binding.closedTimerLabel.text = getString(R.string.hint_watching)
                    }
                    else -> {
                        binding.statusDot.setBackgroundResource(R.drawable.dot_watching)
                        binding.statusLabel.text = getString(R.string.status_no_face)
                        setEyeIcon(R.drawable.ic_eye, R.color.muted_text)
                        binding.closedTimerLabel.text = getString(R.string.hint_no_face)
                    }
                }
            }
            else -> {
                setButton(getString(R.string.start_monitoring), R.drawable.ic_play, R.color.accent_teal)
                binding.statusDot.setBackgroundResource(R.drawable.dot_idle)
                binding.statusLabel.text = getString(R.string.status_idle)
                setEyeIcon(R.drawable.ic_eye, R.color.muted_text)
                binding.closedTimerLabel.text = getString(R.string.hint_start)
            }
        }
    }

    private fun setButton(text: String, iconRes: Int, colorRes: Int) {
        binding.startStopButton.text = text
        binding.startStopButton.setIconResource(iconRes)
        binding.startStopButton.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
    }

    private fun setEyeIcon(iconRes: Int, colorRes: Int) {
        binding.eyeIcon.setImageResource(iconRes)
        binding.eyeIcon.imageTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
    }

    override fun onStart() {
        super.onStart()
        renderState()
    }
}
