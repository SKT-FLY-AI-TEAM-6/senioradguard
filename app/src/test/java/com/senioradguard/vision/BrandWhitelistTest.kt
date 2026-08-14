package com.senioradguard.vision

import com.senioradguard.risk.RiskCategory
import com.senioradguard.risk.RiskLevel
import com.senioradguard.risk.RiskVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandWhitelistTest {

    private fun verdict(
        score: Int,
        category: RiskCategory,
        brand: String
    ) = RiskVerdict(category, RiskLevel.of(score), score, listOf("판별기 근거"), "VISION", brand)

    // 판별기가 그림에서 읽는 상표는 표기가 흔들린다
    @Test
    fun `표기가 흔들려도 같은 상표로 본다`() {
        assertTrue(BrandWhitelist.isTrusted("삼성"))
        assertTrue(BrandWhitelist.isTrusted("SAMSUNG"))
        assertTrue(BrandWhitelist.isTrusted(" samsung "))
        assertTrue(BrandWhitelist.isTrusted("(주)쿠팡"))
        assertTrue(BrandWhitelist.isTrusted("K B"))
    }

    @Test
    fun `모르는 상표는 통과시키지 않는다`() {
        assertFalse(BrandWhitelist.isTrusted(""))
        assertFalse(BrandWhitelist.isTrusted("건강나라"))
        assertFalse(BrandWhitelist.isTrusted("삼성헬스케어연구소"))   // 이름을 빌린 유사 상호
    }

    @Test
    fun `알려진 상표의 평범한 광고는 안전으로 내려간다`() {
        val relaxed = BrandWhitelist.relax(
            verdict(55, RiskCategory.UNVERIFIED_THIRD_PARTY, "쿠팡")
        )

        assertEquals(RiskLevel.LOW, relaxed.level)
        assertEquals(BrandWhitelist.TRUSTED_SCORE_CAP, relaxed.score)
        assertEquals(RiskCategory.TRUSTED_KNOWN_BRAND, relaxed.category)
        assertEquals("알려진 사업자(쿠팡) 광고입니다", relaxed.reasons.first())
    }

    // 여기가 핵심이다. 사칭은 정확히 그 이름을 쓴다 —
    // 상표를 알아봤다는 사실이 오히려 사칭의 근거일 수 있다
    @Test
    fun `사칭으로 본 판정은 상표가 있어도 낮추지 않는다`() {
        val kept = BrandWhitelist.relax(
            verdict(90, RiskCategory.PHISHING_OR_SCAM, "네이버")
        )

        assertEquals(RiskLevel.HIGH, kept.level)
        assertEquals(90, kept.score)
    }

    @Test
    fun `불법 도박 성인 악성앱도 낮추지 않는다`() {
        val categories = listOf(
            RiskCategory.ILLEGAL_GAMBLING,
            RiskCategory.ADULT_CONTENT,
            RiskCategory.MALWARE_OR_UNWANTED_APP,
            RiskCategory.ILLEGAL_STREAMING_OR_COPYRIGHT
        )
        for (category in categories) {
            val kept = BrandWhitelist.relax(verdict(85, category, "삼성"))
            assertEquals("$category 는 유지되어야 한다", 85, kept.score)
        }
    }

    // 알려진 회사도 과장 광고를 한다. 상표를 안다고 과장이 사라지지는 않는다
    @Test
    fun `과장 광고는 상표가 있어도 주의로 남는다`() {
        val kept = BrandWhitelist.relax(
            verdict(55, RiskCategory.EXAGGERATED_CLAIM, "쿠팡")
        )

        assertEquals(RiskLevel.MEDIUM, kept.level)
    }

    @Test
    fun `모르는 상표면 아무것도 바꾸지 않는다`() {
        val original = verdict(55, RiskCategory.UNVERIFIED_THIRD_PARTY, "건강나라")
        assertEquals(original, BrandWhitelist.relax(original))
    }

    @Test
    fun `이미 낮은 점수는 건드리지 않는다`() {
        val original = verdict(5, RiskCategory.TRUSTED_KNOWN_BRAND, "네이버")
        assertEquals(original, BrandWhitelist.relax(original))
    }
}
