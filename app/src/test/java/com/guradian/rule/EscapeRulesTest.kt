package com.guradian.rule

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EscapeRulesTest {

    // ── isStorePackage ───────────────────────────────────────

    @Test
    fun `두 스토어를 인식한다`() {
        assertTrue(EscapeRules.isStorePackage("com.android.vending"))
        assertTrue(EscapeRules.isStorePackage("com.sec.android.app.samsungapps"))
    }

    @Test
    fun `감지 대상 앱은 스토어가 아니다`() {
        assertFalse(EscapeRules.isStorePackage("com.android.chrome"))
        assertFalse(EscapeRules.isStorePackage("com.google.android.youtube"))
        assertFalse(EscapeRules.isStorePackage(""))
    }

    // ── isInstallTrigger ─────────────────────────────────────

    @Test
    fun `설치 유도 문구를 인식한다`() {
        assertTrue(EscapeRules.isInstallTrigger("설치하기"))
        assertTrue(EscapeRules.isInstallTrigger("지금 설치"))
        assertTrue(EscapeRules.isInstallTrigger("무료 다운로드"))
        assertTrue(EscapeRules.isInstallTrigger("앱 다운로드"))
        assertTrue(EscapeRules.isInstallTrigger("지금 받기"))
        assertTrue(EscapeRules.isInstallTrigger("혜택 받기"))
        assertTrue(EscapeRules.isInstallTrigger("이벤트 참여"))
    }

    @Test
    fun `영문 문구도 대소문자 무시하고 인식한다`() {
        assertTrue(EscapeRules.isInstallTrigger("INSTALL NOW"))
        assertTrue(EscapeRules.isInstallTrigger("Free Download"))
        assertTrue(EscapeRules.isInstallTrigger("Get App"))
    }

    // 버튼 문구는 대개 앞뒤에 다른 말이 붙는다
    @Test
    fun `문구가 일부로 들어 있어도 인식한다`() {
        assertTrue(EscapeRules.isInstallTrigger("앱 설치하기 →"))
        assertTrue(EscapeRules.isInstallTrigger("  지금 설치  "))
    }

    // 광고를 피하려는 행동을 막으면 안 된다 — 여기 목적은 설치 개입이다
    @Test
    fun `광고 건너뛰기는 경고하지 않는다`() {
        assertFalse(EscapeRules.isInstallTrigger("광고 건너뛰기"))
        assertFalse(EscapeRules.isInstallTrigger("건너뛰기"))
        assertFalse(EscapeRules.isInstallTrigger("Skip Ad"))
        assertFalse(EscapeRules.isInstallTrigger("skip"))
    }

    @Test
    fun `광고 관리용 버튼도 경고하지 않는다`() {
        assertFalse(EscapeRules.isInstallTrigger("광고 신고"))
        assertFalse(EscapeRules.isInstallTrigger("광고 정보"))
        assertFalse(EscapeRules.isInstallTrigger("광고 숨기기"))
        assertFalse(EscapeRules.isInstallTrigger("이 광고가 표시된 이유"))
    }

    @Test
    fun `무관한 버튼은 경고하지 않는다`() {
        assertFalse(EscapeRules.isInstallTrigger("확인"))
        assertFalse(EscapeRules.isInstallTrigger("좋아요"))
        assertFalse(EscapeRules.isInstallTrigger("댓글 쓰기"))
    }

    @Test
    fun `빈 문자열과 공백은 경고하지 않는다`() {
        assertFalse(EscapeRules.isInstallTrigger(""))
        assertFalse(EscapeRules.isInstallTrigger("   "))
    }
}
