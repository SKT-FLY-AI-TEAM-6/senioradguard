package com.senioradguard.agent

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Agent2 — Gemini로 카드가 광고인지 판별한다.
 *
 * ⚠️ **API 키가 APK 안에 들어간다.** 개발 중에만 쓰는 경로다. APK는 누구나 뜯을
 * 수 있어 문자열이 그대로 추출되고, 추출된 키로 남이 우리 할당량을 쓸 수 있다.
 * 배포 전에는 우리 서버를 거치는 구현으로 반드시 교체할 것 — 그때 바꿀 곳은
 * AdClassifier 구현체 하나뿐이다.
 *
 * SDK를 쓰지 않고 HttpURLConnection으로 직접 부른다. 프로젝트의 다른 네트워크
 * 코드(KakaoNotifier, BlacklistRepository)와 같은 방식이라 의존성이 늘지 않는다.
 */
class GeminiClassifier(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL
) : AdClassifier {

    companion object {
        private const val TAG = "AdGuardLlm"

        /**
         * 분류 작업이라 지연·비용이 품질보다 중요해 flash 계열을 쓴다.
         *
         * 모델명은 실제로 만료된다. gemini-2.0-flash는 "no longer available",
         * gemini-2.5-flash와 2.5-flash-lite는 "no longer available to new users"로
         * 404가 돌아왔다. **ListModels에 보이는 것과 이 키로 부를 수 있는 것은
         * 다르다** — 목록만 보고 고르지 말고 실제로 한 번 호출해 확인할 것:
         *
         *   curl -H "x-goog-api-key: $KEY" -H 'Content-Type: application/json' -X POST \
         *     -d '{"contents":[{"parts":[{"text":"hi"}]}]}' \
         *     "https://generativelanguage.googleapis.com/v1beta/models/<모델>:generateContent"
         *
         * 3.5-flash와 3.5-flash-lite를 판정 4종(쇼핑몰 자체상품·기사·실제광고·
         * 설치유도)으로 비교했을 때 둘 다 전부 맞혔다. 항상 떠 있는 서비스라
         * 지연·비용이 중요해 lite를 고른다. 판별 정확도가 부족해지면
         * gemini-3.5-flash로 올리면 된다.
         */
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"

        // 모바일 회선은 첫 연결이 느릴 때가 많다. 5초로 잡았더니 실기기에서
        // 회선이 느려진 순간 SocketTimeoutException으로 판별이 통째로 날아갔다.
        // 어차피 백그라운드 작업이고 결과는 캐시되므로 넉넉히 준다.
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000

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
    }

    override val source = "LLM"

    override suspend fun classify(text: String): Verdict? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || text.isBlank()) return@withContext null

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        var connection: HttpURLConnection? = null
        try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("x-goog-api-key", apiKey)
                doOutput = true
            }

            connection.outputStream.use { it.write(requestBody(text).toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            if (code != 200) {
                // 본문을 함께 남긴다. 모델명 오타·할당량 초과·키 문제는 여기서만
                // 구분되고, 없으면 "그냥 안 된다"로 끝나 원인을 못 찾는다.
                val error = connection.errorStream?.bufferedReader()?.readText().orEmpty()
                Log.e(TAG, "Gemini HTTP $code: ${error.take(400)}")
                return@withContext null
            }

            parseVerdict(connection.inputStream.bufferedReader().readText())
        } catch (e: Exception) {
            Log.e(TAG, "Gemini 호출 실패: ${e.javaClass.simpleName} ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * responseSchema를 주면 응답이 스키마를 만족하도록 강제되어 JSON 파싱 실패가
     * 사라진다. 모델이 설명 문장을 덧붙이거나 코드 펜스를 두르는 일이 없어진다.
     */
    private fun requestBody(text: String): String {
        val schema = JSONObject().apply {
            put("type", "OBJECT")
            put("properties", JSONObject().apply {
                put("isAd", JSONObject().put("type", "BOOLEAN"))
                put("confidence", JSONObject().put("type", "NUMBER"))
                put("reason", JSONObject().put("type", "STRING"))
            })
            put("required", JSONArray(listOf("isAd", "confidence", "reason")))
        }

        return JSONObject().apply {
            put("systemInstruction", JSONObject().put(
                "parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))
            ))
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put(
                    "text", "다음 카드가 광고입니까?\n\n$text"
                )))
            }))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0)
                // 2.5 계열은 사고(thinking) 토큰이 기본으로 켜져 있어 출력 상한을
                // 먼저 소진하면 본문 없이 finishReason=MAX_TOKENS만 돌아온다.
                // 판정 JSON은 100토큰이면 충분하지만 사고 몫을 넉넉히 남긴다.
                put("maxOutputTokens", 1024)
                put("responseMimeType", "application/json")
                put("responseSchema", schema)
            })
        }.toString()
    }

    private fun parseVerdict(body: String): Verdict? {
        val candidates = JSONObject(body).optJSONArray("candidates")
        val first = candidates?.optJSONObject(0)

        // 안전 필터에 걸리거나 토큰이 모자라면 본문 없이 사유만 온다
        if (first == null) {
            Log.e(TAG, "응답에 candidates 없음: ${body.take(300)}")
            return null
        }
        val payload = first.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text")
            .orEmpty()
        if (payload.isBlank()) {
            Log.e(TAG, "본문 없음 finishReason=${first.optString("finishReason")}")
            return null
        }

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
