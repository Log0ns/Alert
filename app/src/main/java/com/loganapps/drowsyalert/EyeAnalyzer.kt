package com.loganapps.drowsyalert

import android.annotation.SuppressLint
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

class EyeAnalyzer(
    private val onResult: (EyeState) -> Unit,
    private val closedThreshold: Float = 0.35f,
    private val droopThreshold: Float = 0.65f,
    private val droopSustainMs: Long = 2_000L
) : androidx.camera.core.ImageAnalysis.Analyzer {

    sealed class EyeState {
        object NoFaceDetected : EyeState()
        data class EyesOpen(val avgProbability: Float) : EyeState()
        data class EyesDrooping(val avgProbability: Float) : EyeState()
        data class EyesClosed(val avgProbability: Float) : EyeState()
    }

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()
    )

    // Track how long eyes have been in the droop range continuously
    private var droopingSinceMillis: Long? = null

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        detector.process(inputImage)
            .addOnSuccessListener { faces ->
                val face = faces.firstOrNull()
                if (face == null) {
                    droopingSinceMillis = null
                    onResult(EyeState.NoFaceDetected)
                } else {
                    val left = face.leftEyeOpenProbability ?: 1f
                    val right = face.rightEyeOpenProbability ?: 1f
                    val avg = (left + right) / 2f

                    when {
                        avg < closedThreshold -> {
                            droopingSinceMillis = null
                            onResult(EyeState.EyesClosed(avg))
                        }
                        avg < droopThreshold -> {
                            val now = System.currentTimeMillis()
                            val since = droopingSinceMillis ?: now.also { droopingSinceMillis = it }
                            if (now - since >= droopSustainMs) {
                                onResult(EyeState.EyesDrooping(avg))
                            } else {
                                // Still in droop range but not sustained yet — report as open
                                // so we don't trigger false positives on blinks or glances down
                                onResult(EyeState.EyesOpen(avg))
                            }
                        }
                        else -> {
                            droopingSinceMillis = null
                            onResult(EyeState.EyesOpen(avg))
                        }
                    }
                }
            }
            .addOnFailureListener {
                droopingSinceMillis = null
                onResult(EyeState.NoFaceDetected)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    fun close() {
        detector.close()
    }
}
