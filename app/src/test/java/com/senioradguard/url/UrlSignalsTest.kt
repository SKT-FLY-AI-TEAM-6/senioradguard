package com.senioradguard.url

import com.senioradguard.risk.RiskCategory
import com.senioradguard.risk.RiskLevel
import com.senioradguard.risk.RiskVerdict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 네 축이 각각 무엇에 반응하는지, 그리고 **정상 광고에는 반응하지 않는지**를 못박는다.
 * 후자가 더 중요하다 — 정상 광고마다 경고가 뜨면 사용자가 경고를 통째로 무시한다.
 */
class UrlSignalsTest {

    private fun link(
        url: String,
        anchor: String = "",
        source: String = "yna.co.kr",
        isAd: Boolean = true
    ) = UrlParser.parse(url, source, anchor, isAd)!!

    private fun verdict(
        url: String,
        anchor: String = "",
        isAd: Boolean = true
    ) = RiskAggregator.heuristic(UrlSignals.of(link(url, anchor, isAd = isAd)))

    private fun axes(url: String, anchor: String = "") =
        UrlSignals.of(link(url, anchor)).filter { it.weight > 0 }.map { it.axis }.toSet()

    // ── 정상 광고 (팀 예시: ad.yna.co.kr) ──────────────────────

    @Test
    fun `언론사 공식 광고 서버는 낮음이다`() {
        val result = verdict("https://ad.yna.co.kr/RealMedia/ads/click_lx.ads/12345", "삼성전자 신형 스마트폰 특가")

        assertEquals(RiskLevel.LOW, result.level)
        assertEquals(RiskCategory.OFFICIAL_AD_TRACKER, result.category)
    }

    // 광고 축만 걸린 링크는 신호가 여럿이어도 '낮음'을 벗어나면 안 된다
    @Test
    fun `광고 트래킹 축만 걸리면 점수에 상한이 걸린다`() {
        val result = verdict("https://ad.unknownpublisher.com/adclick?utm_source=x")

        assertEquals(setOf(RiskAxis.AD_TRACKING), axes("https://ad.unknownpublisher.com/adclick?utm_source=x"))
        assertTrue(
            "광고 축만 있으면 ${RiskAggregator.AD_TRACKING_ONLY_CAP} 이하",
            result.score <= RiskAggregator.AD_TRACKING_ONLY_CAP
        )
        assertEquals(RiskLevel.LOW, result.level)
    }

    @Test
    fun `알려진 사업자 도메인은 신뢰 가산을 받는다`() {
        val signals = UrlSignals.of(link("https://www.coupang.com/vp/products/1"))
        assertTrue(signals.any { it.weight < 0 })
        assertEquals(RiskCategory.TRUSTED_KNOWN_BRAND, RiskAggregator.category(signals))
    }

    // ── 저작권·불법성 ──────────────────────────────────────────

    // 팀 예시: tvhot2.com — 목록에 없더라도 이름만으로 걸려야 한다
    @Test
    fun `불법 다시보기 사이트 이름은 위험이다`() {
        val result = verdict("https://tvhot2.com/player/123")

        assertEquals(RiskLevel.HIGH, result.level)
        assertEquals(RiskCategory.ILLEGAL_STREAMING_OR_COPYRIGHT, result.category)
    }

    @Test
    fun `도박 용어가 도메인 라벨이면 위험이다`() {
        val result = verdict("https://first-bet.top/join")

        assertEquals(RiskLevel.HIGH, result.level)
        assertEquals(RiskCategory.ILLEGAL_GAMBLING, result.category)
    }

    // "bet"을 부분 일치로 보면 정상 도메인이 무더기로 걸린다
    @Test
    fun `도박 용어가 단어 안에 섞인 것만으로는 걸리지 않는다`() {
        assertTrue(RiskAxis.ILLEGAL_CONTENT !in axes("https://betterlifekorea.com/shop"))
        assertTrue(RiskAxis.ILLEGAL_CONTENT !in axes("https://slotech-parts.co.kr/"))
    }

    @Test
    fun `문구의 불법 유통 표현도 잡는다`() {
        val result = verdict("https://unknown-site.com/v/9", "드라마 전편 다시보기 무료")
        assertTrue(result.score >= RiskLevel.MEDIUM.minScore)
    }

    // ── 피싱·속임수 ────────────────────────────────────────────

    @Test
    fun `브랜드를 앞단에 붙인 사칭 주소는 위험이다`() {
        val result = verdict("https://naver.login-secure.xyz/verify", "본인인증이 필요합니다")

        assertEquals(RiskLevel.HIGH, result.level)
        assertEquals(RiskCategory.PHISHING_OR_SCAM, result.category)
    }

    @Test
    fun `골뱅이로 목적지를 숨긴 주소를 잡는다`() {
        val signals = UrlSignals.of(link("http://naver.com@evil.xyz/login"))
        val at = signals.first { "@" in it.reason }

        assertTrue(at.hard)
        assertEquals(RiskAxis.PHISHING_DECEPTION, at.axis)
    }

    @Test
    fun `설치 파일을 직접 내려받게 하면 위험이다`() {
        val result = verdict("https://cdn-free-app.top/download/gift.apk", "무료 지급")

        assertEquals(RiskLevel.HIGH, result.level)
        assertEquals(RiskCategory.MALWARE_OR_UNWANTED_APP, result.category)
    }

    @Test
    fun `단축 주소는 그 자체가 신호다`() {
        val signals = UrlSignals.of(link("https://bit.ly/3abc"))
        assertTrue(signals.any { it.axis == RiskAxis.PHISHING_DECEPTION && it.weight > 0 })
    }

    @Test
    fun `IP 주소로 연결되면 확정 신호다`() {
        val signals = UrlSignals.of(link("http://203.0.113.9/pay"))
        assertTrue(signals.any { it.hard })
        assertTrue(RiskAggregator.hardFloor(signals) > 0)
    }

    // ── 축 분리 ────────────────────────────────────────────────

    // 한 링크가 여러 축에 동시에 걸리는 것이 이 구조의 요지다
    @Test
    fun `불법 스트리밍 주소는 여러 축에 함께 걸린다`() {
        val found = axes("http://nunu-tv7.xyz/watch?free=1", "무료 다시보기 지금 확인")

        assertTrue(RiskAxis.ILLEGAL_CONTENT in found)
        assertTrue(RiskAxis.DOMAIN_TRUST in found)
        assertTrue(RiskAxis.PHISHING_DECEPTION in found)
    }
}
