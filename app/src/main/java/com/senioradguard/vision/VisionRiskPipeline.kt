package com.senioradguard.vision

import com.senioradguard.agent.RateLimiter
import com.senioradguard.detector.db.IllegalDomainDao
import com.senioradguard.detector.db.RoiRisk
import com.senioradguard.detector.db.RoiRiskDao
import com.senioradguard.risk.RiskCategory
import com.senioradguard.risk.RiskLevel
import com.senioradguard.risk.RiskVerdict
import com.senioradguard.url.UrlParser

/**
 * Layer 5 파이프라인.
 *
 *   ROI → (주소가 보이면) 불법 목록 → 지문 캐시 → 이웃 지문 → 판별 → 상표 검증 → 저장
 *
 * 앞 세 단계는 네트워크도 스크린샷도 쓰지 않는다. 스크린샷을 찍고 판별기를 부르는
 * 것은 "처음 보는 그림"일 때뿐이다. 이 순서가 없으면 스크롤 한 번에 판별 호출이
 * 수십 건 나간다 — 같은 배너가 화면을 계속 오르내리기 때문이다.
 */
class VisionRiskPipeline(
    private val illegalDao: IllegalDomainDao,
    private val riskDao: RoiRiskDao,
    private val classifier: VisionRiskClassifier,
    private val limiter: RateLimiter = RateLimiter(capacity = 40),
    private val now: () -> Long = System::currentTimeMillis
) {

    private companion object {
        /**
         * 판정 유효기간 7일. Layer 4(14일)보다 더 짧다 — 광고 크리에이티브는
         * 도메인보다 훨씬 빨리 교체된다. 같은 자리에 다음 주면 다른 그림이 있다.
         */
        const val TTL_MS = 7L * 24 * 60 * 60 * 1000

        /** 이웃 지문을 찾을 때 훑어볼 최근 판정 수. */
        const val NEIGHBOR_SCAN = 40
    }

    /**
     * @param verdict     최종 판정
     * @param fromCache   지문 캐시(정확 일치 또는 이웃)에서 나왔는가
     * @param blacklisted 화면에 보이던 주소가 확인된 불법 목록에 걸렸는가
     * @param classified  판별기를 실제로 불렀는가
     */
    data class Result(
        val verdict: RiskVerdict,
        val hash: Long,
        val fromCache: Boolean = false,
        val blacklisted: Boolean = false,
        val classified: Boolean = false
    )

    /**
     * 이미지를 아직 만들지 않은 채로 캐시만 조회한다.
     *
     * 스크린샷은 호출 간격 제한이 있고 비트맵 복사도 공짜가 아니다. 캐시에 있으면
     * 찍을 이유가 없으므로, 호출부가 **지문을 이미 알고 있을 때** 이 경로로 먼저 묻는다.
     */
    suspend fun cached(sourceKey: String, hash: Long): RiskVerdict? {
        val notBefore = now() - TTL_MS
        val key = RoiHasher.key(sourceKey, hash)

        runCatching { riskDao.find(key, notBefore) }.getOrNull()?.let { return it.toVerdict() }

        // 압축 잡음이나 애니메이션으로 몇 비트가 흔들린 같은 배너를 살린다.
        val neighbors = runCatching {
            riskDao.recentBySource("$sourceKey|", notBefore, NEIGHBOR_SCAN)
        }.getOrNull().orEmpty()

        return neighbors.firstOrNull { RoiHasher.similar(it.hash, hash) }?.toVerdict()
    }

    /**
     * 주소가 화면에 보였다면 그것만으로 결론이 날 수 있다. 스크린샷보다 먼저 본다.
     *
     * 검색 결과에서 특히 잘 듣는다 — 구글은 결과마다 도메인을 글자로 보여주므로,
     * 누누티비류는 그림을 볼 것도 없이 여기서 걸린다.
     */
    suspend fun byShownUrl(shownUrl: String): RiskVerdict? {
        val components = UrlParser.components(shownUrl) ?: return null
        val row = runCatching {
            illegalDao.findBySuffixes(UrlParser.hostSuffixes(components.domain))
        }.getOrNull() ?: return null

        return RiskVerdict(
            category = RiskCategory.parse(row.category),
            level = RiskLevel.of(row.score),
            score = row.score,
            reasons = listOf(row.note).filter { it.isNotBlank() },
            source = RiskVerdict.SOURCE_BLACKLIST
        )
    }

    /**
     * 이미지까지 만들어 판별한다. 위 두 단계가 모두 비었을 때만 부른다.
     *
     * @return 판별기를 못 불렀으면(상한·실패) null. 호출부는 테두리 색을 바꾸지 않고
     *         다음 기회를 기다린다 — 모르면 아무 색도 칠하지 않는 쪽이 안전하다
     */
    suspend fun classify(request: VisionRequest, hash: Long): Result? {
        if (!limiter.tryAcquire()) return null

        val raw = runCatching { classifier.classify(request) }.getOrNull() ?: return null

        // 모델이 상표를 알아봤다면 우리 목록으로 한 번 더 검증한다. 모델이 "삼성"이라
        // 답했다는 사실만으로 위험도를 낮추면, 사칭 광고가 바로 그 경로로 빠져나간다.
        val verdict = BrandWhitelist.relax(raw)

        store(request.sourceKey, hash, verdict)
        return Result(verdict, hash, classified = true)
    }

    private suspend fun store(sourceKey: String, hash: Long, verdict: RiskVerdict) {
        runCatching {
            riskDao.upsert(
                RoiRisk(
                    key = RoiHasher.key(sourceKey, hash),
                    hash = hash,
                    category = verdict.category.name,
                    level = verdict.level.name,
                    score = verdict.score,
                    reasons = verdict.reasons.joinToString("\n"),
                    source = verdict.source,
                    brand = verdict.brand,
                    updatedAt = now()
                )
            )
        }
    }

    private fun RoiRisk.toVerdict() = RiskVerdict(
        category = RiskCategory.parse(category),
        // 저장된 등급이 아니라 점수에서 다시 계산한다. 경계값을 조정했을 때 옛 행이
        // 옛 등급을 그대로 들고 살아남는 일을 막는다.
        level = RiskLevel.of(score),
        score = score,
        reasons = reasons.split("\n").filter { it.isNotBlank() },
        source = source,
        brand = brand
    )
}
