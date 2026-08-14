package com.senioradguard.url

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskAggregatorTest {

    private fun signal(
        weight: Int,
        axis: RiskAxis = RiskAxis.PHISHING_DECEPTION,
        hard: Boolean = false,
        category: RiskCategory = RiskCategory.UNKNOWN,
        reason: String = "근거$weight"
    ) = Signal(axis, weight, reason, hard, category)

    private fun llm(score: Int, category: RiskCategory = RiskCategory.UNVERIFIED_THIRD_PARTY) =
        UrlRiskVerdict(category, RiskLevel.of(score), score, listOf("판별기 근거"), UrlRiskVerdict.SOURCE_LLM)

    // 신호를 전부 더하면 사소한 신호 여럿이 확정 신호 하나를 이겨버린다
    @Test
    fun `가장 강한 신호가 점수를 정하고 나머지는 거든다`() {
        assertEquals(60, RiskAggregator.score(listOf(signal(60))))
        assertEquals(70, RiskAggregator.score(listOf(signal(60), signal(30))))
        // 20짜리 다섯 개(합 100)로는 60짜리 하나를 넘지 못한다
        assertTrue(RiskAggregator.score(List(5) { signal(20) }) < 60)
    }

    @Test
    fun `신뢰 가산은 점수를 끌어내린다`() {
        assertEquals(0, RiskAggregator.score(listOf(signal(25), signal(-45))))
    }

    @Test
    fun `점수는 0에서 100 사이로 잘린다`() {
        assertEquals(100, RiskAggregator.score(List(4) { signal(90) }))
        assertEquals(0, RiskAggregator.score(listOf(signal(-45))))
        assertEquals(0, RiskAggregator.score(emptyList()))
    }

    // 정상 광고에 경고가 뜨면 사용자가 경고 자체를 무시하게 된다
    @Test
    fun `광고 축 신호만 있으면 상한이 걸린다`() {
        val adOnly = List(4) { signal(30, axis = RiskAxis.AD_TRACKING) }
        assertEquals(RiskAggregator.AD_TRACKING_ONLY_CAP, RiskAggregator.score(adOnly))
    }

    @Test
    fun `광고 축에 다른 축이 하나라도 섞이면 상한이 풀린다`() {
        val mixed = listOf(
            signal(30, axis = RiskAxis.AD_TRACKING),
            signal(60, axis = RiskAxis.ILLEGAL_CONTENT)
        )
        assertTrue(RiskAggregator.score(mixed) > RiskAggregator.AD_TRACKING_ONLY_CAP)
    }

    @Test
    fun `등급 경계`() {
        assertEquals(RiskLevel.LOW, RiskLevel.of(39))
        assertEquals(RiskLevel.MEDIUM, RiskLevel.of(40))
        assertEquals(RiskLevel.MEDIUM, RiskLevel.of(69))
        assertEquals(RiskLevel.HIGH, RiskLevel.of(70))
    }

    // ── 판별기와의 합성 ────────────────────────────────────────

    @Test
    fun `판별기 결과가 없으면 규칙 판정을 쓴다`() {
        val result = RiskAggregator.combine(listOf(signal(60)), null)
        assertEquals(UrlRiskVerdict.SOURCE_HEURISTIC, result.source)
        assertEquals(60, result.score)
    }

    // 목록에 없는 새 사이트를 잡으려고 판별기를 붙인 것이다.
    // 규칙이 조용하다고 판별기 판단을 깎으면 안 된다
    @Test
    fun `판별기가 최종 판단을 한다`() {
        val result = RiskAggregator.combine(listOf(signal(10)), llm(85))

        assertEquals(85, result.score)
        assertEquals(RiskLevel.HIGH, result.level)
        assertEquals(UrlRiskVerdict.SOURCE_LLM, result.source)
    }

    // .apk 직접 배포처럼 URL에 드러난 사실은 판별기가 넘겨도 남아야 한다
    @Test
    fun `확정 신호는 판별기 점수의 바닥이 된다`() {
        val hard = listOf(signal(60, hard = true, category = RiskCategory.MALWARE_OR_UNWANTED_APP))
        val result = RiskAggregator.combine(hard, llm(5))

        assertEquals(60, result.score)
        assertEquals(RiskLevel.MEDIUM, result.level)
    }

    @Test
    fun `확정이 아닌 신호는 바닥을 만들지 않는다`() {
        val soft = listOf(signal(60, hard = false))
        assertEquals(0, RiskAggregator.hardFloor(soft))
        assertEquals(5, RiskAggregator.combine(soft, llm(5)).score)
    }

    // 알려진 브랜드라는 사실이 확정 신호를 지워주지는 않는다
    @Test
    fun `바닥 계산에는 신뢰 가산을 반영하지 않는다`() {
        val signals = listOf(signal(60, hard = true), signal(-45))
        assertEquals(60, RiskAggregator.hardFloor(signals))
    }

    @Test
    fun `판별기가 성격을 모르면 규칙이 고른 성격을 쓴다`() {
        val signals = listOf(signal(60, category = RiskCategory.ILLEGAL_GAMBLING))
        val result = RiskAggregator.combine(signals, llm(70, RiskCategory.UNKNOWN))

        assertEquals(RiskCategory.ILLEGAL_GAMBLING, result.category)
    }

    @Test
    fun `근거는 판별기 것을 앞에 두고 중복 없이 자른다`() {
        val signals = List(6) { signal(50 - it, reason = "규칙$it") }
        val result = RiskAggregator.combine(signals, llm(80))

        assertEquals("판별기 근거", result.reasons.first())
        assertTrue(result.reasons.size <= UrlRiskVerdict.MAX_REASONS)
        assertEquals(result.reasons.distinct(), result.reasons)
    }

    @Test
    fun `성격은 가장 강한 신호를 따른다`() {
        val signals = listOf(
            signal(30, category = RiskCategory.UNVERIFIED_THIRD_PARTY),
            signal(70, category = RiskCategory.PHISHING_OR_SCAM)
        )
        assertEquals(RiskCategory.PHISHING_OR_SCAM, RiskAggregator.category(signals))
    }
}
