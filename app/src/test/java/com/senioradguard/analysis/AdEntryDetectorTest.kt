package com.senioradguard.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdEntryDetectorTest {

    private var now = 0L
    private val detector = AdEntryDetector { now }

    @Test
    fun 광고_클릭_직후의_이동은_광고발이다() {
        detector.recordAdClick()
        now += 1_000

        assertEquals(ShieldReason.AD_CLICK, detector.reasonForNavigation("news.example.com"))
    }

    @Test
    fun 시간_창이_지난_이동은_광고발이_아니다() {
        detector.recordAdClick()
        now += AdEntryDetector.CLICK_NAV_WINDOW_MS + 1

        assertNull(detector.reasonForNavigation("news.example.com"))
    }

    @Test
    fun 광고_밖_클릭은_광고_클릭_대기를_취소한다() {
        detector.recordAdClick()
        now += 500
        detector.recordNonAdClick()
        now += 500

        assertNull(detector.reasonForNavigation("news.example.com"))
    }

    @Test
    fun 같은_클릭으로_가림막이_두_번_뜨지_않는다() {
        detector.recordAdClick()
        now += 500

        assertEquals(ShieldReason.AD_CLICK, detector.reasonForNavigation("a.com"))
        assertNull(detector.reasonForNavigation("a.com"))
    }

    @Test
    fun 클릭_대기가_없어도_리다이렉터_도메인이면_광고발이다() {
        assertEquals(
            ShieldReason.AD_REDIRECTOR,
            detector.reasonForNavigation("adclick.g.doubleclick.net")
        )
    }

    @Test
    fun 클릭_대기도_리다이렉터도_아니면_개입하지_않는다() {
        assertNull(detector.reasonForNavigation("hankyung.com"))
        assertNull(detector.reasonForNavigation(null))
    }

    @Test
    fun 호스트를_모르는_이동도_클릭_대기가_있으면_광고발이다() {
        // 앱 → 브라우저 전환 직후에는 주소창이 아직 없을 수 있다
        detector.recordAdClick()
        now += 200

        assertEquals(ShieldReason.AD_CLICK, detector.reasonForNavigation(null))
    }

    @Test
    fun 리다이렉터는_서브도메인까지_잡는다() {
        assertTrue(AdEntryDetector.isAdRedirector("doubleclick.net"))
        assertTrue(AdEntryDetector.isAdRedirector("adclick.g.doubleclick.net"))
        assertTrue(AdEntryDetector.isAdRedirector("www.googleadservices.com"))
    }

    @Test
    fun 이름이_비슷한_일반_도메인은_리다이렉터가_아니다() {
        assertFalse(AdEntryDetector.isAdRedirector("notdoubleclick.net"))
        assertFalse(AdEntryDetector.isAdRedirector("doubleclick.net.evil.com"))
        assertFalse(AdEntryDetector.isAdRedirector("hankyung.com"))
    }

    // ── 광고성 도착 판별 (웹 광고 주 경로 — 실기기에서 클릭 이벤트 부재 확인) ──

    @Test
    fun 클릭_추적_파라미터가_붙은_도착은_광고발이다() {
        // 실기기 검증에서 실제로 도착한 사기성 랜딩 URL 형태
        assertTrue(AdEntryDetector.isAdLanding("ho-ok.co.kr/a78a/?utm_term=google_KT_j2&gad_source=1"))
        assertTrue(AdEntryDetector.isAdLanding("https://shop.example.com/p?gclid=abc123"))
        assertTrue(AdEntryDetector.isAdLanding("https://a.com/?n_media=27758&n_query=관절"))
        assertTrue(AdEntryDetector.isAdLanding("https://a.com/?utm_medium=cpc&utm_source=naver"))
    }

    @Test
    fun 일반_utm이나_파라미터_없는_도착은_광고발이_아니다() {
        assertFalse(AdEntryDetector.isAdLanding("https://news.example.com/article/123"))
        assertFalse(AdEntryDetector.isAdLanding("https://a.com/?utm_source=newsletter&utm_medium=email"))
        assertFalse(AdEntryDetector.isAdLanding("https://a.com/?id=3&page=2"))
    }

    @Test
    fun 파라미터_이름의_부분_일치는_오탐하지_않는다() {
        assertFalse(AdEntryDetector.isAdLanding("https://a.com/?mygclid=1"))
        assertFalse(AdEntryDetector.isAdLanding("https://a.com/?gclid_backup=1"))
    }

    @Test
    fun 도착_URL의_광고_지문으로도_가림막_사유가_나온다() {
        assertEquals(
            ShieldReason.AD_LANDING,
            detector.reasonForNavigation("ho-ok.co.kr", "ho-ok.co.kr/a78a/?gad_source=1")
        )
        assertNull(detector.reasonForNavigation("news.com", "https://news.com/article/1"))
    }

    @Test
    fun 국내_광고망_리다이렉터도_잡는다() {
        // 실기기에서 실제로 관측된 popIn 경유 (네이트 뉴스 추천위젯 광고)
        assertTrue(AdEntryDetector.isAdRedirector("trace.popin.cc"))
        assertTrue(AdEntryDetector.isAdRedirector("mobon.net"))
    }

    @Test
    fun 직전_호스트가_리다이렉터였으면_현재_페이지는_광고_도착지다() {
        // nate → trace.popin.cc → news.wec.co.kr 의 두 번째 전환.
        // 최종 랜딩 URL에 추적 파라미터가 없어도 잡아야 한다.
        assertEquals(
            ShieldReason.AD_REDIRECTOR,
            detector.reasonForNavigation(
                "news.wec.co.kr",
                "news.wec.co.kr/article/x",
                previousHost = "trace.popin.cc"
            )
        )
        // 직전이 일반 사이트면 신호가 아니다
        assertNull(
            detector.reasonForNavigation(
                "news.wec.co.kr",
                "news.wec.co.kr/article/x",
                previousHost = "m.news.nate.com"
            )
        )
    }
}
