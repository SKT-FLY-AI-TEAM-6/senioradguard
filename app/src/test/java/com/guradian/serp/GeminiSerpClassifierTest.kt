package com.guradian.serp

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 네트워크를 타지 않는 경로만 검증한다. 판별 품질 자체는 실제 API에 표본을 돌려
 * 확인했고(27개 표본, 일치 26/27 · 오탐 0 · 미탐 0), 응답을 흉내 내서 재현하면
 * 오히려 실제와 멀어진다.
 *
 * 여기서 덮는 것은 **배치 응답을 요청에 다시 맞추는 부분**이다. 배치는 index로만
 * 짝이 맞으므로 이 대응이 틀리면 A 사이트의 판정이 B 사이트 배지에 붙는다 —
 * 조용히 틀리는 종류의 버그라 테스트가 없으면 발견되지 않는다.
 */
class GeminiSerpClassifierTest {

    private val classifier = GeminiSerpClassifier(apiKey = "key")

    private fun request(host: String) =
        SerpClassifier.Request(host, "제목", "설명", emptyList())

    /** Gemini 응답 봉투. 실제 형식 그대로다. */
    private fun envelope(inner: String) = """
        {"candidates":[{"content":{"parts":[{"text":${org.json.JSONObject.quote(inner)}}]}}]}
    """.trimIndent()

    @Test
    fun `index로 요청과 응답을 짝짓는다`() {
        val items = listOf(request("tving.com"), request("tvhot2.com"))
        val body = envelope(
            """{"results":[
               {"index":1,"category":"ILLEGAL_STREAMING_OR_COPYRIGHT","score":90,"reason":"불법 사이트입니다"},
               {"index":0,"category":"TRUSTED_KNOWN_BRAND","score":10,"reason":"공식 서비스입니다"}]}"""
        )

        val parsed = classifier.parse(body, items)

        // 응답이 뒤섞여 와도 index가 기준이다
        assertEquals(RiskGrade.HIGH, parsed["tvhot2.com"]!!.grade)
        assertEquals(RiskGrade.LOW, parsed["tving.com"]!!.grade)
        assertEquals("불법 사이트입니다", parsed["tvhot2.com"]!!.reason)
    }

    @Test
    fun `범위를 벗어난 index는 그 항목만 버린다`() {
        // 배치 하나가 통째로 날아가면 화면에 아무것도 안 뜬다. 대부분 맞고 하나만
        // 틀린 경우가 훨씬 흔하므로 그 한 건만 버린다.
        val items = listOf(request("tving.com"))
        val body = envelope(
            """{"results":[
               {"index":7,"category":"UNKNOWN","score":50,"reason":"엉뚱한 번호"},
               {"index":0,"category":"TRUSTED_KNOWN_BRAND","score":10,"reason":"공식 서비스입니다"}]}"""
        )

        val parsed = classifier.parse(body, items)

        assertEquals(1, parsed.size)
        assertEquals(RiskGrade.LOW, parsed["tving.com"]!!.grade)
    }

    @Test
    fun `모르는 분류 이름은 판단 보류로 떨어진다`() {
        val items = listOf(request("some-site.com"))
        val body = envelope(
            """{"results":[{"index":0,"category":"WEIRD_NEW_NAME","score":50,"reason":"근거"}]}"""
        )

        assertEquals(RiskCategory.UNKNOWN, classifier.parse(body, items)["some-site.com"]!!.category)
    }

    @Test
    fun `점수가 범위를 벗어나면 가둔다`() {
        val items = listOf(request("some-site.com"))
        val body = envelope("""{"results":[{"index":0,"category":"UNKNOWN","score":500,"reason":"근거"}]}""")

        assertEquals(100, classifier.parse(body, items)["some-site.com"]!!.score)
    }

    @Test
    fun `응답이 잘리거나 형식이 어긋나면 빈 결과다`() {
        val items = listOf(request("some-site.com"))
        // 호출부는 빈 결과를 "판별 실패"로 보고 캐시에 남기지 않는다
        assertTrue(classifier.parse("""{"candidates":[]}""", items).isEmpty())
        assertTrue(classifier.parse(envelope("""{"results":[]}"""), items).isEmpty())
    }

    // ── 네트워크로 나가지 않는 조건 ─────────────────────────────

    @Test
    fun `키가 없으면 호출하지 않는다`() = runTest {
        // 키 없이 빌드한 팀원의 기기에서도 앱이 죽지 않아야 한다
        assertTrue(GeminiSerpClassifier(apiKey = "").classify("q", listOf(request("a.com"))).isEmpty())
    }

    @Test
    fun `보낼 항목이 없으면 호출하지 않는다`() = runTest {
        assertTrue(classifier.classify("q", emptyList()).isEmpty())
    }

    @Test
    fun `판정 출처가 남는다`() {
        assertEquals(SerpVerdict.SOURCE_LLM, classifier.source)
    }

    @Test
    fun `점수 필드가 없는 행은 버린다`() {
        val items = listOf(request("some-site.com"))
        val body = envelope("""{"results":[{"index":0,"category":"UNKNOWN","reason":"점수 없음"}]}""")
        assertNull(classifier.parse(body, items)["some-site.com"])
    }
}
