package com.senioradguard.agent

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gemini generateContent 호출 한 겹. 판별기들이 공유한다.
 *
 * ⚠️ **API 키가 APK 안에 들어간다.** 개발 중에만 쓰는 경로다. APK는 누구나 뜯을
 * 수 있어 문자열이 그대로 추출되고, 추출된 키로 남이 우리 할당량을 쓸 수 있다.
 * 배포 전에는 우리 서버를 거치는 구현으로 반드시 교체할 것 — 그때 바꿀 곳은
 * 이 파일 하나다.
 *
 * SDK를 쓰지 않고 HttpURLConnection으로 직접 부른다. BlacklistRepository와 같은
 * 방식이라 의존성이 늘지 않는다.
 *
 * ## 상한(429) 휴식은 왜 전역인가
 * 무료 티어 상한은 **키 단위**다. 광고 판별기가 상한에 걸렸는데 URL 판별기가 그
 * 사실을 모르고 계속 두드리면, 둘이 번갈아 429를 받으며 쿨다운이 영영 끝나지
 * 않는다. 인스턴스가 아니라 companion에 두어 키를 쓰는 모두가 함께 쉰다.
 */
class GeminiClient(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL
) {

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

        /**
         * 429(무료 티어 상한)를 받은 뒤 쉬는 시간.
         *
         * 실패는 캐시에 남기지 않는 설계라, 상한에 걸린 상태에서는 유휴가 돌 때마다
         * 같은 카드를 다시 두드린다. 팀원 실기기 확인에서 몇 초 만에 호출 수십 건이
         * 나갔다. 한 번 막히면 잠시 쉬는 편이 낫다.
         */
        private const val QUOTA_COOLDOWN_MS = 5 * 60 * 1000L

        /** 키 단위 상한이므로 이 값도 인스턴스가 아니라 전역이다. */
        @Volatile
        private var cooldownUntil = 0L

        /** 응답 스키마를 만드는 짧은 보조 — 판별기마다 같은 코드를 반복하지 않게 한다. */
        fun objectSchema(vararg properties: Pair<String, JSONObject>): JSONObject =
            JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().apply {
                    properties.forEach { (name, spec) -> put(name, spec) }
                })
                put("required", JSONArray(properties.map { it.first }))
            }

        fun stringField(vararg enumValues: String): JSONObject =
            JSONObject().put("type", "STRING").apply {
                if (enumValues.isNotEmpty()) put("enum", JSONArray(enumValues.toList()))
            }

        fun numberField(): JSONObject = JSONObject().put("type", "NUMBER")

        fun booleanField(): JSONObject = JSONObject().put("type", "BOOLEAN")

        fun stringArrayField(): JSONObject = JSONObject().apply {
            put("type", "ARRAY")
            put("items", JSONObject().put("type", "STRING"))
        }
    }

    /**
     * @param schema responseSchema. 주면 응답이 스키마를 만족하도록 강제되어 JSON
     *        파싱 실패가 사라진다 — 모델이 설명 문장을 덧붙이거나 코드 펜스를
     *        두르는 일이 없어진다
     * @return 모델이 돌려준 JSON 문자열. 실패·타임아웃·상한이면 null
     */
    suspend fun generateJson(
        systemPrompt: String,
        userPrompt: String,
        schema: JSONObject,
        maxOutputTokens: Int = 1024
    ): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || userPrompt.isBlank()) return@withContext null
        if (System.currentTimeMillis() < cooldownUntil) return@withContext null

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

            connection.outputStream.use {
                it.write(
                    body(systemPrompt, userPrompt, schema, maxOutputTokens)
                        .toByteArray(Charsets.UTF_8)
                )
            }

            val code = connection.responseCode
            if (code != 200) {
                // 본문을 함께 남긴다. 모델명 오타·할당량 초과·키 문제는 여기서만
                // 구분되고, 없으면 "그냥 안 된다"로 끝나 원인을 못 찾는다.
                val error = connection.errorStream?.bufferedReader()?.readText().orEmpty()
                Log.e(TAG, "Gemini HTTP $code: ${error.take(400)}")
                if (code == 429) {
                    cooldownUntil = System.currentTimeMillis() + QUOTA_COOLDOWN_MS
                    Log.i(TAG, "gemini 상한 초과 — ${QUOTA_COOLDOWN_MS / 60_000}분간 판별 중단")
                }
                return@withContext null
            }

            payloadOf(connection.inputStream.bufferedReader().readText())
        } catch (e: Exception) {
            Log.e(TAG, "Gemini 호출 실패: ${e.javaClass.simpleName} ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun body(
        systemPrompt: String,
        userPrompt: String,
        schema: JSONObject,
        maxOutputTokens: Int
    ): String = JSONObject().apply {
        put("systemInstruction", JSONObject().put(
            "parts", JSONArray().put(JSONObject().put("text", systemPrompt))
        ))
        put("contents", JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
        }))
        put("generationConfig", JSONObject().apply {
            put("temperature", 0)
            // 2.5 이후 계열은 사고(thinking) 토큰이 기본으로 켜져 있어 출력 상한을
            // 먼저 소진하면 본문 없이 finishReason=MAX_TOKENS만 돌아온다.
            // 판정 JSON은 100토큰이면 충분하지만 사고 몫을 넉넉히 남긴다.
            put("maxOutputTokens", maxOutputTokens)
            put("responseMimeType", "application/json")
            put("responseSchema", schema)
        })
    }.toString()

    private fun payloadOf(body: String): String? {
        val first = JSONObject(body).optJSONArray("candidates")?.optJSONObject(0)

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
        return payload
    }
}
