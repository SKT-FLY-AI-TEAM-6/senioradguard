package com.senioradguard.vision

import com.senioradguard.agent.RateLimiter
import com.senioradguard.detector.db.IllegalDomain
import com.senioradguard.detector.db.IllegalDomainDao
import com.senioradguard.detector.db.RoiRisk
import com.senioradguard.detector.db.RoiRiskDao
import com.senioradguard.risk.RiskCategory
import com.senioradguard.risk.RiskLevel
import com.senioradguard.risk.RiskVerdict
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeIllegalDao(rows: List<IllegalDomain> = emptyList()) : IllegalDomainDao {
    val stored = rows.associateBy { it.domain }.toMutableMap()
    override suspend fun findBySuffixes(suffixes: List<String>) =
        suffixes.mapNotNull { stored[it] }.maxByOrNull { it.domain.length }
    override suspend fun insertAll(rows: List<IllegalDomain>) =
        rows.forEach { stored[it.domain] = it }
    override suspend fun count() = stored.size
    override suspend fun clearAll() = stored.clear()
}

private class FakeRoiDao : RoiRiskDao {
    val rows = mutableMapOf<String, RoiRisk>()

    override suspend fun find(key: String, notBefore: Long) =
        rows[key]?.takeIf { it.updatedAt >= notBefore }

    override suspend fun recentBySource(sourcePrefix: String, notBefore: Long, limit: Int) =
        rows.values
            .filter { it.key.startsWith(sourcePrefix) && it.updatedAt >= notBefore }
            .sortedByDescending { it.updatedAt }
            .take(limit)

    override suspend fun upsert(risk: RoiRisk) { rows[risk.key] = risk }
    override suspend fun recent(limit: Int) = rows.values.take(limit)
    override suspend fun deleteExpired(notBefore: Long) = 0
}

private class CountingVision(private val verdict: RiskVerdict?) : VisionRiskClassifier {
    override val source = "FAKE"
    var calls = 0
    var lastRequest: VisionRequest? = null

    override suspend fun classify(request: VisionRequest): RiskVerdict? {
        calls++
        lastRequest = request
        return verdict
    }
}

class VisionRiskPipelineTest {

    private var clock = 1_000_000L

    private fun request(
        kind: RoiKind = RoiKind.AD,
        text: String = "",
        url: String = "",
        image: String = "AAAA"
    ) = VisionRequest(kind, "yna.co.kr", text, url, image)

    private fun verdict(
        score: Int,
        category: RiskCategory = RiskCategory.UNVERIFIED_THIRD_PARTY,
        brand: String = ""
    ) = RiskVerdict(category, RiskLevel.of(score), score, listOf("판별기 근거"), "FAKE", brand)

    private fun pipeline(
        illegal: IllegalDomainDao = FakeIllegalDao(),
        roi: RoiRiskDao = FakeRoiDao(),
        classifier: VisionRiskClassifier = CountingVision(verdict(30)),
        limiter: RateLimiter = RateLimiter(40, 3_600_000L) { clock }
    ) = VisionRiskPipeline(illegal, roi, classifier, limiter) { clock }

    // ── 주소만으로 끝나는 길 (스크린샷 없이) ───────────────────

    // 검색 결과는 도메인을 글자로 보여준다. 그림을 볼 것도 없이 여기서 걸려야 한다
    @Test
    fun `화면에 보이는 주소가 불법 목록에 있으면 그림 없이 위험이다`() = runTest {
        val illegal = FakeIllegalDao(
            listOf(
                IllegalDomain(
                    "tvhot2.com", RiskCategory.ILLEGAL_STREAMING_OR_COPYRIGHT.name,
                    95, "확인된 불법 다시보기 사이트", clock
                )
            )
        )
        val verdict = pipeline(illegal).byShownUrl("tvhot2.com/player/1")

        assertNotNull(verdict)
        assertEquals(RiskLevel.HIGH, verdict!!.level)
        assertEquals(RiskVerdict.SOURCE_BLACKLIST, verdict.source)
    }

    @Test
    fun `목록에 없는 주소는 주소만으로 결론이 나지 않는다`() = runTest {
        assertNull(pipeline().byShownUrl("www.netflix.com/title/1"))
    }

    @Test
    fun `주소가 아니면 조용히 넘어간다`() = runTest {
        assertNull(pipeline().byShownUrl(""))
        assertNull(pipeline().byShownUrl("영화 다시보기"))
    }

    // ── 지문 캐시 ──────────────────────────────────────────────

    @Test
    fun `같은 지문은 두 번 판별하지 않는다`() = runTest {
        val classifier = CountingVision(verdict(80))
        val roi = FakeRoiDao()
        val pipeline = pipeline(roi = roi, classifier = classifier)

        pipeline.classify(request(), hash = 0x1234L)
        val cached = pipeline.cached("yna.co.kr", 0x1234L)

        assertEquals(1, classifier.calls)
        assertNotNull(cached)
        assertEquals(RiskLevel.HIGH, cached!!.level)
    }

