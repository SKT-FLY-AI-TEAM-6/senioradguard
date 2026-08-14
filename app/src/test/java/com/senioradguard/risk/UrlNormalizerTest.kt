package com.senioradguard.risk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlNormalizerTest {

    @Test
    fun 추적_파라미터만_다른_같은_광고는_같은_키가_된다() {
        val a = UrlNormalizer.normalize("https://shop.example.com/deal?id=3&utm_source=naver&utm_campaign=aug")
        val b = UrlNormalizer.normalize("https://shop.example.com/deal?utm_source=daum&id=3&gclid=xyz")

        assertEquals("https://shop.example.com/deal?id=3", a)
        assertEquals(a, b)
    }

    @Test
    fun 파라미터_순서만_달라도_같은_키가_된다() {
        val a = UrlNormalizer.normalize("https://example.com/p?a=1&b=2")
        val b = UrlNormalizer.normalize("https://example.com/p?b=2&a=1")

        assertEquals(a, b)
    }

    @Test
    fun 스킴과_호스트는_소문자화되고_경로는_유지된다() {
        assertEquals(
            "https://example.com/Path/To",
            UrlNormalizer.normalize("HTTPS://Example.COM/Path/To")
        )
    }

    @Test
    fun 기본_포트는_제거되고_다른_포트는_남는다() {
        assertEquals("https://example.com/", UrlNormalizer.normalize("https://example.com:443/"))
        assertEquals("http://example.com/", UrlNormalizer.normalize("http://example.com:80/"))
        assertEquals("http://example.com:8080/", UrlNormalizer.normalize("http://example.com:8080/"))
    }

    @Test
    fun 프래그먼트는_제거된다() {
        assertEquals(
            "https://example.com/page",
            UrlNormalizer.normalize("https://example.com/page#section2")
        )
    }

    @Test
    fun 스킴이_없으면_https로_가정한다() {
        assertEquals("https://example.com/event", UrlNormalizer.normalize("example.com/event"))
    }

    @Test
    fun 빈_경로는_슬래시가_된다() {
        assertEquals("https://example.com/", UrlNormalizer.normalize("https://example.com"))
    }

    @Test
    fun 피싱형_userinfo는_실제_호스트만_남긴다() {
        // bank.com@evil.com의 실제 접속지는 evil.com이다
        assertEquals(
            "https://evil.com/login",
            UrlNormalizer.normalize("https://bank.com@evil.com/login")
        )
    }

    @Test
    fun http_https가_아니면_null() {
        assertNull(UrlNormalizer.normalize("market://details?id=com.bad.app"))
        assertNull(UrlNormalizer.normalize("intent://scan/#Intent;end"))
        assertNull(UrlNormalizer.normalize("ftp://example.com/file"))
    }

    @Test
    fun URL이_아닌_문구는_null() {
        assertNull(UrlNormalizer.normalize(""))
        assertNull(UrlNormalizer.normalize("   "))
        assertNull(UrlNormalizer.normalize("지금 바로 설치하세요"))
        assertNull(UrlNormalizer.normalize("https://host with space/path"))
    }

    @Test
    fun hostOf는_호스트만_돌려준다() {
        assertEquals("evil.com", UrlNormalizer.hostOf("https://bank.com@evil.com/login?a=1"))
        assertEquals("shop.example.com", UrlNormalizer.hostOf("shop.example.com/deal"))
        assertNull(UrlNormalizer.hostOf("설치하세요"))
    }
}
