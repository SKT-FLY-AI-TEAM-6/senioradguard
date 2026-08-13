package com.guradian.rule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserHostTest {

    private val chrome = BrowserHost.CHROME

    private fun host() = BrowserHost()

    @Test
    fun `주소창에서 도메인을 읽는다`() {
        assertEquals("hankyung.com", host().resolve(chrome, "hankyung.com/article/2026"))
    }

    @Test
    fun `스킴을 제거한다`() {
        assertEquals("mk.co.kr", host().resolve(chrome, "https://mk.co.kr/news"))
        assertEquals("mk.co.kr", host().resolve(chrome, "http://mk.co.kr"))
    }

    @Test
    fun `www를 제거한다`() {
        assertEquals("naver.com", host().resolve(chrome, "https://www.naver.com/"))
    }

    @Test
    fun `앞뒤 공백을 무시한다`() {
        assertEquals("naver.com", host().resolve(chrome, "  naver.com  "))
    }

    // 크롬이 주소창에 검색어를 보여줄 때가 있다 — 도메인이 아니면 받지 않는다
    @Test
    fun `점이 없는 문자열은 도메인으로 보지 않는다`() {
        assertNull(host().resolve(chrome, "광고 차단"))
    }

    // 크롬은 아래로 스크롤하면 주소창을 접어 트리에서 없애버린다.
    // 여기서 물러나면 같은 페이지가 스크롤 위치에 따라 두 개의 키를 갖는다.
    @Test
    fun `주소창이 접히면 마지막 도메인으로 물러난다`() {
        val h = host()
        assertEquals("hankyung.com", h.resolve(chrome, "hankyung.com/article/1"))
        assertEquals("hankyung.com", h.resolve(chrome, ""))
        assertEquals("hankyung.com", h.resolve(chrome, null))
    }

    @Test
    fun `접힘 폴백은 점 없는 문자열에도 적용된다`() {
        val h = host()
        h.resolve(chrome, "mk.co.kr")
        assertEquals("mk.co.kr", h.resolve(chrome, "검색어"))
    }

    @Test
    fun `본 적 없는 상태에서 접혀 있으면 null`() {
        assertNull(host().resolve(chrome, ""))
    }

    @Test
    fun `크롬이 아니면 null`() {
        assertNull(host().resolve("com.instagram.android", "hankyung.com"))
    }

    // 다른 앱 카드에 남의 도메인이 붙으면 캐시가 뒤섞인다
    @Test
    fun `다른 앱으로 넘어가면 기억한 도메인을 버린다`() {
        val h = host()
        h.resolve(chrome, "hankyung.com")
        assertNull(h.resolve("com.instagram.android", null))
        assertNull(h.resolve(chrome, ""))
    }
}
