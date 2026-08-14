package com.senioradguard.risk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 기획서 3.2절 매트릭스의 전수 검증 + 어떤 리팩터링에도 깨지면 안 되는
 * 불변 속성 두 가지. 이 테스트가 곧 대응 정책의 명세다.
 */
class RiskPolicyTest {

    private fun assessed(level: RiskLevel) = RiskAssessment.Assessed(level, "테스트 사유")

    // ── 매트릭스 전수 (3 보호수준 × 3 등급) ──

    @Test
    fun 저위험은_모든_보호수준에서_표시_후_허용() {
        for (protection in ProtectionLevel.entries) {
            assertEquals(
                "보호수준=$protection",
                UserAction.LABEL_AND_ALLOW,
                RiskPolicy.actionFor(protection, assessed(RiskLevel.LOW))
            )
        }
    }

    @Test
    fun 중위험_안내형은_이유_표시_후_사용자_선택() {
        assertEquals(
            UserAction.WARN_WITH_CHOICE,
            RiskPolicy.actionFor(ProtectionLevel.GUIDE, assessed(RiskLevel.MEDIUM))
        )
    }

    @Test
    fun 중위험_균형형은_투터치_확인() {
        assertEquals(
            UserAction.WARN_TWO_TOUCH,
            RiskPolicy.actionFor(ProtectionLevel.BALANCED, assessed(RiskLevel.MEDIUM))
        )
    }

    @Test
    fun 중위험_강력보호형은_기본_차단() {
        assertEquals(
            UserAction.BLOCK_DEFAULT,
            RiskPolicy.actionFor(ProtectionLevel.STRICT, assessed(RiskLevel.MEDIUM))
        )
    }

    @Test
    fun 고위험은_모든_보호수준에서_자동_차단() {
        for (protection in ProtectionLevel.entries) {
            assertEquals(
                "보호수준=$protection",
                UserAction.BLOCK_ALWAYS,
                RiskPolicy.actionFor(protection, assessed(RiskLevel.HIGH))
            )
        }
    }

    // ── 불변 속성 ──

    @Test
    fun 분석하지_못한_광고는_어떤_보호수준에서도_허용으로_흘러가지_않는다() {
        val unverified = RiskAssessment.Unverified("목적 URL 미확보")
        for (protection in ProtectionLevel.entries) {
            val action = RiskPolicy.actionFor(protection, unverified)
            assertEquals("보호수준=$protection", UserAction.SHOW_LIMITATION, action)
            assertNotEquals("보호수준=$protection", UserAction.LABEL_AND_ALLOW, action)
        }
    }

    @Test
    fun 기본_보호수준은_균형형() {
        assertEquals(ProtectionLevel.BALANCED, ProtectionLevel.DEFAULT)
    }

    @Test
    fun 위험도_배지는_색상_없이도_읽히는_텍스트를_가진다() {
        assertEquals("광고", RiskLevel.LOW.badgeText)
        assertEquals("주의", RiskLevel.MEDIUM.badgeText)
        assertEquals("위험", RiskLevel.HIGH.badgeText)
    }
}
