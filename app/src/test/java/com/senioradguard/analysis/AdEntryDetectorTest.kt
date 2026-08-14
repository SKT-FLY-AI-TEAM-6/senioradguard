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
}
