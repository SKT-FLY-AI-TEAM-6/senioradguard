package com.guradian.serp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 검색 결과 위험도를 **글자만 보고** 판별한다. 한 화면 = 호출 1회.
 *
 * ## 스크린샷을 쓰지 않는다
 * 광고 배너는 그림 한 장이라 내용을 알려면 픽셀을 봐야 했다. 검색 결과는 다르다 —
 * 도메인·제목·설명이 전부 접근성 트리에 글자로 들어 있다. 그림을 보낼 이유가 없고,
 * 안 보내면 이만큼이 사라진다: 스크린샷 권한, 비트맵 복사·크롭·JPEG 인코딩,
 * 이미지 토큰(장당 수백~수천), 그리고 "화면이 통째로 나간다"는 프라이버시 부담.
 *
 * ## 접속해서 확인하지 않는다
 * 페이지를 열면 판정이 정확해지지만 열지 않는다. 접속 차단된 도메인이 절반이고,
 * 어르신 회선으로 불법 사이트에 요청을 보내는 것 자체가 위험(추적·리다이렉트·
 * 데이터 요금)을 만든다. 판단 근거를 **화면에 이미 보이는 것**으로 한정하는 것은
 * 타협이 아니라 이 기능의 전제다.
 *
 * ⚠️ 앱에서 Gemini를 직접 부른다. APK를 뜯으면 키가 그대로 나오므로 개발 단계
 *    전용이다. 배포 전에는 서버를 거치는 [SerpClassifier] 구현으로 교체할 것.
 */
