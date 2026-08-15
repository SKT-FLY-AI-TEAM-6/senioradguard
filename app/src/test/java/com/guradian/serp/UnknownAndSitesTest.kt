package com.guradian.serp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 두 가지를 못 박는다.
 *
 *  1. **모르는 것을 '안전'이라고 말하지 않는다** — 우리 규칙 목록은 작다. "걸린 규칙이
 *     없다"를 초록으로 옮기면 모르는 사이트 전부에 초록 도장을 찍게 된다
 *  2. **목록은 갈아끼울 수 있다** — 원격 피드가 붙어도 판정 코드는 손대지 않는다
 */
class UnknownAndSitesTest {

    private val signals = UrlSignals.DEFAULT

    private fun result(host: String, title: String = "그냥 평범한 제목입니다", snippet: String = "") =
        SerpResult(host, title, snippet)

    // ── 모르는 것은 모른다고 한다 ────────────────────────────────

    @Test
    fun `걸린 규칙이 없으면 안전이 아니라 확인 안 됨이다`() {
        val verdict = RiskAggregator.ruleVerdict(signals.of(result("some-shop-blog.com")))

        assertEquals(RiskGrade.UNKNOWN, verdict.grade)
        assertNotEquals("0점을 '안전'으로 흘려보내면 안 된다", RiskGrade.LOW, verdict.grade)
    }

    @Test
    fun `확인 안 됨은 화면에 아무것도 그리지 않는다`() {
        assertFalse(RiskGrade.UNKNOWN.isShown)
        // 나머지는 전부 그린다 — 초록도 "확인했고 괜찮다"는 정보다
        assertTrue(RiskGrade.LOW.isShown)
        assertTrue(RiskGrade.MEDIUM.isShown)
        assertTrue(RiskGrade.HIGH.isShown)
    }

    @Test
    fun `초록은 근거가 있을 때만 붙는다`() {
        // 알려진 곳이거나…
        assertEquals(RiskGrade.LOW, RiskAggregator.ruleVerdict(signals.of(result("tving.com"))).grade)

        // …판별기가 실제로 보고 안전하다고 했을 때
        val fromAi = SerpVerdict.of(RiskCategory.TRUSTED_KNOWN_BRAND, 10, "공식 서비스입니다", "LLM")
        val combined = RiskAggregator.combine(signals.of(result("kocowa.com")), fromAi)
        assertEquals(RiskGrade.LOW, combined.grade)
    }

    @Test
    fun `판별기를 못 불렀고 규칙도 조용하면 확인 안 됨으로 남는다`() {
        // 키 없음·상한 초과·오프라인. 여기서 초록을 칠하면 가장 위험하다 —
        // 아무도 보지 않은 사이트에 안전 도장이 찍힌다.
        val combined = RiskAggregator.combine(signals.of(result("never-seen-site.com")), null)

        assertEquals(RiskGrade.UNKNOWN, combined.grade)
    }

    @Test
    fun `위험 신호가 있으면 판별기가 없어도 경고는 남는다`() {
        // 모른다고 침묵하는 것과, 아는 신호를 버리는 것은 다르다
        val combined = RiskAggregator.combine(
            signals.of(result("unknown-site.top", "무료 다시보기", "전편 무료 시청")),
            null
        )

        assertTrue(combined.grade.isShown)
        assertNotEquals(RiskGrade.UNKNOWN, combined.grade)
    }

    // ── 목록은 갈아끼울 수 있다 ─────────────────────────────────

    /** 원격 피드가 새 불법 도메인 조각을 하나 물어다 준 상황. */
    private object FeedSites : KnownSites {
        override val trustedRoots = emptySet<String>()
        override val publicSuffixes = emptySet<String>()
        override val impersonatedBrands = emptySet<String>()
        override val piracyHostTerms = setOf("zzflix")
        override val gamblingHostTerms = emptySet<String>()
        override val adultHostTerms = emptySet<String>()
        override val shorteners = emptySet<String>()
    }

    @Test
    fun `목록을 더하면 판정 코드를 건드리지 않고 새 사이트를 잡는다`() {
        val before = UrlSignals.DEFAULT
        val after = UrlSignals(BuiltInKnownSites + FeedSites)
        val site = result("zzflix7.com", "최신 드라마", "바로 보기")

        // 씨앗 목록만으로는 모른다 → 판별기 몫
        assertEquals(null, SerpRules.resolve(before.of(site)))

        // 피드를 얹으면 규칙이 즉시 확정한다 (네트워크 왕복 없이)
        val resolved = SerpRules.resolve(after.of(site))
        assertTrue("피드로 들어온 이름이 잡혀야 한다", resolved != null)
        assertEquals(RiskGrade.HIGH, resolved!!.grade)
    }

    @Test
    fun `목록을 더해도 씨앗은 살아 있다`() {
        // 피드를 못 받아도 최소한 씨앗만큼은 계속 동작해야 한다
        val merged = UrlSignals(BuiltInKnownSites + FeedSites)

        assertEquals(RiskGrade.HIGH, SerpRules.resolve(merged.of(result("tvhot2.com")))!!.grade)
        assertEquals(RiskGrade.LOW, SerpRules.resolve(merged.of(result("tving.com")))!!.grade)
    }
}
