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
 * Claude API 판정 — 키가 있으면 **주 LLM 엔진**, 없으면 온디바이스 폴백.
 *
 * 2026-08-14 팀 결정: 온디바이스 추론이 15~19초로 발표·데모에 못 쓰는 속도라
 * Haiku(실측 2.1초)를 주 엔진으로 쓴다. 원 기획의 "외부 LLM API 미사용"은
 * 온디바이스 실측(완료)과 함께 로드맵으로 유지 — OnDeviceLlm이 그 증거이자
 * 키 없는 환경의 폴백이다. 안전핀 구조(규칙-저위험만 검토, 상향 전용)는
 * 엔진과 무관하게 동일하다.
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
        val elapsedMs: Long,
        /** 응답 원문 — "저위험 동의"와 "형식 위반/무응답"을 구분하는 데 쓴다 */
        val raw: String
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
            Result(LlmRiskJudge.parse(text), ms, text)
        }.onFailure { Log.w(TAG, "Claude API 호출 실패", it) }.getOrNull()
    }
}
