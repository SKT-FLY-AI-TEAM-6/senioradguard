package com.senioradguard.detector

import android.graphics.Bitmap
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.Log

/**
 * ScreenCaptureHelper
 *
 * ScreenCaptureService가 MediaProjection + VirtualDisplay + ImageReader를 만들어
 * [attach]로 등록해두면, AccessibilityService는 필요할 때만 [getLatestFrame]을 호출해
 * 그 시점의 최신 화면을 동기적으로 꺼내 쓴다 (풀 방식 — VirtualDisplay 자체는 계속
 * 대기 상태로 떠 있고, 실제 캡처/디코딩은 트리거될 때만 수행).
 */
object ScreenCaptureHelper {

    private const val TAG = "ScreenCaptureHelper"

    @Volatile
    private var mediaProjection: MediaProjection? = null

    @Volatile
    private var imageReader: ImageReader? = null

    @Volatile
    private var virtualDisplay: VirtualDisplay? = null

    fun attach(projection: MediaProjection, reader: ImageReader, display: VirtualDisplay) {
        mediaProjection = projection
        imageReader = reader
        virtualDisplay = display
    }

    fun detach() {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { mediaProjection?.stop() }
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
    }

    fun isReady(): Boolean = imageReader != null

    /**
     * 현재 화면의 최신 프레임을 동기적으로 캡처해 Bitmap으로 반환한다.
     * VirtualDisplay가 아직 준비되지 않았거나 프레임이 없으면 null.
     */
    fun getLatestFrame(): Bitmap? {
        val reader = imageReader ?: return null
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            Log.w(TAG, "acquireLatestImage failed", e)
            null
        } ?: return null

        return try {
            imageToBitmap(image)
        } catch (e: Exception) {
            Log.w(TAG, "image to bitmap conversion failed", e)
            null
        } finally {
            image.close()
        }
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val padded = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        padded.copyPixelsFromBuffer(buffer)

        return if (rowPadding == 0) {
            padded
        } else {
            val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            padded.recycle()
            cropped
        }
    }
}
