package com.loganapps.drowsyalert

import android.annotation.SuppressLint
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

/**
 * Wraps ML Kit's on-device Face Detector, configured to classify eye-open
 * probability for each detected face. Everything runs locally on the device;
 * no image data leaves the phone.
 */
class EyeAnalyzer(
    private val onResult: (EyeState) -> Unit
) : androidx.camera.core.ImageAnalysis.Analyzer {

    sealed class EyeState {
        object NoFaceDetected : EyeState()
        data class EyesOpen(val avgProbability: Float) : EyeState()
        data class EyesClosed(val avgProbability: Float) : EyeState()
    }

    // Below this average open-probability, we consider the eyes closed.
    private val closedThreshold = 0.35f

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()
    )

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
                    onResult(EyeState.NoFaceDetected)
                } else {
                    val left = face.leftEyeOpenProbability ?: 1f
                    val right = face.rightEyeOpenProbability ?: 1f
                    val avg = (left + right) / 2f
                    if (avg < closedThreshold) {
                        onResult(EyeState.EyesClosed(avg))
                    } else {
                        onResult(EyeState.EyesOpen(avg))
                    }
                }
            }
            .addOnFailureListener {
                onResult(EyeState.NoFaceDetected)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }
}
