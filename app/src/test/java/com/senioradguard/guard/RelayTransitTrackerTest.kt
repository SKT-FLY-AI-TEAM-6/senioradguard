package com.senioradguard.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "광고를 눌러서 왔는가"를 주소 흔적으로 가른다.
 *
 * 판정을 느슨하게 하면 직접 들어간 쇼핑에도 경고가 붙고, 조이면 광고를 놓친다.
 * 여기 있는 사례는 대부분 실기기 로그에서 그대로 가져왔다.
 */
class RelayTransitTrackerTest {

    private var clock = 100_000L
    private fun tracker() = RelayTransitTracker { clock }

    private fun visit(t: RelayTransitTracker, host: String, afterMs: Long = 0): Boolean {
        clock += afterMs
        return t.onHost(host)
    }

    // 실기기 로그 그대로:
    //   m.yonhapnewstv.co.kr → clickads.co.kr(0.22초) → link.coupang.com → coupang.com
    @Test
    fun `광고 중계를 거쳐 오면 잡는다`() {
        val t = tracker()
        assertFalse(visit(t, "m.yonhapnewstv.co.kr"))
        assertFalse(visit(t, "clickads.co.kr", afterMs = 13_000))

        assertTrue("중계를 0.22초 만에 지나쳤다", visit(t, "link.coupang.com", afterMs = 220))
    }

    // 주소를 직접 치거나 즐겨찾기로 들어가면 중계가 없다.
    @Test
    fun `직접 들어가면 잡지 않는다`() {
        val t = tracker()
        assertFalse(visit(t, "m.coupang.com"))
        assertFalse("같은 사이트 안의 이동", visit(t, "coupang.com", afterMs = 900))
    }

    // 뉴스를 읽다가 주소창에 쿠팡을 직접 쳐 넣은 경우.
    // 뉴스에 한참 머물렀으므로 중계가 아니다.
    @Test
    fun `읽던 사이트에서 곧장 이동하면 잡지 않는다`() {
        val t = tracker()
        assertFalse(visit(t, "m.yonhapnewstv.co.kr"))
        assertFalse(visit(t, "coupang.com", afterMs = 30_000))
    }

    @Test
    fun `같은 사이트의 하위 도메인은 새 방문이 아니다`() {
        val t = tracker()
        visit(t, "news.naver.com")
        assertFalse(visit(t, "m.naver.com", afterMs = 500))
    }

    // co.kr까지 잘라야 서로 다른 사이트로 본다. 두 조각만 쓰면
    // a.co.kr과 b.co.kr이 같은 "co.kr"이 되어 중계를 못 알아본다.
    @Test
    fun `한국 2단계 도메인을 구분한다`() {
        assertEquals("yonhapnewstv.co.kr", RelayTransitTracker.registrable("m.yonhapnewstv.co.kr"))
        assertEquals("clickads.co.kr", RelayTransitTracker.registrable("clickads.co.kr"))
        assertEquals("coupang.com", RelayTransitTracker.registrable("link.coupang.com"))
        assertEquals("naver.com", RelayTransitTracker.registrable("www.naver.com"))
    }

    @Test
    fun `첫 방문은 판단할 근거가 없다`() {
        assertFalse(tracker().onHost("coupang.com"))
    }

    @Test
    fun `오래 머문 사이트는 중계가 아니다`() {
        val t = tracker()
        visit(t, "a.com")
        visit(t, "b.com", afterMs = 1_000)
        assertFalse("b에 10초 머물렀다", visit(t, "coupang.com", afterMs = 10_000))
    }
}
