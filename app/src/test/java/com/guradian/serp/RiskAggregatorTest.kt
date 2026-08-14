package com.guradian.serp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 점수를 만드는 식과 등급 경계.
 *
 * 이 식이 바뀌면 화면의 색이 통째로 바뀐다. 값을 손볼 일이 생기면
 * [GeminiSerpClassifier]의 프롬프트에 박힌 경계값(70)도 함께 봐야 한다.
 */
class RiskAggregatorTest {

    private fun signal(weight: Int, hard: Boolean = false, axis: RiskAxis = RiskAxis.DOMAIN_TRUST) =
        Signal(axis, weight, "근거", hard)

    @Test
    fun `가장 강한 신호가 등급을 정하고 나머지는 거들기만 한다`() {
        // 40짜리 셋을 단순히 더하면 120이 되어 확정 신호 하나(70)를 이겨버린다.
        assertEquals(40 + (40 + 40) / 3, RiskAggregator.score(listOf(signal(40), signal(40), signal(40))))
    }

    @Test
    fun `신뢰 가산은 점수를 깎는다`() {
        val signals = listOf(signal(30), signal(-45))
        assertEquals(0, RiskAggregator.score(signals))
    }

    @Test
    fun `확정 신호의 하한에는 신뢰 가산이 반영되지 않는다`() {
        // 알려진 플랫폼에 올라온 글이라는 사실이, 그 글이 도박을 권한다는 사실을
        // 지워주지는 않는다.
        val signals = listOf(signal(55, hard = true), signal(-45))
        // 종합 점수는 신뢰 가산에 깎여 '안전'까지 내려가지만(55-45=10)
        assertEquals(10, RiskAggregator.score(signals))
        // 하한은 그대로 남아 [SerpRules]가 '주의'로 확정한다
        assertEquals(55, RiskAggregator.hardFloor(signals))
    }

    @Test
    fun `등급 경계는 40과 70이다`() {
        assertEquals(RiskGrade.LOW, RiskGrade.of(39))
        assertEquals(RiskGrade.MEDIUM, RiskGrade.of(40))
        assertEquals(RiskGrade.MEDIUM, RiskGrade.of(69))
        assertEquals(RiskGrade.HIGH, RiskGrade.of(70))
    }

    @Test
    fun `점수는 0에서 100 사이로 갇힌다`() {
        assertEquals(100, RiskAggregator.score(listOf(signal(100), signal(90), signal(90))))
        assertEquals(0, RiskAggregator.score(listOf(signal(-45))))
        assertEquals(100, SerpVerdict.of(RiskCategory.UNKNOWN, 500, "근거", "RULE").score)
        assertEquals(0, SerpVerdict.of(RiskCategory.UNKNOWN, -20, "근거", "RULE").score)
    }

    @Test
    fun `판별기가 최종 판단을 하고 신호는 바닥만 받친다`() {
        val signals = listOf(signal(20))     // 확정 신호 없음
        val llm = SerpVerdict.of(RiskCategory.PHISHING_OR_SCAM, 85, "사칭으로 보입니다", "LLM")

        val combined = RiskAggregator.combine(signals, llm)

        // 규칙이 20점밖에 못 봤어도 판별기의 85가 이긴다. 목록에 없는 새 사이트를
        // 잡으려고 판별기를 붙인 것이므로 순서를 뒤집으면 안 된다.
        assertEquals(85, combined.score)
        assertEquals(RiskGrade.HIGH, combined.grade)
        assertEquals("사칭으로 보입니다", combined.reason)
    }

    @Test
    fun `판별기가 안전하다고 해도 확정 신호 아래로는 안 내려간다`() {
        val signals = listOf(signal(60, hard = true))
        val llm = SerpVerdict.of(RiskCategory.TRUSTED_KNOWN_BRAND, 5, "안전합니다", "LLM")

        val combined = RiskAggregator.combine(signals, llm)

        assertEquals(60, combined.score)
        // 규칙이 점수를 끌어올렸으면 근거도 규칙 쪽이어야 한다 — "안전합니다"라고
        // 쓰인 빨간 배지는 어르신에게 아무것도 알려주지 못한다
        assertEquals("근거", combined.reason)
    }

    @Test
    fun `판별기가 없으면 규칙 판정을 그대로 쓴다`() {
        val signals = listOf(signal(50))
        assertEquals(SerpVerdict.SOURCE_RULE, RiskAggregator.combine(signals, null).source)
    }

    @Test
    fun `성격은 가장 강한 신호가 가리키는 것을 따른다`() {
        val signals = listOf(
            Signal(RiskAxis.DOMAIN_TRUST, 25, "약한 신호", category = RiskCategory.UNVERIFIED_THIRD_PARTY),
            Signal(RiskAxis.ILLEGAL_CONTENT, 70, "강한 신호", category = RiskCategory.ILLEGAL_GAMBLING)
        )
        assertEquals(RiskCategory.ILLEGAL_GAMBLING, RiskAggregator.category(signals))
    }

    @Test
    fun `근거가 배지 길이로 잘린다`() {
        val long = "가".repeat(100)
        assertTrue(SerpVerdict.of(RiskCategory.UNKNOWN, 50, long, "RULE").reason.length
            <= SerpVerdict.MAX_REASON_CHARS)
    }
}
