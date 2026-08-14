package com.guradian.serp

/**
 * 이 패키지가 쓰는 로그 태그.
 *
 * 값은 다른 레이어와 같게 두어 `adb logcat -s GurADian:*` 한 줄로 전부 보이게 하되,
 * **상수 자체는 여기서 정의한다.** 서비스의 companion을 import하면 이 패키지가
 * 그 서비스 없이는 컴파일되지 않는다 — 병합·이식할 때 그게 정확히 걸림돌이 된다.
 */
internal const val SERP_TAG = "GurADian"

/**
 * 판별기 호출 상한 (토큰버킷).
 *
 * ## 왜 `agent.RateLimiter`를 쓰지 않고 따로 두는가
 * 하는 일은 같다. 그럼에도 나눠 둔 이유는 **이 패키지를 통째로 들어내 다른 저장소에
 * 옮길 수 있게** 하기 위해서다. 검색 결과 위험도는 다른 팀원이 만드는 광고 감지와
 * 별개의 task라, 서로의 코드를 참조하기 시작하면 병합할 때 한쪽만 가져올 수 없다.
 *
 * 30줄을 중복해서 얻는 것이 "이 패키지는 자기 밖의 무엇도 필요로 하지 않는다"는
 * 성질이라면 남는 거래다. 둘을 합칠 일이 생기면 그때 공용 모듈로 올리면 된다.
 *
 * @param capacity 버킷 최대 토큰 수 (= 기간당 호출 상한)
 * @param refillMs 버킷이 가득 차는 데 걸리는 시간
 * @param now      현재 시각. 테스트에서 시간을 직접 흘리려고 주입받는다
 */
class SerpCallBudget(
    private val capacity: Int = 30,
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
