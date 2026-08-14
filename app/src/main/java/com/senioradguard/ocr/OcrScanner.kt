package com.senioradguard.ocr

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 글자가 없는 광고 이미지를 화면 픽셀에서 읽는다.
 *
 * ## 왜 MediaProjection이 아닌가
 * 예전 구현은 MediaProjection이었다. 그건 쓸 때마다 시스템 동의 창이 뜨고 포그라운드
 * 서비스가 필요하다 — 어르신에게 "화면 녹화를 허용하시겠습니까?"를 계속 묻는 셈이다.
 * 접근성 서비스는 [AccessibilityService.takeScreenshot]으로 동의 없이 화면을 받을 수
 * 있고, 우리는 이미 그 권한을 받아 쓰고 있다. **API 30부터만 된다** — 그 아래에서는
 * OCR 없이 동작한다.
 *
 * ## 화면 픽셀은 기기 밖으로 나가지 않는다
 * ML Kit 한국어 인식기는 온디바이스다. 캡처한 비트맵도, 읽어낸 글자도 네트워크로
 * 보내지 않는다. 읽은 결과는 Layer 1 규칙에만 넘긴다 — Layer 2(LLM)로 보내면
 * 화면 이미지에서 뽑은 내용이 외부로 나가게 되므로 그렇게 하지 않는다.
 *
 * ## 비용
 * takeScreenshot은 시스템이 초당 1회로 제한한다(그보다 자주 부르면 실패한다).
 * 여기서도 [MIN_INTERVAL_MS] 간격을 두고, 스크롤 중에는 아예 부르지 않는다 —
 * 어차피 다음 프레임이면 잘라낼 자리가 사라진다.
 */
@RequiresApi(Build.VERSION_CODES.R)
class OcrScanner(private val service: AccessibilityService) {

    private val recognizer by lazy {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }

    private var lastRunAt = 0L

    /** 지금 캡처해도 되는가. 시스템 제한과 우리 간격을 함께 본다. */
    fun ready(): Boolean = SystemClock.uptimeMillis() - lastRunAt >= MIN_INTERVAL_MS

    /**
     * 화면 전체를 읽어 **글자 덩어리와 그 위치**를 돌려준다.
     *
     * 처음에는 "이미지 노드를 찾아 그 자리만 잘라 읽는" 방식이었다. 크롬에서 쓸 수
     * 없었다 — 웹 이미지가 ImageView로 오지 않고 전부 `android.view.View`이며, 그나마도
     * 리프가 아니라 자식을 가진 컨테이너로 온다. 실기기에서 후보가 계속 0개였다.
     *
     * 그래서 뒤집었다. **어디가 이미지인지 알 필요가 없다.** ML Kit이 인식한 글자마다
     * 화면 좌표를 함께 주므로, 화면을 통째로 읽고 "광고"라고 적힌 자리를 그대로 쓰면
     * 된다. 노드 트리에 안 실리는 글자(이미지 안에 그려진 글자)가 목적이었으니
     * 트리를 거치지 않는 편이 오히려 곧다.
     *
     * @return 글자 덩어리 → 화면 좌표. 캡처나 인식이 실패하면 빈 목록.
     */
    suspend fun readScreen(): List<Pair<String, Rect>> {
        if (!ready()) return emptyList()
        lastRunAt = SystemClock.uptimeMillis()

        val screen = captureScreen() ?: return emptyList()
        return try {
            recognizeBlocks(screen)
        } finally {
            screen.recycle()
        }
    }

    private suspend fun recognizeBlocks(bitmap: Bitmap): List<Pair<String, Rect>> =
        suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { text ->
                    // 줄 단위로 본다. 덩어리(TextBlock)는 문단이라 "광고"와 본문이
                    // 한 상자에 섞여 테두리가 화면 절반을 덮는다.
                    val out = text.textBlocks
                        .flatMap { it.lines }
                        .mapNotNull { line ->
                            val box = line.boundingBox ?: return@mapNotNull null
                            line.text.trim() to Rect(box)
                        }
                    cont.resume(out)
                }
                .addOnFailureListener {
                    Log.w(TAG, "OCR 실패: ${it.message}")
                    cont.resume(emptyList())
                }
        }

    /**
     * 화면 캡처. HardwareBuffer로 오므로 소프트웨어 비트맵으로 복사해야 자를 수 있다.
     * 복사하지 않으면 createBitmap이 "unable to getPixels()"로 죽는다.
     */
    private suspend fun captureScreen(): Bitmap? =
        suspendCancellableCoroutine { cont ->
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
                        cont.resume(bitmap)
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "화면 캡처 실패 code=$errorCode")
                        cont.resume(null)
                    }
                }
            )
        }

    private companion object {
        const val TAG = "AdGuardOcr"

        /** 시스템 제한이 1초다. 여유를 둬 실패를 줄인다. */
        const val MIN_INTERVAL_MS = 1500L
    }
}
