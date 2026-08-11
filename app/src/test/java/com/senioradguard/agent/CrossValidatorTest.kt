package com.senioradguard.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossValidatorTest {

    // ── hasWeakSignal ────────────────────────────────────────

    @Test
    fun `광고성 id 토큰을 찾는다`() {
        assertTrue(CrossValidator.hasWeakSignal(listOf("com.app:id/ad_container")))
        assertTrue(CrossValidator.hasWeakSignal(listOf("banner_slot")))
        assertTrue(CrossValidator.hasWeakSignal(listOf("promo-area")))
        assertTrue(CrossValidator.hasWeakSignal(listOf("sponsored_label")))
    }

    // 단순 contains로 판정하면 여기서 전부 오탐한다
    @Test
    fun `ad가 부분 문자열로 들어간 평범한 id는 신호가 아니다`() {
        assertFalse(CrossValidator.hasWeakSignal(listOf("com.app:id/header")))
        assertFalse(CrossValidator.hasWeakSignal(listOf("download_button")))
        assertFalse(CrossValidator.hasWeakSignal(listOf("shadow_layer")))
        assertFalse(CrossValidator.hasWeakSignal(listOf("gradient_bg")))
        assertFalse(CrossValidator.hasWeakSignal(listOf("thread_list")))
    }

    @Test
    fun `id가 없으면 신호도 없다`() {
        assertFalse(CrossValidator.hasWeakSignal(emptyList()))
    }

    // ── adjust ───────────────────────────────────────────────

    @Test
    fun `광고 판정에 신호가 있으면 올린다`() {
        val result = CrossValidator.adjust(
            Verdict(isAd = true, confidence = 0.5f, reason = "x"),
            listOf("ad_slot")
        )
        assertEquals(0.65f, result.confidence, 0.0001f)
    }

    @Test
    fun `광고 아님 판정에 신호가 있으면 내린다`() {
        val result = CrossValidator.adjust(
            Verdict(isAd = false, confidence = 0.7f, reason = "x"),
            listOf("ad_slot")
        )
        assertEquals(0.55f, result.confidence, 0.0001f)
    }

    // 네이티브 앱 id는 대개 광고와 무관한 이름이라, 없다고 깎으면
    // 앱 안의 진짜 광고가 전부 임계값 아래로 밀려난다
    @Test
    fun `신호가 없으면 점수를 건드리지 않는다`() {
        val original = Verdict(isAd = true, confidence = 0.7f, reason = "x")
        assertEquals(original, CrossValidator.adjust(original, listOf("com.app:id/title")))
    }

    @Test
    fun `보정 후에도 0과 1 사이를 벗어나지 않는다`() {
        val high = CrossValidator.adjust(Verdict(true, 0.95f, "x"), listOf("ad"))
        assertEquals(1.0f, high.confidence, 0.0001f)

        val low = CrossValidator.adjust(Verdict(false, 0.05f, "x"), listOf("ad"))
        assertEquals(0.0f, low.confidence, 0.0001f)
    }

    // ── shouldMark ───────────────────────────────────────────

    @Test
    fun `임계값 이상인 광고만 표시한다`() {
        assertTrue(CrossValidator.shouldMark(Verdict(true, 0.6f, "x")))
        assertTrue(CrossValidator.shouldMark(Verdict(true, 0.9f, "x")))
        assertFalse(CrossValidator.shouldMark(Verdict(true, 0.59f, "x")))
    }

    // 확신에 찬 "광고 아님"을 표시하면 정반대 결과가 된다
    @Test
    fun `광고가 아니면 점수가 높아도 표시하지 않는다`() {
        assertFalse(CrossValidator.shouldMark(Verdict(false, 0.95f, "x")))
    }
}
