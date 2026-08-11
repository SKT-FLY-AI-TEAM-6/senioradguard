package com.senioradguard.detector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainMatcherTest {

    private val blocked = setOf("doubleclick.net", "googlesyndication.com", "co.kr")

    @Test
    fun `정확히 일치하면 차단`() {
        assertTrue(DomainMatcher.isBlocked("doubleclick.net", blocked))
    }

    @Test
    fun `서브도메인도 차단`() {
        assertTrue(DomainMatcher.isBlocked("ads.g.doubleclick.net", blocked))
    }

    @Test
    fun `목록에 없으면 통과`() {
        assertFalse(DomainMatcher.isBlocked("example.com", blocked))
    }

    // 기존 endsWith 구현의 오탐 버그 — 라벨 경계를 무시해 통과시켰다
    @Test
    fun `라벨 중간에서 끝나는 문자열은 차단하지 않음`() {
        assertFalse(DomainMatcher.isBlocked("notdoubleclick.net", blocked))
        assertFalse(DomainMatcher.isBlocked("evil-googlesyndication.com", blocked))
    }

    @Test
    fun `대소문자 무시`() {
        assertTrue(DomainMatcher.isBlocked("ADS.DoubleClick.NET", blocked))
    }

    @Test
    fun `끝에 붙은 루트 점도 처리`() {
        assertTrue(DomainMatcher.isBlocked("doubleclick.net.", blocked))
    }

    @Test
    fun `빈 문자열은 통과`() {
        assertFalse(DomainMatcher.isBlocked("", blocked))
    }
}
