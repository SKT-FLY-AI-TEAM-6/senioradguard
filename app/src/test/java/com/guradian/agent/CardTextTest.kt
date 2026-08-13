package com.guradian.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardTextTest {

    // ── mask ─────────────────────────────────────────────────

    @Test
    fun `휴대폰 번호를 지운다`() {
        assertEquals("연락처 [전화번호] 입니다", CardText.mask("연락처 010-1234-5678 입니다"))
        assertEquals("[전화번호]", CardText.mask("01012345678"))
    }

    @Test
    fun `카드번호를 지운다`() {
        assertEquals("결제 [카드번호]", CardText.mask("결제 1234-5678-9012-3456"))
        assertEquals("[카드번호]", CardText.mask("1234 5678 9012 3456"))
    }

    @Test
    fun `주민등록번호를 지운다`() {
        assertEquals("[주민번호]", CardText.mask("900101-1234567"))
    }

    // 전화번호 규칙이 먼저 돌면 카드번호 뒷자리를 먹어 카드번호가 남는다
    @Test
    fun `카드번호가 전화번호 규칙보다 먼저 적용된다`() {
        assertEquals("[카드번호]", CardText.mask("0101-2345-6789-0123"))
    }

    @Test
    fun `개인정보가 없으면 그대로 둔다`() {
        assertEquals("무료 배송 이벤트", CardText.mask("무료 배송 이벤트"))
    }

    // ── normalize ────────────────────────────────────────────

    // 카운트다운·가격이 매번 달라 캐시가 안 걸리는 문제를 막는 핵심 처리
    @Test
    fun `숫자가 다른 같은 광고는 같은 정규화 결과가 된다`() {
        assertEquals(
            CardText.normalize("특가 12,900원 남은시간 03:21"),
            CardText.normalize("특가 45,700원 남은시간 01:08")
        )
    }

    @Test
    fun `대소문자와 연속 공백을 정규화한다`() {
        assertEquals("free sale", CardText.normalize("  FREE   Sale  "))
    }

    @Test
    fun `정규화는 200자로 자른다`() {
        assertEquals(200, CardText.normalize("가".repeat(500)).length)
    }

    // ── cacheKey ─────────────────────────────────────────────

    @Test
    fun `같은 출처의 같은 문구는 같은 키`() {
        val a = CardText.cacheKey("hankyung.com", listOf("무료", "배송 이벤트"))
        val b = CardText.cacheKey("hankyung.com", listOf("무료 배송", "이벤트"))
        assertEquals(a, b)
    }

    // 사이트마다 키를 나눠야 같은 문구가 다른 사이트로 번지지 않는다
    @Test
    fun `출처가 다르면 다른 키`() {
        assertNotEquals(
            CardText.cacheKey("hankyung.com", listOf("무료 배송")),
            CardText.cacheKey("mk.co.kr", listOf("무료 배송"))
        )
    }

    @Test
    fun `문구가 다르면 다른 키`() {
        assertNotEquals(
            CardText.cacheKey("a.com", listOf("무료 배송")),
            CardText.cacheKey("a.com", listOf("오늘의 날씨"))
        )
    }

    @Test
    fun `키는 출처와 해시로 이루어진다`() {
        val key = CardText.cacheKey("a.com", listOf("무료"))
        assertTrue(key.startsWith("a.com|"))
        assertEquals(64, key.substringAfter('|').length)   // SHA-256 hex
    }

    // ── forClassifier ────────────────────────────────────────

    @Test
    fun `외부로 보내는 텍스트는 마스킹을 거친다`() {
        val sent = CardText.forClassifier(listOf("문의", "010-1234-5678"))
        assertFalse(sent.contains("1234-5678"))
        assertTrue(sent.contains("[전화번호]"))
    }

    @Test
    fun `외부로 보내는 텍스트는 400자로 자른다`() {
        assertEquals(400, CardText.forClassifier(listOf("가".repeat(1000))).length)
    }
}
