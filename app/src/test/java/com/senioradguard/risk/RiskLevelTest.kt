package com.senioradguard.risk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 등급이 개입 강도를 정한다. 여기가 틀리면 알려주기만 하면 될 것을 막아버리거나,
 * 막아야 할 것을 그냥 지나친다.
 */
class RiskLevelTest {

    @Test
    fun `확신이 높으면 고위험`() {
        assertEquals(RiskLevel.HIGH, RiskLevel.ofConfidence(0.95f))
        assertEquals(RiskLevel.HIGH, RiskLevel.ofConfidence(0.8f))
    }

    @Test
    fun `확신이 부족하면 중위험`() {
        assertEquals(RiskLevel.MEDIUM, RiskLevel.ofConfidence(0.79f))
        assertEquals(RiskLevel.MEDIUM, RiskLevel.ofConfidence(0.6f))
    }

    // 표시 임계값(0.6)과 차단 임계값(0.8)은 다른 질문에 답한다.
    // "테두리를 그려도 되나"와 "사용자를 멈춰 세워도 되나"는 요구 수준이 다르다.
    @Test
    fun `표시는 되지만 차단은 안 되는 구간이 있다`() {
        assertEquals(RiskLevel.MEDIUM, RiskLevel.ofConfidence(0.7f))
    }

    @Test
    fun `보호 강도가 켜는 기능`() {
        assertFalse("1단계는 Layer 2를 돌리지 않는다", ProtectionLevel.LABELS_ONLY.usesAi)
        assertFalse(ProtectionLevel.LABELS_ONLY.usesUrlBlock)

        assertTrue(ProtectionLevel.WITH_AI.usesAi)
        assertFalse("2단계는 URL 차단까지 가지 않는다", ProtectionLevel.WITH_AI.usesUrlBlock)

        assertTrue(ProtectionLevel.WITH_URL_BLOCK.usesAi)
        assertTrue(ProtectionLevel.WITH_URL_BLOCK.usesUrlBlock)
    }

    @Test
    fun `알 수 없는 값은 기본값으로 떨어진다`() {
        // 원격에서 내려온 값이 깨졌을 때 보호가 0단계가 되면 안 된다
        assertEquals(ProtectionLevel.DEFAULT, ProtectionLevel.of(null))
        assertEquals(ProtectionLevel.DEFAULT, ProtectionLevel.of(0))
        assertEquals(ProtectionLevel.DEFAULT, ProtectionLevel.of(99))
    }

    @Test
    fun `등급 문자열은 서버 스키마와 맞는다`() {
        assertEquals("low", RiskLevel.LOW.wire)
        assertEquals("medium", RiskLevel.MEDIUM.wire)
        assertEquals("high", RiskLevel.HIGH.wire)
    }
}
