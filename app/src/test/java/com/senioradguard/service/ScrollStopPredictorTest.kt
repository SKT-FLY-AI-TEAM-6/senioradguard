package com.senioradguard.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollStopPredictorTest {

    private var clock = 1_000L
    private fun predictor() = ScrollStopPredictor { clock }

    /** dt ms 간격으로 delta px씩 스크롤 이벤트를 흘려보낸다. */
    private fun ScrollStopPredictor.feed(times: Int, delta: Int?, dt: Long) {
        repeat(times) {
            clock += dt
            record(delta)
        }
    }

    @Test
    fun `이벤트가 없으면 스크롤 중이 아니고 지연도 없다`() {
        val p = predictor()
        assertFalse(p.isScrolling())
        assertEquals(0L, p.predictStopDelayMs())
    }

    // 첫 이벤트는 직전 시각이 없어 속도를 낼 근거가 없다. 그래도 "방금 스크롤이
    // 있었다"는 사실은 확실하므로 스캔은 미뤄야 한다 — 속도를 모른다고 곧바로
    // 훑으면 스크롤 도중에 스캔이 나간다.
    @Test
    fun `이벤트 하나로는 속도를 못 내지만 스캔은 미룬다`() {
        val p = predictor()
        clock += 16
        p.record(100)
        assertTrue(p.isScrolling())
        assertTrue(p.predictStopDelayMs() > 0)
    }

    @Test
    fun `빠른 플링은 스크롤 중으로 보고 정지까지 기다린다`() {
        val p = predictor()
        p.feed(times = 5, delta = 160, dt = 16)   // 10px/ms
        assertTrue(p.isScrolling())
        assertTrue("빠를수록 오래 기다려야 한다", p.predictStopDelayMs() > 200)
    }

    @Test
    fun `느린 스크롤은 짧게 기다린다`() {
        val fast = predictor().also { it.feed(5, 160, 16) }   // 10px/ms
        val slow = predictor().also { it.feed(5, 16, 16) }    // 1px/ms
        assertTrue(slow.predictStopDelayMs() < fast.predictStopDelayMs())
    }

    // 손가락을 떼지 않고 멈춰 있으면 델타가 0으로 들어온다
    @Test
    fun `이동량이 0이면 멈춘 것으로 본다`() {
        val p = predictor()
        p.feed(5, 160, 16)
        p.feed(5, 0, 16)
        assertFalse(p.isScrolling())
        assertEquals(0L, p.predictStopDelayMs())
    }

    // 스크롤이 끝나고 한참 뒤 온 이벤트를 이어붙이면 엉뚱한 속도가 나온다
    @Test
    fun `한참 만에 온 이벤트는 속도를 초기화한다`() {
        val fling = predictor().also { it.feed(5, 160, 16) }
        val longFlingDelay = fling.predictStopDelayMs()

        val p = predictor()
        p.feed(5, 160, 16)
        clock += 5_000
        p.record(160)

        // 직전 플링 속도를 이어받지 않는다 (이어받았다면 그만큼 길게 기다릴 것)
        assertTrue(p.predictStopDelayMs() < longFlingDelay)
    }

    @Test
    fun `예측 시간은 기다리는 사이 줄어든다`() {
        val p = predictor()
        p.feed(5, 160, 16)
        val first = p.predictStopDelayMs()
        clock += 100
        assertTrue(p.predictStopDelayMs() < first)
    }

    @Test
    fun `예측 시간은 음수가 되지 않는다`() {
        val p = predictor()
        p.feed(5, 160, 16)
        clock += 10_000
        assertEquals(0L, p.predictStopDelayMs())
    }

    // 크롬처럼 이동량을 안 주는 앱: 0(정지)으로 뭉뚱그리면 스크롤 도중에 스캔이 나간다
    @Test
    fun `이동량을 모르면 스크롤 중으로 보고 정착 시간을 기다린다`() {
        val p = predictor()
        p.feed(times = 5, delta = null, dt = 16)
        assertTrue(p.isScrolling())
        assertTrue(p.predictStopDelayMs() > 0)
    }

    @Test
    fun `이동량을 몰라도 스크롤이 끊기면 멈춘 것으로 본다`() {
        val p = predictor()
        p.feed(times = 5, delta = null, dt = 16)
        clock += 300
        assertFalse(p.isScrolling())
        assertEquals(0L, p.predictStopDelayMs())
    }

    @Test
    fun `reset하면 처음 상태로 돌아간다`() {
        val p = predictor()
        p.feed(5, 160, 16)
        p.reset()
        assertFalse(p.isScrolling())
        assertEquals(0L, p.predictStopDelayMs())
    }
}
