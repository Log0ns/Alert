package com.loganapps.drowsyalert

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.loganapps.drowsyalert.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null

    private var isMonitoring = false
    private var eyesClosedSinceMillis: Long? = null
    private var closedThresholdMillis: Long = 15_000L // default: 15s, matches SeekBar default

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var alarmActive = false

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startMonitoring()
            } else {
                Toast.makeText(this, "Camera permission is required to detect drowsiness.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        binding.thresholdSeekBar.progress = (closedThresholdMillis / 1000).toInt()
        updateThresholdLabel()
        binding.thresholdSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val seconds = progress.coerceAtLeast(3)
                closedThresholdMillis = seconds * 1000L
                updateThresholdLabel()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        binding.startStopButton.setOnClickListener {
            if (isMonitoring) {
                stopMonitoring()
            } else {
                requestCameraAndStart()
            }
        }
    }

    private fun updateThresholdLabel() {
        binding.thresholdLabel.text = getString(R.string.threshold_label) +
            "  (${closedThresholdMillis / 1000}s)"
    }

    private fun requestCameraAndStart() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED -> startMonitoring()

            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                AlertDialog.Builder(this)
                    .setTitle("Camera needed")
                    .setMessage(getString(R.string.permission_rationale))
                    .setPositiveButton("Continue") { _, _ ->
                        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }

            else -> requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startMonitoring() {
        isMonitoring = true
        eyesClosedSinceMillis = null
        binding.startStopButton.text = getString(R.string.stop_monitoring)
        binding.statusText.text = "Starting camera…"
        acquireWakeLock()
        bindCamera()
    }

    private fun stopMonitoring() {
        isMonitoring = false
        eyesClosedSinceMillis = null
        binding.startStopButton.text = getString(R.string.start_monitoring)
        binding.statusText.text = getString(R.string.status_idle)
        cameraProvider?.unbindAll()
        stopAlarm()
        releaseWakeLock()
    }

    private fun bindCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, EyeAnalyzer { state -> handleEyeState(state) })
                }

            // Front camera, since this watches the user's own eyes.
            val selector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, selector, preview, analysis)
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Could not start camera: ${e.message}", Toast.LENGTH_LONG).show()
                    stopMonitoring()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleEyeState(state: EyeAnalyzer.EyeState) {
        runOnUiThread {
            if (!isMonitoring) return@runOnUiThread

            when (state) {
                is EyeAnalyzer.EyeState.NoFaceDetected -> {
                    binding.statusText.text = "No face detected — reposition camera"
                    // Don't reset the closed-eye timer here: a brief detection
                    // dropout shouldn't cancel an in-progress drowsy episode.
                }
                is EyeAnalyzer.EyeState.EyesOpen -> {
                    eyesClosedSinceMillis = null
                    binding.statusText.text = "Eyes open"
                    if (alarmActive) stopAlarm()
                }
                is EyeAnalyzer.EyeState.EyesClosed -> {
                    val now = System.currentTimeMillis()
                    val since = eyesClosedSinceMillis ?: now.also { eyesClosedSinceMillis = it }
                    val elapsed = now - since
                    binding.statusText.text = "Eyes closed (${elapsed / 1000}s)"
                    if (elapsed >= closedThresholdMillis && !alarmActive) {
                        triggerAlarm()
                    }
                }
            }
        }
    }

    private fun triggerAlarm() {
        alarmActive = true
        binding.alertOverlay.visibility = android.view.View.VISIBLE
        binding.wakeAlertText.visibility = android.view.View.VISIBLE

        vibrator?.let { v ->
            val pattern = longArrayOf(0, 500, 300, 500, 300, 500)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, 0)
            }
        }

        try {
            val alarmUri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@MainActivity, alarmUri)
                isLooping = true
                setVolume(1.0f, 1.0f)
                prepare()
                start()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Could not play alarm sound: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopAlarm() {
        alarmActive = false
        binding.alertOverlay.visibility = android.view.View.INVISIBLE
        binding.wakeAlertText.visibility = android.view.View.INVISIBLE
        vibrator?.cancel()
        mediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        mediaPlayer = null
        eyesClosedSinceMillis = null
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            "DrowsyAlert::MonitoringWakeLock"
        ).apply { acquire(6 * 60 * 60 * 1000L /* 6 hours max, then auto-release as a safety net */) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAlarm()
        releaseWakeLock()
        cameraExecutor.shutdown()
    }
}
