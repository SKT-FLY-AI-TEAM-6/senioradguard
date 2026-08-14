package com.senioradguard.url

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.senioradguard.agent.RateLimiter
import com.senioradguard.detector.IllegalDomainRepository
import com.senioradguard.detector.db.AppDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Layer 4를 **진짜 Room 위에서** 돌린다.
 *
 * JVM 단위 테스트는 DAO를 인메모리 대역으로 바꿔치기하므로 SQL 자체는 한 줄도
 * 실행되지 않는다. `IN (:suffixes)`와 `ORDER BY LENGTH(domain) DESC`가 의도대로
 * 도는지, v2 스키마에 테이블 둘을 더한 v3가 실제로 만들어지는지는 여기서만 확인된다.
 */
@RunWith(AndroidJUnit4::class)
class UrlRiskInstrumentedTest {

    private lateinit var db: AppDatabase

    private val clock = 1_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
    }

    @After
    fun tearDown() = db.close()

    private fun pipeline(classifier: UrlRiskClassifier) = UrlRiskPipeline(
        illegalDao = db.illegalDomainDao(),
        riskDao = db.urlRiskDao(),
        classifier = classifier,
        limiter = RateLimiter(30, 3_600_000L) { clock }
    ) { clock }

    private class Fake(private val verdict: UrlRiskVerdict?) : UrlRiskClassifier {
        override val source = "FAKE"
        var calls = 0
        override suspend fun classify(link: AdLink, signals: List<Signal>): UrlRiskVerdict? {
            calls++
            return verdict
        }
    }

    private fun link(url: String) = UrlParser.parse(url, "yna.co.kr", "광고", isAdElement = true)!!

    private fun verdict(score: Int) = UrlRiskVerdict(
        RiskCategory.UNVERIFIED_THIRD_PARTY, RiskLevel.of(score), score, listOf("근거"), "FAKE"
    )

    @Test
    fun v3_스키마가_실제로_만들어진다() = runTest {
        assertEquals(0, db.illegalDomainDao().count())
        assertNull(db.urlRiskDao().find("none", 0L))
        // 기존 테이블도 함께 살아 있어야 한다
        assertNull(db.adVerdictDao().find("none", 0L))
        assertTrue(db.blacklistDao().getAllDomains().isEmpty())
    }

    @Test
    fun 씨앗_목록이_적재된다() = runTest {
        val repo = IllegalDomainRepository(db.illegalDomainDao())

        val added = repo.seedIfEmpty(clock)

        assertEquals(IllegalDomainRepository.SEED.size, added)
        assertEquals(IllegalDomainRepository.SEED.size, db.illegalDomainDao().count())
    }

    @Test
    fun 이미_값이_있으면_씨앗을_덮어쓰지_않는다() = runTest {
        val repo = IllegalDomainRepository(db.illegalDomainDao())
        repo.replaceFromRemote(
            listOf(
                IllegalDomainRepository.Entry(
                    "remote-only.com", RiskCategory.PHISHING_OR_SCAM, 90, "원격 목록"
                )
            ),
            clock
        )

        assertEquals(0, repo.seedIfEmpty(clock))
        assertEquals(1, db.illegalDomainDao().count())
    }

    // 목록에 등록 도메인만 있어도 하위 호스트가 걸려야 한다 — IN 절 조회의 핵심
    @Test
    fun 접미사_조회가_하위_호스트를_잡는다() = runTest {
        val dao = db.illegalDomainDao()
        IllegalDomainRepository(dao).replaceFromRemote(
            listOf(
                IllegalDomainRepository.Entry(
                    "evil.co.kr", RiskCategory.PHISHING_OR_SCAM, 95, "확인된 사기 도메인"
                )
            ),
            clock
        )

        val found = dao.findBySuffixes(UrlParser.hostSuffixes("cdn.ads.evil.co.kr"))

        assertNotNull(found)
        assertEquals("evil.co.kr", found!!.domain)
    }

    // 정확한 호스트 항목이 넓은 항목보다 먼저 걸려야 한다 (ORDER BY LENGTH DESC)
    @Test
    fun 더_좁은_항목이_우선한다() = runTest {
        val dao = db.illegalDomainDao()
        IllegalDomainRepository(dao).replaceFromRemote(
            listOf(
                IllegalDomainRepository.Entry("example.com", RiskCategory.UNVERIFIED_THIRD_PARTY, 45, "넓은 항목"),
                IllegalDomainRepository.Entry("bad.example.com", RiskCategory.PHISHING_OR_SCAM, 95, "좁은 항목")
            ),
            clock
        )

        val found = dao.findBySuffixes(UrlParser.hostSuffixes("bad.example.com"))

        assertEquals("bad.example.com", found!!.domain)
        assertEquals(95, found.score)
    }

    @Test
    fun 목록에_걸리면_판별기를_부르지_않는다() = runTest {
        IllegalDomainRepository(db.illegalDomainDao()).seedIfEmpty(clock)
        val classifier = Fake(verdict(0))

        val result = pipeline(classifier).evaluate(link("https://tvhot2.com/player/1"))

        assertTrue(result.blacklisted)
        assertEquals(RiskLevel.HIGH, result.verdict.level)
        assertEquals(0, classifier.calls)
    }

    @Test
    fun 판정이_캐시되어_두_번째는_판별기를_부르지_않는다() = runTest {
        val classifier = Fake(verdict(80))
        val pipeline = pipeline(classifier)

        val first = pipeline.evaluate(link("https://unknown-shop.com/a"))
        val second = pipeline.evaluate(link("https://unknown-shop.com/b"))

        assertEquals(1, classifier.calls)
        assertTrue(second.fromCache)
        assertEquals(first.verdict.score, second.verdict.score)
        assertEquals(RiskLevel.HIGH, second.verdict.level)
    }

    // 근거는 개행으로 이어 붙여 한 컬럼에 저장한다. 여러 줄이 한 줄로 뭉개지거나
    // 순서가 뒤바뀌면 경고창 문구가 그대로 망가진다.
    @Test
    fun 근거_여러_줄이_캐시를_왕복해도_보존된다() = runTest {
        val many = UrlRiskVerdict(
            RiskCategory.PHISHING_OR_SCAM, RiskLevel.HIGH, 90,
            listOf("첫 번째 근거", "두 번째 근거"), "FAKE"
        )
        val pipeline = pipeline(Fake(many))

        // 저장되는 것은 판별기 근거에 규칙 근거가 덧붙은 결과다(RiskAggregator.combine).
        // 그 결과가 무엇이든 캐시를 왕복해도 그대로여야 한다.
        val fresh = pipeline.evaluate(link("https://unknown-shop.com/a"))
        val cached = pipeline.evaluate(link("https://unknown-shop.com/a"))

        assertTrue(fresh.verdict.reasons.size >= 2)
        assertEquals("첫 번째 근거", fresh.verdict.reasons[0])
        assertEquals("두 번째 근거", fresh.verdict.reasons[1])
        assertTrue(cached.fromCache)
        assertEquals(fresh.verdict.reasons, cached.verdict.reasons)
    }

    @Test
    fun 최근_판정을_보호자용으로_읽을_수_있다() = runTest {
        val pipeline = pipeline(Fake(verdict(75)))
        pipeline.evaluate(link("https://a-shop.com/x"))
        pipeline.evaluate(link("https://b-shop.com/x"))

        val recent = db.urlRiskDao().recent(10)

        assertEquals(2, recent.size)
        assertTrue(recent.all { it.score == 75 })
    }
}
