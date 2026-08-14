package com.senioradguard.url

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 노드 순회는 실기기가 필요해 여기서 덮지 못한다. 순회 결과를 실제로 주소로
 * 바꾸는 부분([LinkHarvester.urlInText])만 순수 함수라 검증한다 — 오탐이 나면
 * 파일 이름과 소수점 숫자까지 위험도 판별로 넘어간다.
 */
class LinkHarvesterTest {

    @Test
    fun `스킴이 붙은 주소를 우선한다`() {
        assertEquals(
            "https://ad.example.com/click",
            LinkHarvester.urlInText("스폰서 example.co.kr 자세히 보기 https://ad.example.com/click")
        )
    }

    @Test
    fun `화면에 드러난 도메인을 읽는다`() {
        assertEquals("example.co.kr", LinkHarvester.urlInText("스폰서 · example.co.kr"))
        assertEquals("shop.example.com/sale", LinkHarvester.urlInText("지금 shop.example.com/sale 방문"))
    }

    @Test
    fun `문장 끝의 구두점은 주소에서 뗀다`() {
        assertEquals("https://example.com/a", LinkHarvester.urlInText("여기(https://example.com/a)"))
        assertEquals("example.com", LinkHarvester.urlInText("출처는 example.com."))
    }

    // 실기기 확인에서 크롬이 이미지 노드에도 targetUrl을 채워 주는 것이 드러났다.
    // 거르지 않으면 이미지 CDN 호스트마다 판별기 호출이 한 건씩 나간다
    @Test
    fun `눌러도 이동하지 않는 주소는 링크가 아니다`() {
        assertTrue(LinkHarvester.isStaticAsset("https://img8.yna.co.kr/photo/yna/YH/2026/PYH.jpg"))
        assertTrue(LinkHarvester.isStaticAsset("https://cdn.example.com/a/b.PNG?v=3"))
        assertTrue(LinkHarvester.isStaticAsset("https://cdn.example.com/app.js#x"))
    }

    @Test
    fun `보통의 링크는 그대로 둔다`() {
        assertFalse(LinkHarvester.isStaticAsset("https://ad.yna.co.kr/RealMedia/ads/click_lx.ads/1"))
        assertFalse(LinkHarvester.isStaticAsset("https://shop.example.com/jpg-printer/sale"))
        assertFalse(LinkHarvester.isStaticAsset("example.com"))
    }

    // 마지막 라벨을 검사하지 않으면 파일 이름이 전부 주소로 읽힌다
    @Test
    fun `주소가 아닌 것은 걸러낸다`() {
        assertNull(LinkHarvester.urlInText("index.html"))
        assertNull(LinkHarvester.urlInText("사진.jpg"))
        assertNull(LinkHarvester.urlInText("가격 12.900원"))
        assertNull(LinkHarvester.urlInText("광고를 눌러보세요"))
        assertNull(LinkHarvester.urlInText(""))
    }
}
