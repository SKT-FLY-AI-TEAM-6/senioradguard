package com.senioradguard.vision

import android.util.Log
import com.senioradguard.agent.GeminiClient
import com.senioradguard.risk.RiskCategory
import com.senioradguard.risk.RiskLevel
import com.senioradguard.risk.RiskVerdict
import org.json.JSONArray
import org.json.JSONObject

/**
 * Agent6의 Gemini 구현 — 잘라낸 화면 조각을 **그림째** 보고 판단한다.
 *
 * ## OCR + 상표 인식 + 판별을 한 번에 하는 이유
 * 온디바이스 OCR로 글자를 뽑고, 별도 표 상표를 매칭하고, 그 결과로 위험도를
 * 매기는 3단 구성도 가능하다. 그렇게 하지 않는다.
 *  - OCR은 "Laptop BIG SALE"은 읽지만 **로고 그림은 못 읽는다.** 상표를 알아보는 게
 *    목표인데 가장 중요한 단서를 놓친다
 *  - 상표 표기는 흔들린다("삼성전자", "SAMSUNG", 로고만). 매칭 표를 우리가
 *    유지하는 순간 그 표가 곧 부채가 된다
 *  - 과장 광고("운동 없이 한 달 10kg")는 글자를 읽는 것만으로는 판정이 안 되고
 *    **의미**를 읽어야 한다
 *
 * 대신 확인된 상표를 [BrandWhitelist]로 검증한다 — 모델이 "삼성"이라고 답해도
 * 그 이름이 우리 목록에 있어야만 위험도를 낮춘다.
 *
 * ## 화면 전체를 보내지 않는다
 * 넘기는 것은 ROI 하나를 잘라 640px 이하로 줄인 JPEG다. 화면 전체를 보내면
 * 카톡 대화·사진·계좌번호가 그대로 나가고, 그건 되돌릴 수 없다.
 */
class GeminiVisionClassifier(
    apiKey: String,
    model: String = GeminiClient.DEFAULT_MODEL,
    private val client: GeminiClient = GeminiClient(apiKey, model)
) : VisionRiskClassifier {

    companion object {
        private const val TAG = "AdGuardVision"

        /** 함께 넘길 화면 문구의 길이 상한. */
        private const val MAX_TEXT_CHARS = 200

        private val SYSTEM_PROMPT = """
            당신은 한국 노인 사용자를 광고 피해에서 보호하는 판별기입니다.
            화면에서 잘라낸 이미지 한 장을 보고 위험도를 매기세요.

            이미지에서 먼저 읽어낼 것: 광고주 상표(브랜드), 문구, 무엇을 하라고
            시키는지. 로고만 있고 글자가 없어도 어느 회사인지 알아보세요.

            등급 기준입니다. 이 세 가지로 나뉜다고 생각하세요.

            [하 · score 0~39] 안전
            - 알려진 기업·기관의 정상 광고 (삼성, 쿠팡, 은행, 통신사, 공공기관 등)
            - 공식 OTT·언론사·플랫폼의 콘텐츠
            - 상품을 파는 평범한 광고로, 효과를 부풀리지 않는 것

            [중 · score 40~69] 주의
            - 효과를 부풀린 광고: "운동 없이 한 달 10kg", "먹기만 하면 무릎 통증 끝",
              "이것만 알면 월 500만원" 같은 것. 불법은 아니지만 어르신이 가장 많이 속습니다
            - 광고주를 알 수 없는 건강식품·의료·투자 권유
            - 개인정보나 전화번호를 요구하는 이벤트·경품

            [상 · score 70~100] 위험
            - 성인·선정적 내용
            - 도박·토토·카지노
            - 불법 다시보기·불법 스트리밍·저작권 침해 사이트
            - 유명 브랜드나 공공기관을 사칭한 것
            - 앱 설치 파일을 직접 받게 하거나, 당첨·긴급을 앞세워 재촉하는 것

            중요합니다.
            - 알려진 기업의 정상 광고에 경고를 띄우면 사용자가 경고 자체를 무시하게
              되어, 놓친 위험보다 해롭습니다. 확신이 없으면 낮은 쪽으로 두세요
            - 다만 성인·도박·불법 콘텐츠는 확신이 없어도 '상'으로 두세요.
              이 셋은 놓쳤을 때의 피해가 오탐보다 훨씬 큽니다
            - brand에는 알아본 상표를 그대로 적으세요. 모르겠으면 빈 문자열입니다
            - reasons는 한국어 한 줄짜리 문장으로, 어르신이 읽고 이해할 수 있게 쓰세요.
              "왜 이 등급인지"가 드러나야 합니다
        """.trimIndent()

        private val SEARCH_RESULT_HINT = """
            이것은 검색 결과 한 칸입니다. 같은 검색어에 공식 서비스와 불법 사이트가
            나란히 나옵니다. 공식 OTT·언론사·공공기관이면 '하', 불법 다시보기나
            도박 사이트면 '상'으로 명확히 갈라 주세요.
        """.trimIndent()

        private val SCHEMA = GeminiClient.objectSchema(
            "category" to GeminiClient.stringField(
                *RiskCategory.entries.map { it.name }.toTypedArray()
            ),
            "score" to GeminiClient.numberField(),
            "brand" to GeminiClient.stringField(),
            "reasons" to GeminiClient.stringArrayField()
        )
    }

    override val source = RiskVerdict.SOURCE_VISION

    override suspend fun classify(request: VisionRequest): RiskVerdict? {
        if (request.jpegBase64.isBlank()) return null

        val payload = client.generateJson(
            systemPrompt = SYSTEM_PROMPT,
            userPrompt = prompt(request),
            schema = SCHEMA,
            imageJpegBase64 = request.jpegBase64
        ) ?: return null

        return runCatching {
            val json = JSONObject(payload)
            val score = json.getDouble("score").toInt().coerceIn(0, 100)
            RiskVerdict(
                category = RiskCategory.parse(json.optString("category")),
                level = RiskLevel.of(score),
                score = score,
                reasons = json.optJSONArray("reasons").toStringList()
                    .take(RiskVerdict.MAX_REASONS),
                source = source,
                brand = json.optString("brand").trim().take(40)
            )
        }.getOrElse {
            Log.e(TAG, "판정 파싱 실패: ${payload.take(200)}")
            null
        }
    }

    /**
     * 그림 옆에 붙이는 문맥. 접근성 트리에서 이미 읽어낸 것이 있으면 함께 준다 —
     * 그림에서 다시 읽게 하는 것보다 정확하고, 없으면 없는 대로 그림만으로 답한다.
     */
    private fun prompt(request: VisionRequest): String {
        val lines = mutableListOf<String>()
        lines += "종류: ${request.kind.label}"
        lines += "출처: ${request.sourceKey.ifBlank { "알 수 없음" }}"
        if (request.shownUrl.isNotBlank()) lines += "화면에 보이는 주소: ${request.shownUrl}"
        if (request.shownText.isNotBlank()) {
            lines += "화면에서 읽은 글자: ${request.shownText.take(MAX_TEXT_CHARS)}"
        }
        if (request.kind == RoiKind.SEARCH_RESULT) lines += SEARCH_RESULT_HINT

        return (lines + "" + "이 이미지의 위험도를 판단하세요.").joinToString("\n")
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).trim().takeIf(String::isNotEmpty) }
    }
}
