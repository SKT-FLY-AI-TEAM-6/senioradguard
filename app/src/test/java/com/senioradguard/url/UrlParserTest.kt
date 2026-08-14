package com.senioradguard.url

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlParserTest {

    private fun components(raw: String) = UrlParser.components(raw)

    @Test
    fun `팀 스키마 예시를 그대로 분해한다`() {
        val c = components("https://ad.yna.co.kr/RealMedia/ads/click_lx.ads/12345")!!

        assertEquals("https", c.protocol)
        assertEquals("ad.yna.co.kr", c.domain)
        assertEquals("yna.co.kr", c.rootDomain)
        assertEquals("co.kr", c.tld)
        assertEquals("ad", c.subdomain)
        assertEquals("/RealMedia/ads/click_lx.ads/12345", c.path)
    }

    // 이게 없으면 모든 한국 사이트의 등록 도메인이 "co.kr"로 뭉개져
    // 캐시도 블랙리스트도 통째로 오작동한다
    @Test
    fun `두 라벨짜리 공개 접미사를 알아본다`() {
        assertEquals("yna.co.kr", components("https://www.yna.co.kr/")!!.rootDomain)
        assertEquals("example.com", components("https://a.b.example.com/")!!.rootDomain)
        assertEquals("a.b", components("https://a.b.example.com/")!!.subdomain)
    }

    // 크롬 주소창은 스킴을 떼고 보여준다. 여기서 죽으면 사후 판별 경로가 통째로 멈춘다
    @Test
    fun `스킴이 없어도 분해한다`() {
        val c = components("yna.co.kr/article/1")!!
        assertEquals("", c.protocol)
        assertEquals("yna.co.kr", c.domain)
        assertEquals("/article/1", c.path)
    }

    @Test
    fun `포트와 사용자 정보를 호스트에서 걷어낸다`() {
        assertEquals("evil.xyz", components("http://naver.com@evil.xyz/login")!!.domain)
        assertEquals("example.com", components("https://example.com:8443/x")!!.domain)
    }

    @Test
    fun `쿼리와 프래그먼트는 path에 남는다`() {
        // 추적 파라미터와 유인 문구가 거기 들어 있어 잘라내면 판단 근거가 사라진다
        assertEquals("/go?utm_source=ad&id=1", components("https://a.com/go?utm_source=ad&id=1")!!.path)
    }

    @Test
    fun `IP 주소는 등록 도메인이 곧 호스트다`() {
        val c = components("http://203.0.113.9/pay")!!
        assertEquals("203.0.113.9", c.domain)
        assertEquals("203.0.113.9", c.rootDomain)
        assertEquals("", c.tld)
    }

    @Test
    fun `웹 링크가 아니면 거른다`() {
        assertNull(components("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(components("market://details?id=com.evil"))
        assertNull(components("javascript:alert(1)"))
    }

    @Test
    fun `호스트를 읽을 수 없으면 null`() {
        assertNull(components(""))
        assertNull(components("   "))
        assertNull(components("광고를 눌러보세요"))
        assertNull(components("localhost"))       // 라벨이 하나뿐
        assertNull(components("co.kr"))           // 접미사만 있고 등록 도메인이 없다
    }

    @Test
    fun `단축 주소를 알아본다`() {
        assertTrue(UrlParser.isShortener("bit.ly"))
        assertTrue(UrlParser.isShortener("www.me2.do"))
        assertFalse(UrlParser.isShortener("yna.co.kr"))
    }

    @Test
    fun `parse는 문맥과 단축 여부를 함께 채운다`() {
        val link = UrlParser.parse(
            raw = "https://bit.ly/3abc",
            sourcePageUrl = "yna.co.kr",
            anchorText = "지금 확인하세요",
            isAdElement = true
        )!!

        assertEquals("bit.ly", link.cacheKey)
        assertTrue(link.context.isShortener)
        assertTrue(link.context.isAdElement)
        assertEquals("지금 확인하세요", link.context.anchorText)
    }

    // 목록에 "evil.co.kr"만 있어도 하위 호스트가 걸려야 한다
    @Test
    fun `호스트 접미사를 넓은 것부터 만든다`() {
        assertEquals(
            listOf("ad.evil.co.kr", "evil.co.kr", "co.kr", "kr"),
            UrlParser.hostSuffixes("ad.evil.co.kr")
        )
        assertEquals(emptyList<String>(), UrlParser.hostSuffixes(""))
    }
}
