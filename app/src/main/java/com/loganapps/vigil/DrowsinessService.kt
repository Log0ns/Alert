package com.loganapps.vigil

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.MutableLiveData
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground service that owns the front camera and runs eye-closure
 * detection continuously, independent of whether MainActivity is visible.
 * Publishes status via the companion LiveData fields below, which
 * MainActivity observes directly (no binding needed, since this is all
 * single-process).
 */
class DrowsinessService : LifecycleService() {

    companion object {
        const val ACTION_STOP = "com.loganapps.vigil.action.STOP"
        const val ACTION_DISMISS_ALARM = "com.loganapps.vigil.action.DISMISS_ALARM"
        const val PREFS_NAME = "drowsy_alert_prefs"
        const val PREF_THRESHOLD_MS = "threshold_ms"
        const val PREF_GLASSES_MODE = "glasses_mode"
        const val PREF_EARLY_WARNING = "early_warning"
        private const val DEFAULT_THRESHOLD_MS = 2_000L
        private const val THRESHOLD_NORMAL = 0.35f
        private const val THRESHOLD_GLASSES = 0.18f

        private const val MONITORING_CHANNEL_ID = "monitoring_channel"
        private const val ALARM_CHANNEL_ID = "alarm_channel"
        private const val MONITORING_NOTIFICATION_ID = 1
        private const val ALARM_NOTIFICATION_ID = 2

        val eyeState = MutableLiveData<EyeAnalyzer.EyeState>()
        val closedElapsedMs = MutableLiveData(0L)
        val isRunning = MutableLiveData(false)
        val isAlarming = MutableLiveData(false)
        val isDrooping = MutableLiveData(false)
    }

    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var eyesClosedSinceMillis: Long? = null
    private var thresholdMillis: Long = DEFAULT_THRESHOLD_MS

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var warningSpoken = false
    private var droopWarningSpoken = false

    private var eyeAnalyzer: EyeAnalyzer? = null

