package com.senioradguard.agent

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Agent2 — Gemini로 광고 여부를 판별한다.
 *
 * StubClassifier와 달리 문맥을 읽는다. 출처(sourceKey)를 함께 넘기는 게 핵심인데,
 * 같은 "70% 할인 무료배송"이라도 지마켓에서 나오면 그 쇼핑몰의 본래 기능이고
 * 뉴스 사이트에서 나오면 끼어든 광고이기 때문이다. 스텁이 지마켓 "슈퍼딜"을
 * 광고로 오탐한 원인이 바로 이 구분을 못 한 것이다.
 *
 * 의존성을 새로 넣지 않으려고 HttpURLConnection과 org.json만 쓴다. 요청이 화면당
 * 최대 3건이라 커넥션 풀이나 코드 생성이 필요한 규모가 아니다.
 *
 * ⚠️ 이 구현은 앱에서 Gemini를 직접 부른다. APK를 뜯으면 키가 그대로 나오므로
 *    개발 단계 전용이다. 배포 전에는 우리 서버를 거치는 구현체로 교체할 것 —
 *    AdClassifier 인터페이스가 그 교체점이고, 여기 말고는 손댈 곳이 없다.
 */
class GeminiClassifier(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    /** 현재 시각. 테스트에서 쉬는 시간을 흘려보내려고 주입받는다. */
    private val now: () -> Long = System::currentTimeMillis
) : AdClassifier {

    override val source = "GEMINI:$model"

    companion object {
        private const val TAG = "AdGuard"

        /**
         * 12개 표본(쇼핑몰 자체 특가·기사·앱 UI·대출·경품 미끼 등)에서 전부 맞았고,
         * 무료 티어 상한이 실사용 가능한 수준이다.
         *
         * gemini-3.5-flash도 같은 표본을 맞히지만 무료 티어가 하루 20건이라 실기기
         * 테스트 중에 바로 소진된다. gemini-2.5-flash 계열은 신규 사용자에게 닫혔다(404).
         */
        const val DEFAULT_MODEL = "gemini-3.1-flash-lite"

        /**
         * 429(무료 티어 상한)를 받은 뒤 쉬는 시간.
         *
         * 실패는 캐시에 남기지 않으므로(모르는 것을 안다고 저장하면 TTL 동안 그 카드를
         * 다시 볼 기회가 사라진다) 상한에 걸린 상태에서는 유휴마다 같은 카드를 다시
         * 시도하게 된다. 실기기에서 몇 초 만에 호출 수십 건이 나가는 것을 확인해 넣었다.
         */
        private const val QUOTA_COOLDOWN_MS = 5 * 60 * 1000L

        /**
         * 판별은 스크롤이 멈춘 뒤 백그라운드에서 돌지만, 무한정 매달리면 코루틴과
         * 커넥션이 쌓인다. 실측 응답이 2초대라 읽기 10초면 정상 범위를 넉넉히 덮는다.
         */
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 10_000

        /**
         * 판별 기준. 프롬프트를 바꾸면 판정 분포가 통째로 달라지므로 임계값(0.6)과
         * 함께 봐야 한다. 표본 검증을 다시 돌리지 않고 손대지 말 것.
         */
        private val SYSTEM_PROMPT = """
            너는 안드로이드 화면에서 잘라낸 카드 하나가 광고인지 판단한다. 어르신이 광고를 광고인 줄 모르고 누르는 것을 막는 앱에서 쓰인다.

            isAd=true — 외부 광고주가 돈을 내고 그 앱·사이트에 끼워 넣은 홍보물:
            - 배너, 스폰서 게시물, 앱 설치 유도
            - 대출·보험·투자·도박·성인·건강식품 유인
            - 경품 당첨이나 무료 지급을 미끼로 클릭을 유도하는 문구

            isAd=false — 그 앱이 원래 하는 일:
            - 뉴스 기사, 사용자 게시물, 댓글
            - 앱 자체 UI(메뉴, 설정, 알림)
            - 검색 결과, 상품 상세
            - 쇼핑앱·오픈마켓이 자기 상품을 파는 영역(오늘의 특가, 기획전, 랭킹, 타임세일)

            출처를 반드시 고려해라. 같은 "50% 할인" 문구라도 출처가 쇼핑몰이면 그 앱의 본래 기능이라 광고가 아니고, 출처가 뉴스 사이트면 끼어든 광고다. 다만 뉴스 사이트나 커뮤니티는 대출·보험·도박 상품을 직접 팔지 않으므로, 그런 문구는 출처와 무관하게 광고로 본다.

            confidence는 확신도다. 애매하면 낮춰라. 어르신에게 잘못된 경고가 반복되면 경고 자체를 무시하게 되므로, 확신이 없으면 광고가 아니라고 하는 쪽이 낫다.

            reason은 25자 이내 한국어. 카드에 실제로 있는 표현만 근거로 삼고 없는 내용을 지어내지 마라.
        """.trimIndent()
    }

    /**
     * @return 판별 결과. 네트워크 실패·상한 초과(429)·응답 형식 이상이면 null.
     *         호출부가 캐시에 저장하지 않고 이번 화면은 표시하지 않는다.
     */
    /** 상한에 걸린 뒤 다시 호출해도 되는 시각. 여러 코루틴이 함께 읽는다. */
    @Volatile
    private var cooldownUntil = 0L

    override suspend fun classify(text: String, sourceKey: String): Verdict? {
        if (apiKey.isBlank() || text.isBlank()) return null
        if (now() < cooldownUntil) return null

        return withContext(Dispatchers.IO) {
            runCatching { request(text, sourceKey) }
                .onFailure { Log.i(TAG, "gemini 판별 실패: ${it.javaClass.simpleName} ${it.message}") }
                .getOrNull()
        }
    }

    private fun request(text: String, sourceKey: String): Verdict? {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            // 키는 쿼리스트링이 아니라 헤더로 보낸다. URL에 실으면 로그·크래시 리포트에
            // 그대로 찍혀 새어 나갈 구멍이 늘어난다.
            setRequestProperty("x-goog-api-key", apiKey)
        }

        try {
            conn.outputStream.use { it.write(buildBody(text, sourceKey).toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                val body = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                if (conn.responseCode == 429) {
                    // 상한을 넘긴 상태에서 계속 두드려봐야 같은 답만 돌아온다. 잠시 쉬면
                    // 그동안 Layer 1·3과 캐시는 그대로 동작한다 — 조용히 얕아질 뿐이다.
                    cooldownUntil = now() + QUOTA_COOLDOWN_MS
                    Log.i(TAG, "gemini 상한 초과 — ${QUOTA_COOLDOWN_MS / 60_000}분간 판별 중단")
                } else {
                    Log.i(TAG, "gemini HTTP ${conn.responseCode} ${body.take(160)}")
                }
                return null
            }

            val raw = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            return parse(raw)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * responseSchema로 형식을 강제한다. 프롬프트로 "JSON만 답해라"라고 부탁하는 것과
     * 달리 모델이 설명 문장을 앞에 붙일 수 없다.
     */
    private fun buildBody(text: String, sourceKey: String): String {
        val schema = JSONObject()
            .put("type", "OBJECT")
            .put(
                "properties",
                JSONObject()
                    .put("isAd", JSONObject().put("type", "BOOLEAN"))
                    .put("confidence", JSONObject().put("type", "NUMBER"))
                    .put("reason", JSONObject().put("type", "STRING"))
            )
            .put("required", JSONArray(listOf("isAd", "confidence", "reason")))

        val userText = "출처: ${sourceKey.ifBlank { "알 수 없음" }}\n카드 텍스트: $text"

        return JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", userText)))
                )
            )
            .put(
                "generationConfig",
                JSONObject()
                    // 같은 카드에 같은 판정이 나와야 캐시가 의미를 갖는다.
                    .put("temperature", 0)
                    // 사고를 끈다. 12개 표본에서 판정이 하나도 달라지지 않으면서
                    // 응답이 빨라졌다 (중앙값 4.9초 → 3.8초). 켜두면 사고 토큰이
                    // maxOutputTokens를 먼저 먹어 JSON을 내기 전에 잘리기도 한다.
                    .put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                    .put("maxOutputTokens", 800)
                    .put("responseMimeType", "application/json")
                    .put("responseSchema", schema)
            )
            .toString()
    }

    private fun parse(raw: String): Verdict? {
        val part = JSONObject(raw)
            .optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")?.optJSONObject(0)
            ?.optString("text")
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val verdict = JSONObject(part)
        if (!verdict.has("isAd")) return null

        return Verdict(
            isAd = verdict.getBoolean("isAd"),
            // 모델이 범위를 벗어난 값을 낼 수 있다. 임계값 비교 전에 가둔다.
            confidence = verdict.optDouble("confidence", 0.0).toFloat().coerceIn(0f, 1f),
            reason = verdict.optString("reason").ifBlank { "이유 없음" }
        )
    }
}
