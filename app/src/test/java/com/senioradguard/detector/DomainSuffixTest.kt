package com.senioradguard.detector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 서픽스 매칭. 사기 사이트는 하위 도메인을 계속 갈아 만들기 때문에,
 * `example.com`을 막으면 `login.example.com`도 함께 막혀야 의미가 있다.
 */
class DomainSuffixTest {

    private val blocked = setOf("evil.com", "phish.co.kr")

    @Test
    fun `정확히 같은 도메인은 막는다`() {
        assertTrue(DomainMatcher.isBlocked("evil.com", blocked))
        assertTrue(DomainMatcher.isBlocked("phish.co.kr", blocked))
    }

    @Test
    fun `하위 도메인도 막는다`() {
        assertTrue(DomainMatcher.isBlocked("login.evil.com", blocked))
        assertTrue(DomainMatcher.isBlocked("a.b.c.evil.com", blocked))
    }

    // "evil.com"을 막았다고 "notevil.com"까지 막으면 멀쩡한 사이트를 잡는다.
    // 점 단위로 끊어야지 문자열 끝만 비교하면 이 사고가 난다.
    @Test
    fun `이름이 겹치는 다른 도메인은 막지 않는다`() {
        assertFalse(DomainMatcher.isBlocked("notevil.com", blocked))
        assertFalse(DomainMatcher.isBlocked("evil.com.kr", blocked))
    }

    @Test
    fun `목록에 없으면 통과`() {
        assertFalse(DomainMatcher.isBlocked("naver.com", blocked))
    }

    @Test
    fun `목록이 비었으면 아무것도 막지 않는다`() {
        assertFalse(DomainMatcher.isBlocked("evil.com", emptySet()))
    }
}
