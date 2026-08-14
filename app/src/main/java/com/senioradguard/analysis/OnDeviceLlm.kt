package com.senioradguard.analysis

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File

/**
 * 온디바이스 LLM (MediaPipe LLM Inference) 얇은 래퍼.
 *
 * 기획 원칙: 자체 서버·외부 LLM API를 쓰지 않고 어르신 기기에서 분석한다.
 * 모델은 APK에 넣지 않는다 — 개발 중에는 adb push로 [MODEL_PATH]에 두고,
 * 없으면 [isAvailable]이 false라 규칙 기반 판정만 동작한다 (앱은 정상).
 *
 * 메모리·시간 실측을 위해 로드·추론 시간을 항상 로그로 남긴다 —
 * 설계 문서 4c의 "대상 기기 성능 실측 먼저" 원칙이다. 1.5B q8 모델은
 * 로드 시 약 1.6GB를 상주시키므로 첫 사용 때 게으르게 올린다.
 */
class OnDeviceLlm(private val context: Context) {

    companion object {
        private const val TAG = "AdGuard"

        /** 개발용 모델 경로. adb push <model>.task /data/local/tmp/guardian_llm.task */
        const val MODEL_PATH = "/data/local/tmp/guardian_llm.task"

        /** 프롬프트+응답 합계 토큰 상한 (모델 컨텍스트 4096 안쪽에서 여유 있게) */
        private const val MAX_TOKENS = 1024
    }

    @Volatile
    private var inference: LlmInference? = null

    fun isAvailable(): Boolean = File(MODEL_PATH).exists()

    @Synchronized
    private fun engine(): LlmInference? {
        inference?.let { return it }
        if (!isAvailable()) return null
        val started = SystemClock.uptimeMillis()
        return runCatching {
            LlmInference.createFromOptions(
                context,
                LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(MODEL_PATH)
                    .setMaxTokens(MAX_TOKENS)
                    .build()
            )
        }.onSuccess {
            Log.i(TAG, "LLM 로드 완료 — ${SystemClock.uptimeMillis() - started}ms")
        }.onFailure {
            Log.w(TAG, "LLM 로드 실패", it)
        }.getOrNull()?.also { inference = it }
    }

    /** 동기 추론. 호출부가 IO 디스패처+타임아웃을 책임진다. 실패면 null. */
    fun generate(prompt: String): String? {
        val llm = engine() ?: return null
        val started = SystemClock.uptimeMillis()
        return runCatching { llm.generateResponse(prompt) }
            .onSuccess {
                // 판정 품질 검증용 — 응답 원문 앞부분을 남긴다 (개인정보 아님: 우리
                // 프롬프트에 대한 모델의 등급·이유 답변이다)
                Log.i(
                    TAG,
                    "LLM 추론 — ${SystemClock.uptimeMillis() - started}ms: " +
                        (it?.replace('\n', ' ')?.take(140) ?: "null")
                )
            }
            .onFailure { Log.w(TAG, "LLM 추론 실패", it) }
            .getOrNull()
    }
}
