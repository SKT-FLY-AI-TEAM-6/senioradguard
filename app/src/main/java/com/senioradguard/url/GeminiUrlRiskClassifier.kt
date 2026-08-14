package com.senioradguard.url

import com.senioradguard.risk.RiskCategory
import com.senioradguard.risk.RiskLevel
import com.senioradguard.risk.RiskVerdict

import android.util.Log
import com.senioradguard.agent.GeminiClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Agent5의 Gemini 구현 — **URL 문자열만 보고** 위험도를 추론한다.
 *
 * ## 왜 접속해서 확인하지 않는가
 * 링크를 열어 내용을 보면 판정이 훨씬 정확해진다. 그럼에도 열지 않는다.
 *  - 접속을 막아 둔 도메인이 있어 절반은 어차피 못 본다
 *  - 어르신 회선으로 낯선 서버에 요청을 보내는 것 자체가 위험을 만든다
 *    (추적 픽셀, 리다이렉트 체인, 데이터 요금)
 *  - 광고 클릭 URL은 한 번 열면 광고비가 집행된다 — 우리가 광고주에게 비용을 씌우는 셈이다
 *
 * 대신 [UrlSignals]가 뽑은 근거를 프롬프트에 함께 넣어 판단 재료를 채운다.
 *
 * ## 화면 내용을 보내지 않는다
 * 넘기는 것은 URL·링크 문구·출처 도메인까지다. 카드 본문은 Layer 2가 이미
 * 마스킹해서 따로 보내며, 여기서 또 보낼 이유가 없다.
 */
class GeminiUrlRiskClassifier(
    apiKey: String,
    model: String = GeminiClient.DEFAULT_MODEL,
    private val client: GeminiClient = GeminiClient(apiKey, model)
) : UrlRiskClassifier {

    companion object {
        private const val TAG = "AdGuardUrl"

        /** 링크 문구를 프롬프트에 넣을 때의 길이 상한. */
        private const val MAX_ANCHOR_CHARS = 80

        /** URL 자체의 길이 상한. 추적 파라미터가 수백 자로 붙는 경우가 흔하다. */
        private const val MAX_URL_CHARS = 300

        private val SYSTEM_PROMPT = """
            당신은 한국 노인 사용자를 악성 링크에서 보호하는 판별기입니다.
            광고를 눌렀을 때 이동하는 URL 하나를 받아 위험도를 매기세요.

            **URL 문자열과 주어진 문맥만으로 판단하세요. 접속해 보지 않았습니다.**
            모르는 도메인을 아는 척하지 마세요. 근거가 URL에 드러나 있어야 합니다.

            네 방향으로 각각 확인한 뒤 종합하세요.
            1) 도메인 신뢰도 — 등록 도메인이 알려진 사업자인가, 무료·익명 등록이
               쉬운 최상위 도메인인가, 이름이 기계로 생성된 듯한가
            2) 저작권·불법성 — 불법 다시보기·웹툰·토렌트·사설 도박을 암시하는가
            3) 피싱·속임수 — 유명 브랜드를 흉내 냈는가, 단축 주소로 목적지를
               감췄는가, 설치 파일을 직접 내려받게 하는가, 다급함을 만드는 문구인가
            4) 광고 트래킹 — 언론사·플랫폼이 쓰는 공식 광고 서버인가

            중요: 공식 광고 서버(ad.언론사.co.kr, doubleclick.net 등)는 광고일 뿐
            위험하지 않습니다. score를 20 이하로 두세요. 정상 광고에 경고를 띄우면
            사용자가 앱을 믿지 않게 되어, 놓친 위험보다 해롭습니다.

            score는 0~100입니다. 70 이상이면 어르신에게 전체 화면 경고가 뜹니다.
            근거가 URL에 분명히 드러날 때만 70을 넘기세요.
            reasons는 한국어 한 줄짜리 문장으로, 어르신이 읽고 이해할 수 있게 쓰세요.
        """.trimIndent()

        private val SCHEMA = GeminiClient.objectSchema(
            "category" to GeminiClient.stringField(
                *RiskCategory.entries.map { it.name }.toTypedArray()
            ),
            "score" to GeminiClient.numberField(),
            "reasons" to GeminiClient.stringArrayField()
        )
    }

    override val source = RiskVerdict.SOURCE_LLM

    override suspend fun classify(link: AdLink, signals: List<Signal>): RiskVerdict? {
        val payload = client.generateJson(SYSTEM_PROMPT, prompt(link, signals), SCHEMA)
            ?: return null

        return runCatching {
            val json = JSONObject(payload)
            val score = json.getDouble("score").toInt().coerceIn(0, 100)
            RiskVerdict(
                category = RiskCategory.parse(json.optString("category")),
                level = RiskLevel.of(score),
                score = score,
                reasons = json.optJSONArray("reasons").toStringList()
                    .take(RiskVerdict.MAX_REASONS),
                source = source
            )
        }.getOrElse {
            Log.e(TAG, "위험도 파싱 실패: ${payload.take(200)}")
            null
        }
    }

    /**
     * 팀에서 합의한 스키마 그대로 넘긴다. 필드 이름이 곧 설명이라 별도 안내문 없이도
     * 모델이 무엇을 보는지 알 수 있고, 나중에 서버 구현으로 옮길 때 그대로 재사용된다.
     */
    private fun prompt(link: AdLink, signals: List<Signal>): String {
        val c = link.components
        val json = JSONObject().apply {
            put("target_url", link.targetUrl.take(MAX_URL_CHARS))
            put("url_components", JSONObject().apply {
                put("protocol", c.protocol)
                put("domain", c.domain)
                put("root_domain", c.rootDomain)
                put("tld", c.tld)
                put("subdomain", c.subdomain)
                put("path", c.path.take(MAX_URL_CHARS))
            })
            put("context", JSONObject().apply {
                put("source_page_url", link.context.sourcePageUrl)
                put("anchor_text", link.context.anchorText.take(MAX_ANCHOR_CHARS))
                put("is_ad_element", link.context.isAdElement)
                put("is_shortener", link.context.isShortener)
            })
        }

        // 축별로 묶어 넘긴다. 어느 방향에서 걸렸는지가 보여야 모델도 같은 축으로 답한다.
        val evidence = signals
            .filter { it.weight != 0 }
            .groupBy { it.axis }
            .entries
            .joinToString("\n") { (axis, list) ->
                "- ${axis.label}: " + list.joinToString("; ") { it.reason }
            }
            .ifBlank { "- (규칙으로 걸린 신호 없음)" }

        return """
            다음 링크의 위험도를 판단하세요.

            $json

            우리 규칙이 먼저 찾아낸 신호입니다. 참고하되 그대로 따르지는 마세요.
            $evidence
        """.trimIndent()
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).trim().takeIf(String::isNotEmpty) }
    }
}
