package com.senioradguard.url

import com.senioradguard.agent.RateLimiter
import com.senioradguard.detector.db.IllegalDomain
import com.senioradguard.detector.db.IllegalDomainDao
import com.senioradguard.detector.db.UrlRisk
import com.senioradguard.detector.db.UrlRiskDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Room 없이 JVM에서 파이프라인 흐름을 검증하기 위한 인메모리 대역. */
private class FakeIllegalDao(rows: List<IllegalDomain> = emptyList()) : IllegalDomainDao {
    val stored = rows.associateBy { it.domain }.toMutableMap()
    var lookups = 0

    override suspend fun findBySuffixes(suffixes: List<String>): IllegalDomain? {
        lookups++
        // 좁은 것(정확한 호스트)이 먼저 걸리도록 — 실제 쿼리의 ORDER BY와 같은 규칙
        return suffixes.mapNotNull { stored[it] }.maxByOrNull { it.domain.length }
    }

    override suspend fun insertAll(rows: List<IllegalDomain>) {
        rows.forEach { stored[it.domain] = it }
    }

    override suspend fun count() = stored.size

    override suspend fun clearAll() = stored.clear()
}

private class FakeRiskDao : UrlRiskDao {
    val rows = mutableMapOf<String, UrlRisk>()
    var finds = 0

    override suspend fun find(host: String, notBefore: Long): UrlRisk? {
        finds++
        return rows[host]?.takeIf { it.updatedAt >= notBefore }
    }

    override suspend fun upsert(risk: UrlRisk) {
        rows[risk.host] = risk
    }

    override suspend fun recent(limit: Int) =
        rows.values.sortedByDescending { it.updatedAt }.take(limit)

    override suspend fun deleteExpired(notBefore: Long): Int {
        val expired = rows.filterValues { it.updatedAt < notBefore }.keys
        expired.forEach { rows.remove(it) }
        return expired.size
    }
}

private class CountingClassifier(private val verdict: UrlRiskVerdict?) : UrlRiskClassifier {
    override val source = "FAKE"
    var calls = 0
    var lastSignals: List<Signal> = emptyList()

    override suspend fun classify(link: AdLink, signals: List<Signal>): UrlRiskVerdict? {
        calls++
        lastSignals = signals
        return verdict
    }
}

class UrlRiskPipelineTest {

    private var clock = 1_000_000L

    private fun link(url: String, anchor: String = "") =
        UrlParser.parse(url, "yna.co.kr", anchor, isAdElement = true)!!

    private fun verdict(score: Int, source: String = "FAKE") =
        UrlRiskVerdict(
            RiskCategory.UNVERIFIED_THIRD_PARTY, RiskLevel.of(score), score,
            listOf("판별기 근거"), source
        )

    private fun pipeline(
        illegal: IllegalDomainDao = FakeIllegalDao(),
        risk: UrlRiskDao = FakeRiskDao(),
        classifier: UrlRiskClassifier = CountingClassifier(verdict(30)),
        limiter: RateLimiter = RateLimiter(30, 3_600_000L) { clock }
    ) = UrlRiskPipeline(illegal, risk, classifier, limiter) { clock }

    private fun illegalRow(domain: String, score: Int = 95) = IllegalDomain(
        domain = domain,
        category = RiskCategory.ILLEGAL_STREAMING_OR_COPYRIGHT.name,
        score = score,
        note = "확인된 불법 다시보기 사이트",
        updatedAt = clock
    )

    // ── 1단계: 확인된 불법 목록 ────────────────────────────────

    @Test
    fun `목록에 있으면 판별기를 부르지 않고 즉시 위험이다`() = runTest {
        val classifier = CountingClassifier(verdict(10))
        val result = pipeline(FakeIllegalDao(listOf(illegalRow("tvhot2.com"))), classifier = classifier)
            .evaluate(link("https://tvhot2.com/player/1"))

        assertTrue(result.blacklisted)
        assertEquals(RiskLevel.HIGH, result.verdict.level)
        assertEquals(95, result.verdict.score)
        assertEquals(UrlRiskVerdict.SOURCE_BLACKLIST, result.verdict.source)
        assertEquals(0, classifier.calls)
    }

    // 목록에 등록 도메인만 있어도 하위 호스트가 걸려야 한다
    @Test
    fun `하위 호스트도 목록에 걸린다`() = runTest {
        val result = pipeline(FakeIllegalDao(listOf(illegalRow("evil.co.kr"))))
            .evaluate(link("https://cdn.ads.evil.co.kr/go"))

        assertTrue(result.blacklisted)
    }

    @Test
    fun `목록의 근거가 경고 문구 맨 앞에 온다`() = runTest {
        val result = pipeline(FakeIllegalDao(listOf(illegalRow("tvhot2.com"))))
            .evaluate(link("https://tvhot2.com/player/1"))

        assertEquals("확인된 불법 다시보기 사이트", result.verdict.reasons.first())
    }

    // 목록 점수가 낮으면 등급도 낮아야 한다 (4shared 같은 '주의' 등급 항목)
    @Test
    fun `목록 항목의 점수를 그대로 따른다`() = runTest {
        val result = pipeline(FakeIllegalDao(listOf(illegalRow("4shared.com", score = 50))))
            .evaluate(link("https://4shared.com/file/1"))

        assertEquals(RiskLevel.MEDIUM, result.verdict.level)
    }

    // ── 2단계: 캐시 ────────────────────────────────────────────

    @Test
    fun `두 번째 호출은 캐시를 써 판별기를 부르지 않는다`() = runTest {
        val classifier = CountingClassifier(verdict(80))
        val risk = FakeRiskDao()
        val pipeline = pipeline(risk = risk, classifier = classifier)

        pipeline.evaluate(link("https://unknown-shop.com/a"))
        val second = pipeline.evaluate(link("https://unknown-shop.com/b"))

        assertEquals(1, classifier.calls)
        assertTrue(second.fromCache)
        assertEquals(RiskLevel.HIGH, second.verdict.level)
    }

