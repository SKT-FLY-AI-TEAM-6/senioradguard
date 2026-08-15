package com.guradian.serp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 규칙 단독 판정. **네트워크도 안드로이드도 쓰지 않으므로 JVM에서 그대로 돈다.**
 *
 * 여기서 확인하는 것은 두 가지다.
 *  - 이름만 봐도 이상한 사이트가 판별기 없이 잡히는가 (누누티비류)
 *  - 정상 사이트에 경고가 붙지 않는가 — **이쪽이 더 중요하다.** 정상에 붙는
 *    경고 하나가 어르신이 앱 전체를 무시하게 만든다
 */
class UrlSignalsTest {

    private val signals = UrlSignals.DEFAULT

    private fun result(host: String, title: String = "제목입니다", snippet: String = "") =
        SerpResult(host, title, snippet)

    private fun grade(host: String, title: String = "제목입니다", snippet: String = ""): RiskGrade {
        val found = signals.of(result(host, title, snippet))
        return (SerpRules.resolve(found) ?: RiskAggregator.ruleVerdict(found)).grade
    }

    // ── 규칙만으로 확정되는 위험 (판별기를 부르지 않는다) ────────────

    @Test
    fun `불법 다시보기 이름은 규칙만으로 위험`() {
        for (host in listOf("tvhot2.com", "noonoo.tv", "newtoki-play.top", "tvmon77.xyz")) {
            val verdict = SerpRules.resolve(signals.of(result(host)))
            assertTrue("$host — 규칙이 결론을 내야 한다", verdict != null)
            assertEquals(host, RiskGrade.HIGH, verdict!!.grade)
            assertEquals(host, SerpVerdict.SOURCE_RULE, verdict.source)
        }
    }

    @Test
    fun `도박 사이트는 호스트 토큰이 정확히 일치할 때만 걸린다`() {
        assertEquals(RiskGrade.HIGH, grade("sports-toto.top"))
        assertEquals(RiskGrade.HIGH, grade("live-casino.cc"))
        // "betterlife", "slotech"까지 걸리면 정상 사이트가 무더기로 빨강이 된다.
        // (경고가 안 붙을 뿐 '안전'이라고 하지도 않는다 — 아래 UNKNOWN 테스트 참고)
        assertFalse(warned("betterlife.co.kr"))
        assertFalse(warned("slotech.com", "산업용 부품"))
    }

    /** 어르신 화면에 경고(주의·위험)가 뜨는가. */
    private fun warned(host: String, title: String = "제목입니다", snippet: String = ""): Boolean =
        grade(host, title, snippet) in setOf(RiskGrade.MEDIUM, RiskGrade.HIGH)

    @Test
    fun `상표를 흉내 낸 주소는 등록 도메인이 다르면 걸린다`() {
        assertTrue(grade("netflix-free.xyz") != RiskGrade.LOW)
        assertTrue(grade("tving-vip.shop") != RiskGrade.LOW)
        assertTrue(grade("naver.secure-login.top") != RiskGrade.LOW)
        // 진짜 네이버·카카오뱅크는 걸리면 안 된다
        assertEquals(RiskGrade.LOW, grade("blog.naver.com"))
        assertEquals(RiskGrade.LOW, grade("kakaobank.com"))
    }

    @Test
    fun `IP 직결과 APK 배포는 확정 신호다`() {
        assertTrue(grade("175&46&176&12".replace('&', '.'), "무료영화 다시보기") != RiskGrade.LOW)
        assertTrue(grade("drama-app.top", "드라마 무료 앱", "apk 다운로드 바로가기") != RiskGrade.LOW)
        // "APK"를 설명하는 정상 기사까지 걸면 안 된다
        assertEquals(
            RiskGrade.LOW,
            grade("yna.co.kr", "출처 불명 apk 주의보", "보안 전문가가 설명하는 스미싱 수법")
        )
    }

    // ── 규칙만으로 확정되는 안전 (역시 판별기를 부르지 않는다) ────────

    @Test
    fun `공식 OTT와 방송사는 규칙만으로 안전`() {
        for (host in listOf("tving.com", "wavve.com", "netflix.com", "kbs.co.kr", "coupangplay.com")) {
            val verdict = SerpRules.resolve(signals.of(result(host, "드라마 다시보기")))
            assertTrue("$host — 규칙이 결론을 내야 한다", verdict != null)
            assertEquals(host, RiskGrade.LOW, verdict!!.grade)
        }
    }

    @Test
    fun `공공기관 접미사는 목록에 없어도 신뢰한다`() {
        assertEquals(RiskGrade.LOW, grade("nhis.or.kr", "건강보험 안내"))
        assertEquals(RiskGrade.LOW, grade("seoul.go.kr", "서울시 공고"))
    }

    // ── 규칙이 결론을 못 내는 경우 = AI 트리거 ──────────────────────

    @Test
    fun `처음 보는 평범한 도메인은 판별기로 넘긴다`() {
        val found = signals.of(result("onnada.com", "온나다 TV 편성표"))
        assertEquals("규칙이 결론을 내면 안 된다", null, SerpRules.resolve(found))
    }

    @Test
    fun `신뢰 도메인이어도 위험 문구가 있으면 판별기로 넘긴다`() {
        // 티스토리 자체는 정상이지만 이 글은 불법 사이트를 안내한다.
        // 도메인만 보고 초록을 칠하면 어르신을 그대로 그곳으로 보내게 된다.
        val found = signals.of(
            result("blog.tistory.com", "무료 다시보기 사이트 순위 TOP10", "링크모음 2026 최신판")
        )
        assertEquals(null, SerpRules.resolve(found))
        assertTrue("위험 신호가 잡혀 있어야 한다", found.any { it.weight > 0 })
    }

    @Test
    fun `신뢰 가산이 확정 신호를 지우지는 못한다`() {
        // 네이버 카페에 올라온 도박 권유 글. 도메인이 네이버라는 이유로 '안전'이
        // 되면 어르신을 그대로 그 글로 보내게 된다.
        val found = signals.of(
            result("cafe.naver.com", "스포츠 중계", "먹튀 없는 안전 놀이터 추천")
        )

        // 확정 신호(55)가 '위험'(70)에는 못 미치므로 판별기가 최종 판단을 한다
        assertEquals(null, SerpRules.resolve(found))
        // 판별기를 못 쓰는 상황(키 없음·상한·오프라인)에서도 '안전'으로는 안 떨어진다
        assertTrue(RiskAggregator.ruleVerdict(found).grade != RiskGrade.LOW)
    }

    @Test
    fun `호스트를 못 읽으면 신호가 없다`() {
        assertTrue(signals.of(result("", "제목")).isEmpty())
    }

    @Test
    fun `잘 알려진 곳에는 신뢰 신호가 붙고 위험 신호는 없다`() {
        val found = signals.of(result("netflix.com", "넷플릭스"))
        assertTrue(found.any { it.weight < 0 })
        assertFalse(found.any { it.weight > 0 })
    }
}
