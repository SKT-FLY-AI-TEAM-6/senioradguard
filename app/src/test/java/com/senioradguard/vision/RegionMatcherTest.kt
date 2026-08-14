package com.senioradguard.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 판정이 붙은 사각형과 다음 스캔이 찾아낸 사각형은 좌표가 정확히 같지 않다.
 * 너무 엄격하면 색이 안 바뀌고, 너무 느슨하면 옆 카드의 색을 가져간다.
 */
class RegionMatcherTest {

    private fun iou(a: IntArray, b: IntArray) =
        RegionMatcher.iou(a[0], a[1], a[2], a[3], b[0], b[1], b[2], b[3])

    private fun same(a: IntArray, b: IntArray) =
        RegionMatcher.same(a[0], a[1], a[2], a[3], b[0], b[1], b[2], b[3])

    @Test
    fun `같은 사각형은 1이다`() {
        assertEquals(1f, iou(intArrayOf(0, 0, 100, 100), intArrayOf(0, 0, 100, 100)), 0.001f)
    }

    @Test
    fun `겹치지 않으면 0이다`() {
        assertEquals(0f, iou(intArrayOf(0, 0, 100, 100), intArrayOf(200, 0, 300, 100)), 0.001f)
        assertEquals(0f, iou(intArrayOf(0, 0, 100, 100), intArrayOf(100, 0, 200, 100)), 0.001f)
    }

    // 스크롤이 몇 픽셀 밀린 정도는 같은 영역으로 봐야 색이 유지된다
    @Test
    fun `조금 밀린 사각형은 같은 영역이다`() {
        assertTrue(same(intArrayOf(0, 0, 300, 200), intArrayOf(0, 8, 300, 208)))
        assertTrue(same(intArrayOf(0, 0, 300, 200), intArrayOf(4, 4, 304, 204)))
    }

    // 위아래로 붙어 있는 카드끼리 색이 옮겨가면 안 된다
    @Test
    fun `절반쯤 겹친 이웃 카드는 다른 영역이다`() {
        assertFalse(same(intArrayOf(0, 0, 300, 200), intArrayOf(0, 110, 300, 310)))
    }

    // 광고가 접히거나 펼쳐지면 크기가 크게 달라진다 — 그건 다른 상태다
    @Test
    fun `한쪽이 다른 쪽을 품어도 크기가 많이 다르면 다른 영역이다`() {
        assertFalse(same(intArrayOf(0, 0, 300, 400), intArrayOf(0, 0, 300, 100)))
        assertEquals(0.25f, iou(intArrayOf(0, 0, 300, 400), intArrayOf(0, 0, 300, 100)), 0.001f)
    }

    @Test
    fun `넓이가 0이면 0이다`() {
        assertEquals(0f, iou(intArrayOf(0, 0, 0, 100), intArrayOf(0, 0, 100, 100)), 0.001f)
        assertEquals(0f, iou(intArrayOf(0, 0, 100, 100), intArrayOf(50, 50, 50, 50)), 0.001f)
    }

    // 좌표가 뒤집힌 사각형이 들어와도 죽지 않아야 한다
    @Test
    fun `뒤집힌 사각형은 0이다`() {
        assertEquals(0f, iou(intArrayOf(100, 100, 0, 0), intArrayOf(0, 0, 100, 100)), 0.001f)
    }
}