    // 압축 잡음이나 애니메이션으로 몇 비트가 흔들린 같은 배너를 살린다
    @Test
    fun `몇 비트 다른 이웃 지문도 같은 판정을 쓴다`() = runTest {
        val pipeline = pipeline(classifier = CountingVision(verdict(75)))
        pipeline.classify(request(), hash = 0b1111_0000L)

        val neighbour = pipeline.cached("yna.co.kr", 0b1111_0011L)   // 2비트 차이

        assertNotNull(neighbour)
        assertEquals(75, neighbour!!.score)
    }

    @Test
    fun `많이 다른 지문은 캐시에 걸리지 않는다`() = runTest {
        val pipeline = pipeline(classifier = CountingVision(verdict(75)))
        pipeline.classify(request(), hash = 0L)

        assertNull(pipeline.cached("yna.co.kr", 0xFFFFL))            // 16비트 차이
    }

    // 같은 그림이라도 어디서 나왔는지에 따라 판단이 달라진다
    @Test
    fun `출처가 다르면 캐시를 공유하지 않는다`() = runTest {
        val pipeline = pipeline(classifier = CountingVision(verdict(75)))
        pipeline.classify(request(), hash = 0x99L)

        assertNull(pipeline.cached("com.google.android.youtube", 0x99L))
    }

    @Test
    fun `유효기간이 지난 판정은 쓰지 않는다`() = runTest {
        val pipeline = pipeline(classifier = CountingVision(verdict(75)))
        pipeline.classify(request(), hash = 0x77L)

        clock += 8L * 24 * 60 * 60 * 1000

        assertNull(pipeline.cached("yna.co.kr", 0x77L))
    }

    // 저장된 등급을 그대로 믿으면 경계값을 바꿔도 옛 행이 옛 등급을 들고 살아남는다
    @Test
    fun `캐시에서 읽을 때 등급을 점수로 다시 계산한다`() = runTest {
        val roi = FakeRoiDao()
        roi.upsert(
            RoiRisk(
                key = RoiHasher.key("yna.co.kr", 0x55L), hash = 0x55L,
                category = RiskCategory.ADULT_CONTENT.name, level = RiskLevel.LOW.name,
                score = 88, reasons = "옛 근거", source = "VISION", brand = "",
                updatedAt = clock
            )
        )

        assertEquals(RiskLevel.HIGH, pipeline(roi = roi).cached("yna.co.kr", 0x55L)!!.level)
    }

    // ── 판별 ───────────────────────────────────────────────────

    @Test
    fun `판별하면 저장하고 결과를 돌려준다`() = runTest {
        val roi = FakeRoiDao()
        val result = pipeline(roi = roi).classify(request(), hash = 0x11L)

        assertNotNull(result)
        assertTrue(result!!.classified)
        assertEquals(1, roi.rows.size)
    }

    @Test
    fun `상한에 걸리면 판별기를 부르지 않고 null을 돌려준다`() = runTest {
        val classifier = CountingVision(verdict(80))
        val result = pipeline(classifier = classifier, limiter = RateLimiter(0, 3_600_000L) { clock })
            .classify(request(), hash = 0x22L)

        assertNull(result)
        assertEquals(0, classifier.calls)
    }

    // 실패를 저장하면 TTL 동안 그 그림을 다시 볼 기회가 사라진다
    @Test
    fun `판별에 실패하면 저장하지 않는다`() = runTest {
        val roi = FakeRoiDao()
        val result = pipeline(roi = roi, classifier = CountingVision(null))
            .classify(request(), hash = 0x33L)

        assertNull(result)
        assertTrue(roi.rows.isEmpty())
    }

    @Test
    fun `판별기가 죽어도 파이프라인은 죽지 않는다`() = runTest {
        val exploding = object : VisionRiskClassifier {
            override val source = "BOOM"
            override suspend fun classify(request: VisionRequest): RiskVerdict =
                throw IllegalStateException("네트워크 끊김")
        }
        assertNull(pipeline(classifier = exploding).classify(request(), hash = 0x44L))
    }

    // 모델이 "삼성"이라고 답했다는 사실만으로 낮추면 사칭이 그 경로로 빠져나간다
    @Test
    fun `상표를 알아보면 위험도를 낮추되 목록으로 검증한다`() = runTest {
        val known = pipeline(classifier = CountingVision(verdict(55, brand = "쿠팡")))
            .classify(request(), hash = 0x61L)
        assertEquals(RiskLevel.LOW, known!!.verdict.level)

        val unknown = pipeline(classifier = CountingVision(verdict(55, brand = "건강나라")))
            .classify(request(), hash = 0x62L)
        assertEquals(RiskLevel.MEDIUM, unknown!!.verdict.level)

        val impersonation = pipeline(
            classifier = CountingVision(verdict(90, RiskCategory.PHISHING_OR_SCAM, "네이버"))
        ).classify(request(), hash = 0x63L)
        assertEquals(RiskLevel.HIGH, impersonation!!.verdict.level)
    }

    @Test
    fun `검색 결과라는 사실이 판별기까지 전달된다`() = runTest {
        val classifier = CountingVision(verdict(30))
        pipeline(classifier = classifier)
            .classify(request(kind = RoiKind.SEARCH_RESULT, text = "무료 다시보기"), hash = 0x71L)

        assertEquals(RoiKind.SEARCH_RESULT, classifier.lastRequest?.kind)
        assertEquals("무료 다시보기", classifier.lastRequest?.shownText)
    }
}
