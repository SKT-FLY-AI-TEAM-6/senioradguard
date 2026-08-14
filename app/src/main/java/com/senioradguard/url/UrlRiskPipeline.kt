package com.senioradguard.url

import com.senioradguard.agent.RateLimiter
import com.senioradguard.detector.db.IllegalDomainDao
import com.senioradguard.detector.db.UrlRisk
import com.senioradguard.detector.db.UrlRiskDao

/**
 * Layer 4 파이프라인.
 *
 *   링크 → 신호 추출 → 불법 목록 조회 → 캐시 조회 → (미스만) 판별 → 저장 → 등급
 *
 * 순서가 곧 비용 순서다. 앞의 두 단계는 네트워크를 쓰지 않고 끝나며, 실제로
 * 대부분의 링크가 거기서 걸러진다. 판별기를 부르는 것은 "확인된 목록에도 없고
 * 최근에 본 적도 없는 도메인"뿐이다.
 */
class UrlRiskPipeline(
    private val illegalDao: IllegalDomainDao,
    private val riskDao: UrlRiskDao,
    private val classifier: UrlRiskClassifier,
    private val limiter: RateLimiter = RateLimiter(capacity = 30),
    private val now: () -> Long = System::currentTimeMillis
) {

    private companion object {
        /**
         * 판정 유효기간 14일. 광고 판정(30일)보다 짧다 — 도메인 평판은 더 빨리
         * 변한다. 정상이던 도메인이 팔려 악성으로 바뀌는 일이 실제로 흔하다.
         */
        const val TTL_MS = 14L * 24 * 60 * 60 * 1000
    }

    /**
     * @param verdict     최종 판정
     * @param fromCache   DB 캐시에서 그대로 나왔는가
     * @param blacklisted 확인된 불법 목록에 걸렸는가 (판별기를 부르지 않았다)
     * @param classified  판별기를 실제로 불렀는가. 상한·실패면 false
     */
    data class Result(
        val link: AdLink,
        val verdict: UrlRiskVerdict,
        val fromCache: Boolean = false,
        val blacklisted: Boolean = false,
        val classified: Boolean = false
    )

    /**
     * @param allowClassify false면 목록·캐시·규칙까지만 보고 판별기를 부르지 않는다.
     *        화면에 보이는 링크를 미리 훑는 경로처럼 자주 도는 곳에서 쓴다.
     */
    suspend fun evaluate(link: AdLink, allowClassify: Boolean = true): Result {
        val signals = UrlSignals.of(link)
        val host = link.cacheKey

        // 1. 이미 확인된 불법 도메인 — 추론할 것이 없다. 즉시 최고 등급으로 간다.
        val illegal = runCatching {
            illegalDao.findBySuffixes(UrlParser.hostSuffixes(host))
        }.getOrNull()
        if (illegal != null) {
            val verdict = UrlRiskVerdict(
                category = RiskCategory.parse(illegal.category),
                level = RiskLevel.of(illegal.score),
                score = illegal.score,
                // 목록의 근거를 먼저 보여준다. 규칙 신호는 뒤에 덧붙여 맥락을 채운다.
                reasons = (listOf(illegal.note) + RiskAggregator.heuristic(signals).reasons)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .take(UrlRiskVerdict.MAX_REASONS),
                source = UrlRiskVerdict.SOURCE_BLACKLIST
            )
            store(host, verdict)
            return Result(link, verdict, blacklisted = true)
        }

        // 2. 캐시
        val cached = runCatching { riskDao.find(host, now() - TTL_MS) }.getOrNull()
        if (cached != null) {
            return Result(link, cached.toVerdict(), fromCache = true)
        }

        // 3. 판별기를 못 부르는 경우 — 규칙만으로 답하고 **저장하지 않는다.**
        //    저장하면 TTL 동안 판별기가 그 도메인을 볼 기회가 사라진다.
        if (!allowClassify || !limiter.tryAcquire()) {
            return Result(link, RiskAggregator.heuristic(signals))
        }

        val classified = runCatching { classifier.classify(link, signals) }.getOrNull()
        val verdict = RiskAggregator.combine(signals, classified)

        // 판별에 실패하면(null) 규칙 판정을 캐시에 남기지 않는다 — 위와 같은 이유다.
        if (classified != null) store(host, verdict)

        return Result(link, verdict, classified = classified != null)
    }

    private suspend fun store(host: String, verdict: UrlRiskVerdict) {
        runCatching {
            riskDao.upsert(
                UrlRisk(
                    host = host,
                    category = verdict.category.name,
                    level = verdict.level.name,
                    score = verdict.score,
                    reasons = verdict.reasons.joinToString("\n"),
                    source = verdict.source,
                    updatedAt = now()
                )
            )
        }
    }

    private fun UrlRisk.toVerdict() = UrlRiskVerdict(
        category = RiskCategory.parse(category),
        // 등급을 저장된 문자열이 아니라 점수에서 다시 계산한다. 경계값을 조정했을 때
        // 옛 행이 옛 등급을 그대로 들고 살아남는 일을 막는다.
        level = RiskLevel.of(score),
        score = score,
        reasons = reasons.split("\n").filter { it.isNotBlank() },
        source = source
    )
}
