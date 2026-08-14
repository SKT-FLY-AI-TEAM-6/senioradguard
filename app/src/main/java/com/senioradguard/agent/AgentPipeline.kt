package com.senioradguard.agent

import android.graphics.Rect
import com.senioradguard.detector.db.AdVerdict
import com.senioradguard.detector.db.AdVerdictDao

/**
 * Layer 2 파이프라인.
 *
 *   후보 → 캐시 조회 → (미스만) 판별 → 교차검증 → 저장 → 표시할 영역 반환
 *
 * 캐시가 이 구조의 핵심이다. 화면 카드 대부분은 광고가 아니고, 부정 판정도
 * 저장하기 때문에 두 번째 방문부터는 호출이 거의 일어나지 않는다.
 */
class AgentPipeline(
    private val verdictDao: AdVerdictDao,
    private val classifier: AdClassifier,
    private val limiter: RateLimiter = RateLimiter(),
    private val now: () -> Long = System::currentTimeMillis
) {

    private companion object {
        /** 유휴 1회당 새로 판별할 카드 수 상한. */
        const val MAX_MISSES_PER_RUN = 3

        /** 판정 유효기간. 광고는 교체되므로 오래된 판정은 버린다. */
        const val TTL_MS = 30L * 24 * 60 * 60 * 1000

        /**
         * 이번 세션에서 이미 판정이 끝난 카드를 기억하는 크기. 한 화면의 카드는
         * 5~15개이므로 몇 화면분을 담고도 남는다.
         */
        const val MEMO_CAPACITY = 256
    }

    /**
     * 델타 스캔 — 이미 판정이 끝난 카드는 다시 다루지 않는다.
     *
     * 캐시만 조회하는 패스는 화면이 바뀔 때마다 돈다. 그때마다 카드 수만큼 DB를
     * 읽으면, 스크롤하는 내내 같은 카드를 계속 다시 읽는 셈이다. 카드 지문
     * (출처 + 텍스트 해시 = CardText.cacheKey)으로 판정 결과를 메모리에 들고
     * 있다가 그대로 돌려준다.
     *
     * 지문이 텍스트 기반이라 같은 카드가 스크롤로 위치만 바뀌면 같은 키가 되고,
     * 새로 화면에 들어온 카드만 자연히 DB·판별기로 넘어간다.
     *
     * accessOrder=true인 LinkedHashMap이라 최근 쓴 것이 남는 LRU다.
     */
    private val memo = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) =
            size > MEMO_CAPACITY
    }

    /**
     * 판별 1건의 중간값. 로그는 서비스가 찍는다 — 여기서 android.util.Log를 부르면
     * JVM 단위 테스트에서 "not mocked"로 죽는다(실제로 그렇게 7건이 깨졌다).
     * 화면 텍스트는 담지 않는다. 마스킹을 거쳤어도 logcat에 쌓을 이유가 없고,
     * 어떤 카드였는지는 출처와 글자수로 충분히 추적된다.
     */
    data class Trace(
        val sourceKey: String,
        val chars: Int,
        val rawIsAd: Boolean,
        val rawConfidence: Float,
        val weakSignal: Boolean,
        val finalConfidence: Float,
        val marked: Boolean,
        val reason: String,
        /** 판별기 왕복에 걸린 시간(ms). 캐시 히트는 traces에 담기지 않는다. */
        val elapsedMs: Long
    )

    data class Result(
        val regions: List<Rect>,
        val cacheHits: Int,
        val classified: Int,
        val skippedByLimit: Int,
        /** 이미 판정한 카드라 DB 조회조차 생략한 수 (델타 스캔 효과) */
        val memoHits: Int = 0,
        val traces: List<Trace> = emptyList()
    )

    /**
     * @param allowClassify false면 캐시만 조회하고 판별기를 부르지 않는다.
     *        화면이 바뀔 때마다 테두리 위치를 다시 잡으려면 이 모드로 자주 돌려야
     *        하는데, 그때마다 판별기를 부르면 비용이 감당되지 않는다.
     *        새 판별은 스크롤이 멈췄을 때 한 번만 한다.
     */
    suspend fun run(candidates: List<AdCandidate>, allowClassify: Boolean = true): Result {
        if (candidates.isEmpty()) return Result(emptyList(), 0, 0, 0)

        val notBefore = now() - TTL_MS
        val regions = mutableListOf<Rect>()
        var hits = 0
        var classified = 0
        var skipped = 0
        var misses = 0
        var memoHits = 0
        val traces = mutableListOf<Trace>()

        for (candidate in candidates) {
            val key = CardText.cacheKey(candidate.sourceKey, candidate.texts)

            // 이번 세션에서 이미 판정한 카드 — DB도 건드리지 않는다.
            // 단 새 판별을 허용하는 패스(유휴 1회)에서는 기억을 건너뛰고 DB를 다시
            // 읽는다. 기억이 만료·삭제를 못 보고 굳는 것을 그때 바로잡는다.
            // (기억을 통째로 비우지는 않는다. 유휴 패스가 자주 도는 탓에 비우면
            //  스크롤 중 빠른 경로가 매번 사라져 델타 스캔이 무의미해진다.)
            val remembered = if (allowClassify) null else memo[key]
            if (remembered != null) {
                memoHits++
                if (remembered) regions.add(candidate.rect)
                continue
            }

            val cached = runCatching { verdictDao.find(key, notBefore) }.getOrNull()
            if (cached != null) {
                hits++
                val mark = cached.isAd && cached.confidence >= CrossValidator.THRESHOLD
                memo[key] = mark
                if (mark) regions.add(candidate.rect)
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

            // 판별에 실제로 걸린 시간을 잰다. 화면에 점선이 뜨기까지의 체감
            // 지연이 거의 이 값이라, 눈으로 볼 수 있어야 조정할 수 있다.
            val startedAt = now()
            val raw = runCatching {
                classifier.classify(CardText.forClassifier(candidate.texts), candidate.sourceKey)
            }.getOrNull()
            val elapsed = now() - startedAt
            // 판별에 실패하면 캐시에 남기지 않는다. 실패를 저장하면 TTL 동안
            // 그 카드를 다시 볼 기회가 사라진다.
            if (raw == null) continue

            classified++
            val verdict = CrossValidator.adjust(raw, candidate.viewIds)

            traces.add(
                Trace(
                    sourceKey = candidate.sourceKey,
                    chars = candidate.texts.sumOf { it.length },
                    rawIsAd = raw.isAd,
                    rawConfidence = raw.confidence,
                    weakSignal = CrossValidator.hasWeakSignal(candidate.viewIds),
                    finalConfidence = verdict.confidence,
                    marked = CrossValidator.shouldMark(verdict),
                    reason = verdict.reason,
                    elapsedMs = elapsed
                )
            )

            runCatching {
                verdictDao.upsert(
                    AdVerdict(
                        key = key,
                        isAd = verdict.isAd,
                        confidence = verdict.confidence,
                        source = classifier.source,
                        updatedAt = now()
                    )
                )
            }

            val mark = CrossValidator.shouldMark(verdict)
            memo[key] = mark
            if (mark) regions.add(candidate.rect)
        }

        return Result(regions, hits, classified, skipped, memoHits, traces)
    }
}
