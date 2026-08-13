package com.guradian.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {

    private var clock = 0L
    private fun limiter(capacity: Int = 3, refillMs: Long = 3_000L) =
        RateLimiter(capacity, refillMs) { clock }

    @Test
    fun `용량만큼은 통과시킨다`() {
        val limiter = limiter()
        repeat(3) { assertTrue(limiter.tryAcquire()) }
    }

    @Test
    fun `용량을 넘으면 막는다`() {
        val limiter = limiter()
        repeat(3) { limiter.tryAcquire() }
        assertFalse(limiter.tryAcquire())
    }

    @Test
    fun `시간이 지나면 다시 채워진다`() {
        val limiter = limiter()
        repeat(3) { limiter.tryAcquire() }
        assertFalse(limiter.tryAcquire())

        clock += 1_000L   // 3초에 3개 → 1초에 1개
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
    }

    @Test
    fun `오래 쉬어도 용량을 넘겨 쌓이지는 않는다`() {
        val limiter = limiter()
        clock += 10 * 60 * 60 * 1000L
        repeat(3) { assertTrue(limiter.tryAcquire()) }
        assertFalse(limiter.tryAcquire())
    }
}
