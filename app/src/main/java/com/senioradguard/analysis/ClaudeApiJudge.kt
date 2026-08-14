package com.senioradguard.analysis

import android.os.SystemClock
import android.util.Log
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.senioradguard.BuildConfig
import com.senioradguard.risk.RiskAssessment

/**
 * Claude API 그림자 판정 — **개발 전용 비교 실험**.
 *
 * 기획 원칙은 "외부 LLM API·자체 서버 미사용"이다. 이 클래스는 그 원칙의
 * 예외가 아니라 근거 수집 장치다: 제품 판정(가림막·DB·표시)은 언제나
 * 온디바이스 LLM이 하고, 여기서는 같은 프롬프트를 Claude에도 보내
 * 속도·판정을 나란히 로그로만 남긴다. 결과는 어디에도 반영되지 않는다.
 *
 * local.properties에 CLAUDE_API_KEY가 없으면 [isAvailable]이 false라
 * 요청이 한 건도 나가지 않는다.
 */
object ClaudeApiJudge {

    private const val TAG = "AdGuard"

    /**
     * 비교 대상 모델. 실측 이력:
     * - claude-opus-5 (effort low): 5.4초, 프루지오 관심고객등록 폼을 잡아 중위험 상향
     * - claude-haiku-4-5: 최속·최저가 측정용 (effort 미지원이라 조건 분기)
     */
    private const val MODEL = "claude-haiku-4-5"

    fun isAvailable(): Boolean = BuildConfig.CLAUDE_API_KEY.isNotBlank()

    private val client: AnthropicClient by lazy {
        AnthropicOkHttpClient.builder()
            .apiKey(BuildConfig.CLAUDE_API_KEY)
            .build()
    }

    class Result(
        /** 상향 판정 (중·고위험) — 저위험 동의·형식 위반이면 null */
        val assessment: RiskAssessment.Assessed?,
        val elapsedMs: Long
    )

    /**
     * 온디바이스와 동일한 프롬프트([LlmRiskJudge.buildPrompt])로 판정을 요청하고
     * 소요 시간을 잰다. 실패(네트워크·거부 등)는 null — 비교만 빠질 뿐이다.
     */
    fun judge(finalUrl: String, pageText: String): Result? {
        if (!isAvailable()) return null
        val started = SystemClock.uptimeMillis()
        return runCatching {
            val response = client.messages().create(
                MessageCreateParams.builder()
                    .model(MODEL)
                    .maxTokens(300L)
                    .addUserMessage(LlmRiskJudge.buildPrompt(finalUrl, pageText))
                    .apply {
                        // 두 줄짜리 분류라 저심도면 충분 — 지연 최소화가 목적.
                        // 단 effort는 Opus/Sonnet 5·4.6+ 전용이고 Haiku 4.5는 400을 낸다.
                        if (MODEL.startsWith("claude-opus") || MODEL.startsWith("claude-sonnet")) {
                            outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                        }
                    }
                    .build()
            )
            val text = response.content()
                .mapNotNull { block -> block.text().map { it.text() }.orElse(null) }
                .joinToString(" ")
            val ms = SystemClock.uptimeMillis() - started
            Log.i(
                TAG,
                "Claude API 추론 — ${ms}ms (stop=${response.stopReason()}): " +
                    text.replace('\n', ' ').take(140)
            )
            Result(LlmRiskJudge.parse(text), ms)
        }.onFailure { Log.w(TAG, "Claude API 호출 실패", it) }.getOrNull()
    }
}