    // 광고 서버는 클릭마다 경로가 달라진다. 경로까지 키에 넣으면 캐시가 한 번도 안 맞는다
    @Test
    fun `경로가 달라도 같은 호스트면 같은 캐시다`() = runTest {
        val risk = FakeRiskDao()
        pipeline(risk = risk).evaluate(link("https://ad.example.com/click?id=1"))

        assertEquals(setOf("ad.example.com"), risk.rows.keys)
    }

    @Test
    fun `유효기간이 지난 판정은 다시 판별한다`() = runTest {
        val classifier = CountingClassifier(verdict(30))
        val pipeline = pipeline(classifier = classifier)

        pipeline.evaluate(link("https://unknown-shop.com/a"))
        clock += 15L * 24 * 60 * 60 * 1000
        pipeline.evaluate(link("https://unknown-shop.com/a"))

        assertEquals(2, classifier.calls)
    }

    // 저장된 등급을 그대로 믿으면, 경계값을 바꿔도 옛 행이 옛 등급을 들고 살아남는다
    @Test
    fun `캐시에서 읽을 때 등급을 점수로 다시 계산한다`() = runTest {
        val risk = FakeRiskDao()
        risk.upsert(
            UrlRisk(
                host = "stale.com", category = RiskCategory.PHISHING_OR_SCAM.name,
                level = RiskLevel.LOW.name, score = 90, reasons = "옛 근거",
                source = UrlRiskVerdict.SOURCE_LLM, updatedAt = clock
            )
        )
        val result = pipeline(risk = risk).evaluate(link("https://stale.com/x"))

        assertEquals(RiskLevel.HIGH, result.verdict.level)
    }

    // ── 3단계: 판별 ────────────────────────────────────────────

    @Test
    fun `판별기에 규칙 신호를 함께 넘긴다`() = runTest {
        val classifier = CountingClassifier(verdict(30))
        pipeline(classifier = classifier).evaluate(link("https://first-bet.top/join"))

        assertTrue(classifier.lastSignals.any { it.axis == RiskAxis.ILLEGAL_CONTENT })
    }

    @Test
    fun `사전 훑기 경로는 판별기를 부르지 않는다`() = runTest {
        val classifier = CountingClassifier(verdict(30))
        val result = pipeline(classifier = classifier)
            .evaluate(link("https://unknown-shop.com/a"), allowClassify = false)

        assertEquals(0, classifier.calls)
        assertFalse(result.classified)
        assertEquals(UrlRiskVerdict.SOURCE_HEURISTIC, result.verdict.source)
    }

    @Test
    fun `시간당 상한에 걸리면 규칙으로만 답한다`() = runTest {
        val classifier = CountingClassifier(verdict(30))
        val result = pipeline(classifier = classifier, limiter = RateLimiter(0, 3_600_000L) { clock })
            .evaluate(link("https://tvhot2.com/player/1"))

        assertEquals(0, classifier.calls)
        assertEquals(RiskLevel.HIGH, result.verdict.level)   // 규칙만으로도 이건 잡힌다
    }

    // 저장하면 TTL 14일 동안 판별기가 그 도메인을 볼 기회가 사라진다
    @Test
    fun `판별하지 못한 판정은 캐시에 남기지 않는다`() = runTest {
        val risk = FakeRiskDao()
        pipeline(risk = risk, classifier = CountingClassifier(null))
            .evaluate(link("https://unknown-shop.com/a"))

        assertTrue(risk.rows.isEmpty())
    }

    @Test
    fun `규칙만으로 답한 판정도 캐시에 남기지 않는다`() = runTest {
        val risk = FakeRiskDao()
        pipeline(risk = risk).evaluate(link("https://unknown-shop.com/a"), allowClassify = false)

        assertTrue(risk.rows.isEmpty())
    }

    @Test
    fun `판별기가 죽어도 규칙 판정으로 이어간다`() = runTest {
        val exploding = object : UrlRiskClassifier {
            override val source = "BOOM"
            override suspend fun classify(link: AdLink, signals: List<Signal>): UrlRiskVerdict =
                throw IllegalStateException("네트워크 끊김")
        }
        val result = pipeline(classifier = exploding).evaluate(link("https://tvhot2.com/player/1"))

        assertEquals(RiskLevel.HIGH, result.verdict.level)
        assertEquals(UrlRiskVerdict.SOURCE_HEURISTIC, result.verdict.source)
    }

    // ── 순서 ───────────────────────────────────────────────────

    // 목록 조회는 네트워크를 쓰지 않고 끝난다. 캐시보다 먼저 봐야 확인된 위험을
    // 오래된 캐시가 덮어쓰지 않는다
    @Test
    fun `목록을 캐시보다 먼저 본다`() = runTest {
        val risk = FakeRiskDao()
        risk.upsert(
            UrlRisk(
                host = "tvhot2.com", category = RiskCategory.UNKNOWN.name,
                level = RiskLevel.LOW.name, score = 0, reasons = "옛 판정",
                source = UrlRiskVerdict.SOURCE_LLM, updatedAt = clock
            )
        )
        val result = pipeline(FakeIllegalDao(listOf(illegalRow("tvhot2.com"))), risk)
            .evaluate(link("https://tvhot2.com/player/1"))

        assertEquals(RiskLevel.HIGH, result.verdict.level)
        assertEquals(0, risk.finds)
    }

    @Test
    fun `주소를 읽을 수 없으면 파이프라인까지 오지 않는다`() {
        assertNull(UrlParser.parse("광고를 눌러보세요"))
    }
}
