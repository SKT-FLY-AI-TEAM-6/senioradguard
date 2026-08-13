package com.guradian.agent

/**
 * 시간당 판별 호출 상한 (토큰버킷).
 *
 * 호출 하나하나가 돈이고, 스크롤이 많은 앱에서는 후보가 끝없이 나온다. 상한에
 * 걸리면 호출을 건너뛰고 캐시 결과만 쓴다 — 기능이 조용히 저하될 뿐 멈추지는 않는다.
 *
 * @param capacity  버킷 최대 토큰 수 (= 시간당 호출 상한)
 * @param refillMs  버킷이 가득 차는 데 걸리는 시간
 * @param now       현재 시각. 테스트에서 시간을 직접 흘리려고 주입받는다
 */
class RateLimiter(
    private val capacity: Int = 60,
    private val refillMs: Long = 60 * 60 * 1000L,
    private val now: () -> Long = System::currentTimeMillis
) {

    private var tokens: Double = capacity.toDouble()
    private var lastRefill: Long = now()

    @Synchronized
    fun tryAcquire(): Boolean {
        refill()
        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }

    private fun refill() {
        val current = now()
        val elapsed = current - lastRefill
        if (elapsed <= 0) return

        val perMs = capacity.toDouble() / refillMs
        tokens = (tokens + elapsed * perMs).coerceAtMost(capacity.toDouble())
        lastRefill = current
    }
}