class GeminiSerpClassifier(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    /** 현재 시각. 테스트에서 쉬는 시간을 흘려보내려고 주입받는다. */
    private val now: () -> Long = System::currentTimeMillis
) : SerpClassifier {

    override val source = SerpVerdict.SOURCE_LLM

    companion object {
        /** agent/GeminiClassifier와 같은 모델. 무료 티어 상한이 실사용 가능한 수준이다. */
        const val DEFAULT_MODEL = "gemini-3.1-flash-lite"

        private const val CONNECT_TIMEOUT_MS = 5_000

        /**
         * 배치라 광고 판별(8초)보다 길게 잡는다. 12~15건 배치 실측이 5~9초였다.
         * 이보다 늦게 오는 응답은 사용자가 이미 스크롤해 지나간 뒤라 쓸모가 없다.
         */
        private const val READ_TIMEOUT_MS = 15_000

        /** 429(무료 티어 상한)를 받은 뒤 쉬는 시간. */
        private const val QUOTA_COOLDOWN_MS = 5 * 60 * 1000L

        private const val MAX_TITLE_CHARS = 80
        private const val MAX_SNIPPET_CHARS = 140

        /**
         * 판별 기준. **경계값(40/70)이 프롬프트에 박혀 있다** — [RiskGrade]의 값을
         * 바꾸면 이 문장도 함께 바꿔야 한다. 한쪽만 고치면 판정 분포가 어긋난다.
         *
         * 이 문구는 실측으로 조정했다(2026-08-14, gemini-3.1-flash-lite, 27개 표본:
         * 정상 OTT·방송·포털·덜 알려진 정상 서비스 / 불법 다시보기·도박·성인·사칭·
         * APK 배포). 일치 26/27, **오탐 0 · 미탐 0.** 손대면 다시 돌려볼 것.
         */
        private val SYSTEM_PROMPT = """
            당신은 한국 노인 사용자가 검색 결과에서 불법·위험 사이트를 눌러 들어가는 것을 막는 판별기입니다.
            검색 결과 목록 한 화면을 통째로 받아, 각 항목의 위험도를 매기세요.

            **접속해 보지 않았습니다. 화면에 보이는 제목·설명·도메인만으로 판단하세요.**
            모르는 도메인을 아는 척하지 마세요. 근거가 주어진 텍스트에 드러나 있어야 합니다.

            네 방향으로 확인한 뒤 종합하세요.
            1) 도메인 신뢰도 — 등록 도메인이 알려진 사업자·공식 서비스인가, 무료·익명 등록이 쉬운 TLD인가
            2) 저작권·불법성 — 불법 다시보기·웹툰·토렌트·사설 도박을 암시하는가
            3) 피싱·속임수 — 유명 브랜드를 흉내 냈는가, 설치 파일을 받게 하는가, 다급함을 만드는 문구인가
            4) 정상 서비스 — 공식 OTT·방송사·언론사·포털·백과사전은 위험하지 않다

            중요:
            - 공식 OTT(티빙·웨이브·넷플릭스·쿠팡플레이·디즈니+)와 방송사·포털은 score 20 이하입니다.
              정상 사이트에 경고를 띄우면 사용자가 앱을 믿지 않게 되어, 놓친 위험보다 해롭습니다.
            - "무료 다시보기", "전편 무료", "링크모음", "실시간 무료 시청"은 정식 서비스가 쓰지 않는 표현입니다.
            - score 0~100. 70 이상이면 빨간 '위험' 표시가 붙습니다. 근거가 분명할 때만 70을 넘기세요.
            - reason은 어르신이 읽을 한국어 한 줄(30자 이내).

            각 항목의 index를 그대로 돌려주세요.
        """.trimIndent()
    }

    /** 상한에 걸린 뒤 다시 호출해도 되는 시각. 여러 코루틴이 함께 읽는다. */
    @Volatile
    private var cooldownUntil = 0L

    override suspend fun classify(
        query: String,
        items: List<SerpClassifier.Request>
    ): Map<String, SerpVerdict> {
        if (apiKey.isBlank() || items.isEmpty()) return emptyMap()
        if (now() < cooldownUntil) return emptyMap()

        return withContext(Dispatchers.IO) {
            runCatching { request(query, items) }
                .onFailure { Log.i(SERP_TAG, "serp 판별 실패: ${it.javaClass.simpleName} ${it.message}") }
                .getOrNull() ?: emptyMap()
        }
    }

    private fun request(
        query: String,
        items: List<SerpClassifier.Request>
    ): Map<String, SerpVerdict> {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            // 키는 쿼리스트링이 아니라 헤더로. URL에 실으면 로그에 그대로 찍힌다.
            setRequestProperty("x-goog-api-key", apiKey)
        }

        try {
            conn.outputStream.use { it.write(body(query, items).toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                val error = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                if (conn.responseCode == 429) {
                    cooldownUntil = now() + QUOTA_COOLDOWN_MS
                    Log.i(SERP_TAG, "serp 상한 초과 — ${QUOTA_COOLDOWN_MS / 60_000}분간 규칙만 사용")
                } else {
                    Log.i(SERP_TAG, "serp HTTP ${conn.responseCode} ${error.take(160)}")
                }
                return emptyMap()
            }

            return parse(conn.inputStream.bufferedReader().use(BufferedReader::readText), items)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * responseSchema로 형식을 강제한다. 프롬프트로 "JSON만 답해라"라고 부탁하는 것과
     * 달리 모델이 설명 문장을 앞에 붙일 수 없다.
     */
    private fun body(query: String, items: List<SerpClassifier.Request>): String {
        val itemSchema = JSONObject()
            .put("type", "OBJECT")
            .put(
                "properties",
                JSONObject()
                    .put("index", JSONObject().put("type", "NUMBER"))
                    .put(
                        "category",
                        JSONObject().put("type", "STRING")
                            .put("enum", JSONArray(RiskCategory.entries.map { it.name }))
                    )
                    .put("score", JSONObject().put("type", "NUMBER"))
                    .put("reason", JSONObject().put("type", "STRING"))
            )
            .put("required", JSONArray(listOf("index", "category", "score", "reason")))

        val schema = JSONObject()
            .put("type", "OBJECT")
            .put(
                "properties",
                JSONObject().put(
                    "results",
                    JSONObject().put("type", "ARRAY").put("items", itemSchema)
                )
            )
            .put("required", JSONArray(listOf("results")))

        val list = JSONArray()
        items.forEachIndexed { index, item ->
            list.put(
                JSONObject()
                    .put("index", index)
                    .put("shown_domain", item.host)
                    .put("title", item.title.take(MAX_TITLE_CHARS))
                    .put("snippet", item.snippet.take(MAX_SNIPPET_CHARS))
                    // 축별로 묶어 넘긴다. 어느 방향에서 걸렸는지가 보여야 모델도
                    // 같은 축으로 답한다.
                    .put("our_rule_flags", JSONArray(item.signals.filter { it.weight > 0 }
                        .map { "${it.axis.label}: ${it.reason}" }))
            )
        }

        val userText = buildString {
            append("검색어: ").append(query.ifBlank { "(알 수 없음)" }).append('\n')
            append("\n다음은 화면에 보이는 검색 결과입니다.\n")
            append(list.toString())
            append("\n\nour_rule_flags는 우리 규칙이 먼저 찾아낸 신호입니다. 참고하되 그대로 따르지는 마세요.")
        }

        return JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT)))
            )
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", userText)))
                )
            )
            .put(
                "generationConfig",
                JSONObject()
                    // 같은 화면에 같은 판정이 나와야 캐시가 의미를 갖는다.
                    .put("temperature", 0)
                    // 사고를 끈다. 표본 검증에서 판정이 달라지지 않으면서 응답이 빨라진다.
                    .put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                    // 항목당 100토큰 남짓 + 여유. 모자라면 JSON이 잘려 전부 버려진다.
                    .put("maxOutputTokens", 200 + items.size * 120)
                    .put("responseMimeType", "application/json")
                    .put("responseSchema", schema)
            )
            .toString()
    }

    /**
     * index로 요청과 맞춰 host별 판정을 만든다.
     *
     * 모델이 index를 빠뜨리거나 범위 밖 값을 주면 **그 항목만 버린다.** 배치 하나가
     * 통째로 날아가면 화면에 아무 표시도 안 뜨는데, 대부분 맞고 하나만 틀린 경우가
     * 그보다 훨씬 흔하다.
     */
    internal fun parse(raw: String, items: List<SerpClassifier.Request>): Map<String, SerpVerdict> {
        val part = JSONObject(raw)
            .optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")?.optJSONObject(0)
            ?.optString("text")
            ?.takeIf { it.isNotBlank() }
            ?: return emptyMap()

        val results = JSONObject(part).optJSONArray("results") ?: return emptyMap()
        val out = LinkedHashMap<String, SerpVerdict>()

        for (i in 0 until results.length()) {
            val row = results.optJSONObject(i) ?: continue
            val index = row.optInt("index", -1)
            val item = items.getOrNull(index) ?: continue
            if (!row.has("score")) continue

            out[item.host] = SerpVerdict.of(
                category = RiskCategory.parse(row.optString("category")),
                // 모델이 범위를 벗어난 값을 낼 수 있다. 등급으로 바꾸기 전에 가둔다.
                score = row.optDouble("score", 0.0).toInt(),
                reason = row.optString("reason").ifBlank { "확인이 필요한 곳입니다" },
                source = source
            )
        }
        return out
    }
}