    private lateinit var prefs: SharedPreferences
    private var glassesMode: Boolean = false
    private var earlyWarningEnabled: Boolean = true
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
        if (key == PREF_THRESHOLD_MS) thresholdMillis = sp.getLong(PREF_THRESHOLD_MS, DEFAULT_THRESHOLD_MS)
        if (key == PREF_EARLY_WARNING) earlyWarningEnabled = sp.getBoolean(PREF_EARLY_WARNING, true)
        if (key == PREF_GLASSES_MODE) {
            glassesMode = sp.getBoolean(PREF_GLASSES_MODE, false)
            if (isRunning.value == true) bindCamera()
        }
    }

    override fun onCreate() {
        super.onCreate()
        cameraExecutor = Executors.newSingleThreadExecutor()
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        thresholdMillis = prefs.getLong(PREF_THRESHOLD_MS, DEFAULT_THRESHOLD_MS)
        glassesMode = prefs.getBoolean(PREF_GLASSES_MODE, false)
        earlyWarningEnabled = prefs.getBoolean(PREF_EARLY_WARNING, true)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        createNotificationChannels()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                ttsReady = true
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopMonitoringAndSelf()
                return START_NOT_STICKY
            }
            ACTION_DISMISS_ALARM -> {
                stopAlarm()
                return START_STICKY
            }
        }
        if (isRunning.value != true) {
            acquireWakeLock()
            startForeground(MONITORING_NOTIFICATION_ID, buildMonitoringNotification())
            isRunning.postValue(true)
            bindCamera()
        }
        return START_STICKY
    }

    private fun bindCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            eyeAnalyzer?.close()
            eyeAnalyzer = EyeAnalyzer(
                onResult = { state -> handleEyeState(state) },
                closedThreshold = if (glassesMode) THRESHOLD_GLASSES else THRESHOLD_NORMAL
            )
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, eyeAnalyzer!!) }
            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (e: Exception) {
                android.util.Log.e("DrowsinessService", "Camera bind failed: ${e.message}", e)
                stopMonitoringAndSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleEyeState(state: EyeAnalyzer.EyeState) {
        eyeState.postValue(state)
        when (state) {
            is EyeAnalyzer.EyeState.EyesOpen -> {
                eyesClosedSinceMillis = null
                closedElapsedMs.postValue(0L)
                warningSpoken = false
                droopWarningSpoken = false
                isDrooping.postValue(false)
                if (isAlarming.value == true) stopAlarm()
            }
            is EyeAnalyzer.EyeState.EyesDrooping -> {
                eyesClosedSinceMillis = null
                closedElapsedMs.postValue(0L)
                isDrooping.postValue(true)
                if (!droopWarningSpoken && earlyWarningEnabled) {
                    droopWarningSpoken = true
                    speakDroopWarning()
                }
            }
            is EyeAnalyzer.EyeState.NoFaceDetected -> {
                // A brief detection dropout shouldn't cancel an in-progress
                // drowsy episode, so the closed-eye timer is left untouched.
            }
            is EyeAnalyzer.EyeState.EyesClosed -> {
                val now = System.currentTimeMillis()
                val since = eyesClosedSinceMillis ?: now.also { eyesClosedSinceMillis = it }
                val elapsed = now - since
                closedElapsedMs.postValue(elapsed)
                if (!warningSpoken && elapsed >= thresholdMillis / 2) {
                    speakWarning()
                }
                if (elapsed >= thresholdMillis && isAlarming.value != true) {
                    triggerAlarm()
                }
            }
        }
    }

    private fun triggerAlarm() {
        isAlarming.postValue(true)

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
                setDataSource(this@DrowsinessService, alarmUri)
                isLooping = true
                setVolume(1.0f, 1.0f)
                prepare()
                start()
            }
        } catch (_: Exception) {
            // If the alarm sound can't be played, vibration plus the
            // full-screen alert still keep the user covered.
        }

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(ALARM_NOTIFICATION_ID, buildAlarmNotification())
    }

    private fun speakDroopWarning() {
        if (!ttsReady) return
        tts?.speak("Stay awake", TextToSpeech.QUEUE_FLUSH, null, "droop_warning")
    }

    private fun speakWarning() {
        if (!ttsReady) return
        // Only speak if there's enough time before the alarm — no point
        // starting a warning that will be cut off immediately.
        val timeUntilAlarm = thresholdMillis - (thresholdMillis / 2)
        if (timeUntilAlarm < 800) return
        warningSpoken = true
        tts?.speak("Hey, eyes open", TextToSpeech.QUEUE_FLUSH, null, "drowsy_warning")
    }

    private fun stopAlarm() {
        isAlarming.postValue(false)
        eyesClosedSinceMillis = null
        closedElapsedMs.postValue(0L)
        warningSpoken = false
        droopWarningSpoken = false
        isDrooping.postValue(false)
        vibrator?.cancel()
        mediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        mediaPlayer = null
        getSystemService(NotificationManager::class.java).cancel(ALARM_NOTIFICATION_ID)
    }

    private fun stopMonitoringAndSelf() {
        stopAlarm()
        cameraProvider?.unbindAll()
        releaseWakeLock()
        isRunning.postValue(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Vigil::ServiceWakeLock"
        ).apply { acquire(8 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        val monitoring = NotificationChannel(
            MONITORING_CHANNEL_ID, "Monitoring", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Ongoing drowsiness monitoring status" }
        val alarm = NotificationChannel(
            ALARM_CHANNEL_ID, "Drowsiness alarm", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when your eyes have been closed too long"
            enableVibration(true)
            lightColor = Color.RED
        }
        nm.createNotificationChannel(monitoring)
        nm.createNotificationChannel(alarm)
    }

    private fun buildMonitoringNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, DrowsinessService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, MONITORING_CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_monitoring_title))
            .setContentText(getString(R.string.notif_monitoring_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.stop_monitoring), stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun buildAlarmNotification(): Notification {
        val fullScreenIntent = Intent(this, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 1, fullScreenIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, ALARM_CHANNEL_ID)
            .setContentTitle(getString(R.string.wake_up))
            .setContentText(getString(R.string.notif_alarm_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .setAutoCancel(true)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAlarm()
        cameraProvider?.unbindAll()
        releaseWakeLock()
        cameraExecutor.shutdown()
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        tts?.stop()
        tts?.shutdown()
        eyeAnalyzer?.close()
        isRunning.postValue(false)
    }
}
