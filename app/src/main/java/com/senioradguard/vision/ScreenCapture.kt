package com.senioradguard.vision

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.Display
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 화면을 찍고 필요한 조각만 잘라낸다.
 *
 * ## 권한이 이미 있다
 * `MediaProjection`(매번 뜨는 "화면 녹화를 시작할까요?" 팝업)이 필요 없다.
 * 접근성 서비스는 설정에 `android:canTakeScreenshot="true"`를 선언하면
 * [AccessibilityService.takeScreenshot]으로 바로 찍을 수 있고, 우리 서비스는 이미
 * 그 권한을 받은 상태다(실기기 `dumpsys accessibility`에서 `capabilities=129` =
 * 창 내용 조회 + 스크린샷).
 *
 * ## 화면은 한 번만 찍고 여러 번 자른다
 * 영역마다 따로 찍으면 호출 간격 제한에 바로 걸린다. 전체를 한 장 찍어 두고
 * ROI 개수만큼 잘라 쓴다.
 *
 * ## 제약 세 가지 (전부 시스템이 정한 것)
 *  - **호출 간격**: 너무 자주 부르면 `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`가
 *    돌아온다. [MIN_INTERVAL_MS]로 우리가 먼저 막는다
 *  - **보안 창**: 은행·결제 화면은 `FLAG_SECURE`라
 *    `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW`로 실패한다. 정작 위험한 순간에 못 본다는
 *    뜻이므로, 실패를 조용히 넘기고 다른 레이어에 맡긴다
 *  - **API 30 이상**: 그 아래에서는 이 경로가 아예 없다. minSdk가 26이므로 확인이 필요하다
 */
class ScreenCapture(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "AdGuardVision"

        /**
         * 스크린샷 최소 간격. 시스템 제한보다 넉넉히 잡는다 — 제한에 걸려 돌아오는
         * 실패도 왕복 비용이라, 애초에 부르지 않는 편이 싸다.
         */
        const val MIN_INTERVAL_MS = 1_200L

        /** 판별기에 보낼 조각의 최대 변 길이(px). 이보다 크면 줄인다. */
        const val MAX_EDGE_PX = 640

        /** JPEG 품질. 광고 문구가 읽힐 정도면 충분하다. */
        private const val JPEG_QUALITY = 70

        /** 너무 작은 조각은 판별해도 읽을 게 없다. */
        private const val MIN_EDGE_PX = 48
    }

    private var lastCaptureAt = 0L

    /** 지금 찍어도 되는가. 호출부가 헛수고를 피하는 데 쓴다. */
    fun canCapture(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            SystemClock.uptimeMillis() - lastCaptureAt >= MIN_INTERVAL_MS

    /**
     * 화면 한 장. 실패하면 null — 이유는 로그에만 남기고 호출부는 그냥 건너뛴다.
     *
     * 하드웨어 버퍼로 돌아오므로 소프트웨어 비트맵으로 복사해야 픽셀을 읽을 수 있다.
     * 복사 후 버퍼를 반드시 닫는다 — 안 닫으면 몇 장 만에 그래픽 메모리가 마른다.
     */
    suspend fun captureScreen(): Bitmap? {
        if (!canCapture()) return null
        lastCaptureAt = SystemClock.uptimeMillis()

        return suspendCancellableCoroutine { continuation ->
            runCatching {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    { it.run() },
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            val bitmap = runCatching {
                                Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                                    ?.copy(Bitmap.Config.ARGB_8888, false)
                            }.getOrNull()
                            result.hardwareBuffer.close()
                            if (continuation.isActive) continuation.resume(bitmap)
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.d(TAG, "스크린샷 실패 code=$errorCode")
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                )
            }.onFailure {
                Log.w(TAG, "스크린샷 호출 실패: ${it.javaClass.simpleName}")
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    /**
     * 화면에서 [rect]만 잘라 [MAX_EDGE_PX] 이하로 줄인다.
     *
     * 화면 밖으로 나간 좌표는 잘라 맞춘다 — 스크롤 중에 잡힌 영역은 화면 경계를
     * 넘어가 있는 경우가 흔하고, 그대로 넘기면 createBitmap이 예외를 던진다.
     */
    fun crop(screen: Bitmap, rect: Rect): Bitmap? {
        val left = rect.left.coerceIn(0, screen.width)
        val top = rect.top.coerceIn(0, screen.height)
        val right = rect.right.coerceIn(0, screen.width)
        val bottom = rect.bottom.coerceIn(0, screen.height)
        val width = right - left
        val height = bottom - top
        if (width < MIN_EDGE_PX || height < MIN_EDGE_PX) return null

        val cropped = runCatching {
            Bitmap.createBitmap(screen, left, top, width, height)
        }.getOrNull() ?: return null

        val longest = max(width, height)
        if (longest <= MAX_EDGE_PX) return cropped

        val scale = MAX_EDGE_PX.toFloat() / longest
        val scaled = runCatching {
            Bitmap.createScaledBitmap(
                cropped,
                (width * scale).roundToInt().coerceAtLeast(1),
                (height * scale).roundToInt().coerceAtLeast(1),
                true
            )
        }.getOrNull()
        if (scaled !== cropped) cropped.recycle()
        return scaled
    }

    /** 판별기에 실어 보낼 형태. */
    fun toJpegBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * 지문 계산용 회색조 축소본. [RoiHasher]가 쓰는 9×8 크기로 바로 줄인다.
     *
     * 사람 눈의 밝기 가중치(0.299/0.587/0.114)를 쓴다. 단순 평균을 쓰면 빨간 배경에
     * 흰 글자 같은 광고에서 대비가 뭉개져 서로 다른 배너가 같은 지문이 된다.
     */
    fun grayscale(bitmap: Bitmap): IntArray {
        val small = runCatching {
            Bitmap.createScaledBitmap(bitmap, RoiHasher.WIDTH, RoiHasher.HEIGHT, true)
        }.getOrNull() ?: return IntArray(0)

        val pixels = IntArray(RoiHasher.WIDTH * RoiHasher.HEIGHT)
        small.getPixels(pixels, 0, RoiHasher.WIDTH, 0, 0, RoiHasher.WIDTH, RoiHasher.HEIGHT)
        if (small !== bitmap) small.recycle()

        return IntArray(pixels.size) { i ->
            val p = pixels[i]
            (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)).toInt()
        }
    }
}
