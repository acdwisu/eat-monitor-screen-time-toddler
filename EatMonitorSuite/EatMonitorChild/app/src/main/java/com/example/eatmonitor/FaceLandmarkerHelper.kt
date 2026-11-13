package com.example.eatmonitor

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import java.nio.ByteBuffer

class FaceLandmarkerHelper(
    private val context: Context,
    private val modelAssetPath: String = "face_landmarker.task"
) {
    private var faceLandmarker: FaceLandmarker? = null
    private var reusableBitmap: Bitmap? = null

    init {
        setupFaceLandmarker()
    }

    private fun setupFaceLandmarker() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(modelAssetPath)
                .setDelegate(Delegate.CPU)
                .build()

            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(1)
                .setMinFaceDetectionConfidence(0.5f)
                .setMinFacePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()

            faceLandmarker = FaceLandmarker.createFromOptions(context, options)
        } catch (e: Exception) {
            Log.e("FaceLandmarkerHelper", "Gagal memuat face_landmarker.task dari assets!", e)
        }
    }

    fun detectLandmarks(imageProxy: ImageProxy): List<Point2D>? {
        val landmarker = faceLandmarker ?: return null

        val width = imageProxy.width
        val height = imageProxy.height
        val plane = imageProxy.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        val bitmapWidth = width + rowPadding / pixelStride
        val bitmap = reusableBitmap?.takeIf { it.width == bitmapWidth && it.height == height }
            ?: Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888).also {
                reusableBitmap = it
            }

        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)

        val finalBitmap = if (rowPadding > 0) {
            Bitmap.createBitmap(bitmap, 0, 0, width, height)
        } else {
            bitmap
        }

        val mpImage = BitmapImageBuilder(finalBitmap).build()
        val imageProcessingOptions = ImageProcessingOptions.builder()
            .setRotationDegrees(imageProxy.imageInfo.rotationDegrees)
            .build()

        val result = landmarker.detect(mpImage, imageProcessingOptions)
        val firstFace = result.faceLandmarks().firstOrNull() ?: return null

        return firstFace.map { Point2D(it.x(), it.y()) }
    }

    fun close() {
        faceLandmarker?.close()
        faceLandmarker = null
        reusableBitmap?.recycle()
        reusableBitmap = null
    }
}
