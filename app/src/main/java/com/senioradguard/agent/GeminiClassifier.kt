package com.senioradguard.agent

import android.util.Log
import org.json.JSONObject

/**
 * Agent2 — Gemini로 카드가 광고인지 판별한다.
 *
 * HTTP·타임아웃·429 휴식은 [GeminiClient]가 맡는다. 여기 남는 것은 이 판별에만
 * 해당하는 것 — 프롬프트, 응답 스키마, 파싱뿐이다.
 *
 * ⚠️ 프로덕션에서는 키를 앱에 두면 안 된다. 교체점은 [AdClassifier] 구현체이거나
 * [GeminiClient] 하나다.
 */
class GeminiClassifier(
    apiKey: String,
    model: String = GeminiClient.DEFAULT_MODEL,
    private val client: GeminiClient = GeminiClient(apiKey, model)
) : AdClassifier {

    companion object {
        private const val TAG = "AdGuardLlm"

        private val SYSTEM_PROMPT = """
            당신은 한국 노인 사용자를 광고 피해에서 보호하는 판별기입니다.
            화면 카드 하나의 텍스트를 받아 그것이 '광고'인지 판단하세요.

            광고로 볼 것: 상품·서비스 구매나 앱 설치를 유도하는 홍보물,
            협찬/스폰서 콘텐츠, 이벤트·경품으로 개인정보나 가입을 유도하는 것.

            광고가 아닌 것: 뉴스 기사와 본문, 사용자가 올린 게시물과 댓글,
            앱 자체의 메뉴·버튼·설정, 검색 결과 목록, 날씨·시간 같은 정보 표시.

            주의: 쇼핑몰 안에서 그 쇼핑몰이 파는 상품 목록은 광고가 아닙니다.
            사용자가 물건을 사러 들어온 곳이므로 정상 콘텐츠입니다.
            제3자 광고이거나 맥락에 어울리지 않게 끼어든 홍보일 때만 광고입니다.

            확신이 없으면 isAd=false로 두세요. 잘못된 경고는 사용자가 앱을
            믿지 않게 만들어, 놓친 광고보다 해롭습니다.
        """.trimIndent()

        private val SCHEMA = GeminiClient.objectSchema(
            "isAd" to GeminiClient.booleanField(),
            "confidence" to GeminiClient.numberField(),
            "reason" to GeminiClient.stringField()
        )
    }

    override val source = "LLM"

    override suspend fun classify(text: String, sourceKey: String): Verdict? {
        if (text.isBlank()) return null

        // 출처를 함께 넘긴다. 같은 "70% 할인 무료배송"이라도 쇼핑몰에서 나오면 그
        // 앱의 본래 기능이고 뉴스 사이트에서 나오면 끼어든 광고다.
        val prompt = "출처: ${sourceKey.ifBlank { "알 수 없음" }}\n" +
            "다음 카드가 광고입니까?\n\n$text"

        val payload = client.generateJson(SYSTEM_PROMPT, prompt, SCHEMA) ?: return null

        return runCatching {
            val json = JSONObject(payload)
            Verdict(
                isAd = json.getBoolean("isAd"),
                // 모델이 0~1을 벗어난 값을 줄 수 있어 잘라낸다
                confidence = json.getDouble("confidence").toFloat().coerceIn(0f, 1f),
                reason = json.optString("reason").take(120)
            )
        }.getOrElse {
            Log.e(TAG, "판정 파싱 실패: ${payload.take(200)}")
            null
        }
    }
}
