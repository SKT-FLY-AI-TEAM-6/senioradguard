package com.senioradguard.analysis

import com.senioradguard.risk.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlRiskRulesTest {

    private fun assess(
        finalUrl: String = "https://example.com/page",
        hops: Int = 1,
        contentType: String? = "text/html; charset=utf-8",
        html: String? = "<html><body>일반 콘텐츠</body></html>"
    ) = UrlRiskRules.assess(finalUrl, hops, contentType, html)

    // ── 고위험 ──

    @Test
    fun APK_파일로_연결되면_고위험() {
        assertEquals(RiskLevel.HIGH, assess(finalUrl = "https://bad.com/app.apk", html = null).level)
        assertEquals(
            RiskLevel.HIGH,
            assess(contentType = "application/vnd.android.package-archive", html = null).level
        )
    }

    @Test
    fun 페이지_안의_APK_링크도_고위험() {
        val html = """<a href="https://cdn.bad.com/update.apk">지금 설치</a>"""
        assertEquals(RiskLevel.HIGH, assess(html = html).level)
    }

    @Test
    fun 광고_랜딩이_비밀번호를_요구하면_고위험() {
        val html = """<form><input type="password" name="pw"></form>"""
        assertEquals(RiskLevel.HIGH, assess(html = html).level)
    }

    @Test
    fun 카드번호_입력을_요구하면_고위험() {
        val html = """<input autocomplete="cc-number">"""
        assertEquals(RiskLevel.HIGH, assess(html = html).level)
    }

    @Test
    fun 주민등록번호_입력을_요구하면_고위험() {
        val html = """주민등록번호를 입력하세요 <input name="jumin">"""
        assertEquals(RiskLevel.HIGH, assess(html = html).level)
    }

    // ── 중위험 ──

    @Test
    fun 전화번호_수집_폼은_중위험() {
        // 실기기에서 확인한 보험 리드 수집 랜딩의 전형적 구조
        val html = """<form><input type="tel" name="phone"><button>무료 상담신청</button></form>"""
        val a = assess(html = html)
        assertEquals(RiskLevel.MEDIUM, a.level)
        assertTrue(a.reason.contains("전화번호"))
    }

    @Test
    fun 약한_신호가_두_개_이상이면_중위험() {
        val a = assess(
            finalUrl = "http://unknown-shop.xyz/deal",   // 비HTTPS
            hops = 4,                                     // 반복 리다이렉트
            html = "<html>정상적인 내용</html>"
        )
        assertEquals(RiskLevel.MEDIUM, a.level)
    }

    @Test
    fun 약한_신호_하나만으로는_중위험이_아니다() {
        assertEquals(RiskLevel.LOW, assess(finalUrl = "http://old-site.co.kr/", hops = 1).level)
        assertEquals(RiskLevel.LOW, assess(hops = 4).level)
    }

    // ── 저위험 ──

    @Test
    fun 위험_신호가_없으면_저위험() {
        val a = assess()
        assertEquals(RiskLevel.LOW, a.level)
    }

    @Test
    fun 본문을_못_읽었어도_URL에_위험_신호가_없으면_저위험() {
        // HTML이 아닌 응답(이미지 등) — 다운로드 금지 원칙으로 본문을 안 읽는다
        assertEquals(RiskLevel.LOW, assess(contentType = "image/png", html = null).level)
    }

    // ── 유효기간 ──

    @Test
    fun 위험할수록_판정_유효기간이_짧다() {
        assertTrue(UrlRiskRules.validityMs(RiskLevel.HIGH) < UrlRiskRules.validityMs(RiskLevel.MEDIUM))
        assertTrue(UrlRiskRules.validityMs(RiskLevel.MEDIUM) < UrlRiskRules.validityMs(RiskLevel.LOW))
    }
}
