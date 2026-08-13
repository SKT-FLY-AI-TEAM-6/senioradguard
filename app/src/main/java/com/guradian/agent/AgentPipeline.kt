package com.guradian.agent

import android.graphics.Rect
import com.guradian.store.VerdictStore

/**
 * Layer 2 파이프라인.
 *
 *   후보 → 캐시 조회 → (미스만) 판별 → 교차검증 → 저장 → 표시할 영역 반환
 *
 * 캐시가 이 구조의 핵심이다. 화면 카드 대부분은 광고가 아니고, 부정 판정도
 * 저장하기 때문에 두 번째 방문부터는 호출이 거의 일어나지 않는다.
 *
 * 원본과 달라진 것은 저장소뿐이다 — Room DAO 대신 [VerdictStore] 인터페이스를
 * 주입받는다. task 4에서 Room 구현체로 갈아끼우면 여기는 손대지 않는다.
 */
class AgentPipeline(
    private val store: VerdictStore,
    private val classifier: AdClassifier,
    private val limiter: RateLimiter = RateLimiter()
) {

    private companion object {
        /** 한 번 실행당 새로 판별할 카드 수 상한. */
        const val MAX_MISSES_PER_RUN = 3
    }

    data class Result(
        val regions: List<Rect>,
        val cacheHits: Int,
        val classified: Int,
        val skippedByLimit: Int
    )

    /**
     * @param allowClassify false면 캐시만 조회하고 판별기를 부르지 않는다.
     *        화면이 바뀔 때마다 점선 위치를 다시 잡으려면 이 모드로 자주 돌려야
     *        하는데, 그때마다 판별기를 부르면 비용이 감당되지 않는다.
     *        **새 판별은 사용자가 [광고 찾기]를 눌렀을 때만 한다.**
     */
    suspend fun run(candidates: List<AdCandidate>, allowClassify: Boolean = true): Result {
        if (candidates.isEmpty()) return Result(emptyList(), 0, 0, 0)

        val regions = mutableListOf<Rect>()
        var hits = 0
        var classified = 0
        var skipped = 0
        var misses = 0

        for (candidate in candidates) {
            val key = CardText.cacheKey(candidate.sourceKey, candidate.texts)

            val cached = runCatching { store.get(key) }.getOrNull()
            if (cached != null) {
                hits++
                if (CrossValidator.shouldMark(cached)) regions.add(candidate.rect)
                continue
            }

            if (!allowClassify) continue

            if (misses >= MAX_MISSES_PER_RUN) {
                skipped++
                continue
            }
            misses++

            if (!limiter.tryAcquire()) {
                skipped++
                continue
            }

            val raw = runCatching {
                classifier.classify(CardText.forClassifier(candidate.texts), candidate.sourceKey)
            }.getOrNull()
            // 판별에 실패하면 캐시에 남기지 않는다. 실패를 저장하면 TTL 동안
            // 그 카드를 다시 볼 기회가 사라진다.
            if (raw == null) continue

            classified++
            val verdict = CrossValidator.adjust(raw, candidate.viewIds)

            runCatching { store.put(key, verdict) }

            if (CrossValidator.shouldMark(verdict)) regions.add(candidate.rect)
        }

        return Result(regions, hits, classified, skipped)
    }
}
