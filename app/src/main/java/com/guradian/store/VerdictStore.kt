package com.guradian.store

import com.guradian.agent.Verdict

/**
 * Layer 2 판정 캐시. — 이음매 (task 4가 여기에 붙는다)
 *
 * 캐시가 Layer 2의 비용 구조를 지탱한다. 화면 카드 대부분은 광고가 아니고
 * **부정 판정도 저장**하기 때문에, 두 번째 방문부터는 판별기 호출이 거의 일어나지
 * 않는다. 절감의 대부분이 여기서 나온다.
 *
 * 지금은 [InMemoryVerdictStore], task 4에서 RoomVerdictStore로 갈아끼운다.
 * 인메모리로 바꿔도 비용 제어는 그대로다 — 프로세스가 죽으면 캐시가 날아가지만
 * 접근성 서비스는 상시 실행이라 실사용에서는 큰 차이가 없다.
 */
interface VerdictStore {
    suspend fun get(key: String): Verdict?
    suspend fun put(key: String, verdict: Verdict)
}

/**
 * 지금의 구현 — LRU 500칸, TTL 30일.
 *
 * 500칸인 이유: 한 화면의 후보가 5~15개이고 유휴 1회에 3건까지만 새로 판별하므로,
 * 500칸이면 며칠치 방문을 덮는다. 넘치면 가장 오래 안 쓴 것부터 버린다.
 *
 * TTL 30일인 이유: 광고는 교체된다. 오래된 판정을 계속 믿으면 이미 다른 내용으로
 * 바뀐 자리에 테두리를 그린다.
 *
 * @param now 현재 시각. 테스트에서 시간을 직접 흘리려고 주입받는다
 */
class InMemoryVerdictStore(
    private val maxEntries: Int = 500,
    private val ttlMillis: Long = 30L * 24 * 60 * 60 * 1000,
    private val now: () -> Long = System::currentTimeMillis
) : VerdictStore {

    private class Entry(val verdict: Verdict, val storedAt: Long)

    /**
     * accessOrder=true인 LinkedHashMap이 곧 LRU다. 접근할 때마다 맨 뒤로 옮겨지고
     * [removeEldestEntry]가 넘치는 만큼 앞에서 버린다.
     */
    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > maxEntries
    }

    // LinkedHashMap은 스레드 안전하지 않고, 캐시 전용 경로(스캔 코루틴)와 버튼 경로가
    // 동시에 돌 수 있다. suspend 함수에는 @Synchronized를 못 붙이므로 블록으로 감싼다.
    // 임계 구역이 맵 조회 한 번이라 코루틴을 실제로 막는 시간은 없다시피 하다.
    override suspend fun get(key: String): Verdict? = synchronized(entries) {
        val entry = entries[key] ?: return@synchronized null
        // 만료된 판정은 없는 것으로 본다. 여기서 지워야 다음 조회가 헛돌지 않는다.
        if (now() - entry.storedAt >= ttlMillis) {
            entries.remove(key)
            return@synchronized null
        }
        entry.verdict
    }

    override suspend fun put(key: String, verdict: Verdict) = synchronized(entries) {
        entries[key] = Entry(verdict, now())
        Unit
    }

    /** 테스트·진단용. 지금 들고 있는 판정 수. */
    fun size(): Int = synchronized(entries) { entries.size }
}
