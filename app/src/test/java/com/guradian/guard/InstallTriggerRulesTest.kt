package com.guradian.guard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallTriggerRulesTest {

    @Test
    fun `설치 유도 문구를 인식한다`() {
        assertTrue(InstallTriggerRules.isInstallTrigger("설치하기"))
        assertTrue(InstallTriggerRules.isInstallTrigger("지금 설치"))
        assertTrue(InstallTriggerRules.isInstallTrigger("무료 다운로드"))
        assertTrue(InstallTriggerRules.isInstallTrigger("앱 다운로드"))
        assertTrue(InstallTriggerRules.isInstallTrigger("지금 받기"))
        assertTrue(InstallTriggerRules.isInstallTrigger("혜택 받기"))
        assertTrue(InstallTriggerRules.isInstallTrigger("이벤트 참여"))
    }

    @Test
    fun `영문 문구도 대소문자 무시하고 인식한다`() {
        assertTrue(InstallTriggerRules.isInstallTrigger("INSTALL NOW"))
        assertTrue(InstallTriggerRules.isInstallTrigger("Free Download"))
        assertTrue(InstallTriggerRules.isInstallTrigger("Get App"))
    }

    // 버튼 문구는 대개 앞뒤에 다른 말이 붙는다
    @Test
    fun `문구가 일부로 들어 있어도 인식한다`() {
        assertTrue(InstallTriggerRules.isInstallTrigger("앱 설치하기 →"))
        assertTrue(InstallTriggerRules.isInstallTrigger("  지금 설치  "))
    }

    // 광고를 피하려는 행동을 막으면 안 된다 — Layer 3의 목적은 설치 개입이다
    @Test
    fun `광고 건너뛰기는 경고하지 않는다`() {
        assertFalse(InstallTriggerRules.isInstallTrigger("광고 건너뛰기"))
        assertFalse(InstallTriggerRules.isInstallTrigger("건너뛰기"))
        assertFalse(InstallTriggerRules.isInstallTrigger("Skip Ad"))
        assertFalse(InstallTriggerRules.isInstallTrigger("skip"))
    }

    @Test
    fun `광고 관리용 버튼도 경고하지 않는다`() {
        assertFalse(InstallTriggerRules.isInstallTrigger("광고 신고"))
        assertFalse(InstallTriggerRules.isInstallTrigger("광고 정보"))
        assertFalse(InstallTriggerRules.isInstallTrigger("광고 숨기기"))
        assertFalse(InstallTriggerRules.isInstallTrigger("이 광고가 표시된 이유"))
    }

    @Test
    fun `무관한 버튼은 경고하지 않는다`() {
        assertFalse(InstallTriggerRules.isInstallTrigger("확인"))
        assertFalse(InstallTriggerRules.isInstallTrigger("좋아요"))
        assertFalse(InstallTriggerRules.isInstallTrigger("댓글 쓰기"))
    }

    @Test
    fun `빈 문자열과 공백은 경고하지 않는다`() {
        assertFalse(InstallTriggerRules.isInstallTrigger(""))
        assertFalse(InstallTriggerRules.isInstallTrigger("   "))
    }
}
