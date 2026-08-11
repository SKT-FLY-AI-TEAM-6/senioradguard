package com.senioradguard.detector

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 블랙리스트 도메인 집합의 프로세스 전역 메모리 캐시.
 *
 * AccessibilityService 인스턴스와 WorkManager 워커는 서로 다른 컴포넌트라
 * 인스턴스 필드로 캐시를 들고 있으면 워커의 갱신이 서비스에 반영되지 않는다.
 * 캐시를 전역으로 빼고 워커가 invalidate()를 호출하게 한다.
 */
object BlacklistCache {

    private val mutex = Mutex()

    @Volatile
    private var cached: Set<String>? = null

    suspend fun domains(loader: suspend () -> Set<String>): Set<String> {
        cached?.let { return it }
        return mutex.withLock {
            cached ?: loader().also { cached = it }
        }
    }

    /** 원격 갱신이 성공했을 때 호출한다. 다음 조회에서 DB를 다시 읽는다. */
    fun invalidate() {
        cached = null
    }
}
