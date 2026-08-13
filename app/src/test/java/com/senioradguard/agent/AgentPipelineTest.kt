package com.senioradguard.agent

import android.graphics.Rect
import com.senioradguard.detector.db.AdVerdict
import com.senioradguard.detector.db.AdVerdictDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AdVerdictDao의 인메모리 대역. Room 없이 JVM에서 파이프라인 흐름을 검증한다.
 * (Room 스키마 자체는 AdVerdictDaoTest 계측 테스트가 따로 덮는다)
 */
private class FakeVerdictDao : AdVerdictDao {
    val rows = mutableMapOf<String, AdVerdict>()
    var findCount = 0

    override suspend fun find(key: String, notBefore: Long): AdVerdict? {
        findCount++
        return rows[key]?.takeIf { it.updatedAt >= notBefore }
    }

    override suspend fun upsert(verdict: AdVerdict) {
        rows[verdict.key] = verdict
    }

    override suspend fun deleteExpired(notBefore: Long): Int {
        val expired = rows.filterValues { it.updatedAt < notBefore }.keys
        expired.forEach { rows.remove(it) }
        return expired.size
    }
}

private class CountingClassifier(
    private val verdict: Verdict?
) : AdClassifier {
    override val source = "FAKE"
    var calls = 0

    /** 파이프라인이 후보의 출처를 판별기까지 실제로 넘기는지 확인용. */
    var lastSourceKey: String? = null

    override suspend fun classify(text: String, sourceKey: String): Verdict? {
        calls++
        lastSourceKey = sourceKey
        return verdict
    }
}

class AgentPipelineTest {

    private var clock = 1_000_000L

    private fun candidate(text: String, id: String = "com.app:id/title", top: Int = 0) =
        AdCandidate(
            rect = Rect(0, top, 100, top + 100),
            texts = listOf(text),
            viewIds = listOf(id),
            sourceKey = "example.com"
        )

    private fun pipeline(
        dao: AdVerdictDao,
        classifier: AdClassifier,
        limiter: RateLimiter = RateLimiter(60, 3_600_000L) { clock }
    ) = AgentPipeline(dao, classifier, limiter) { clock }

    @Test
    fun `후보가 없으면 아무것도 하지 않는다`() = runTest {
        val classifier = CountingClassifier(Verdict(true, 0.9f, "x"))
        val result = pipeline(FakeVerdictDao(), classifier).run(emptyList())

        assertTrue(result.regions.isEmpty())
        assertEquals(0, classifier.calls)
    }

    @Test
    fun `광고로 판정되면 영역을 반환하고 저장한다`() = runTest {
        val dao = FakeVerdictDao()
        val classifier = CountingClassifier(Verdict(true, 0.9f, "광고 문구"))

        val result = pipeline(dao, classifier).run(listOf(candidate("무료 배송 이벤트")))

        assertEquals(1, result.regions.size)
        assertEquals(1, result.classified)
        assertEquals(1, dao.rows.size)
        assertEquals("FAKE", dao.rows.values.first().source)
    }

    // 출처가 없으면 LLM이 쇼핑몰 자체 상품과 끼어든 광고를 구분할 수 없다.
    // 넘기는 걸 잊어도 컴파일은 되므로(기본값이 "") 테스트로 못박는다.
    @Test
    fun `판별기에 후보의 출처를 함께 넘긴다`() = runTest {
        val classifier = CountingClassifier(Verdict(true, 0.9f, "광고"))

        pipeline(FakeVerdictDao(), classifier).run(listOf(candidate("무료 배송 이벤트")))

        assertEquals("example.com", classifier.lastSourceKey)
    }

    // 절감의 대부분이 부정 판정 캐시에서 나온다
    @Test
    fun `광고가 아니어도 판정을 저장한다`() = runTest {
        val dao = FakeVerdictDao()
        val classifier = CountingClassifier(Verdict(false, 0.8f, "기사"))

        val result = pipeline(dao, classifier).run(listOf(candidate("오늘의 날씨")))

        assertTrue(result.regions.isEmpty())
        assertEquals(1, dao.rows.size)
        assertEquals(false, dao.rows.values.first().isAd)
    }

    @Test
    fun `두 번째 실행은 캐시를 써 판별기를 부르지 않는다`() = runTest {
        val dao = FakeVerdictDao()
        val classifier = CountingClassifier(Verdict(true, 0.9f, "x"))
        val pipeline = pipeline(dao, classifier)
        val cards = listOf(candidate("무료 배송 이벤트"))

        pipeline.run(cards)
        val second = pipeline.run(cards)

        assertEquals(1, classifier.calls)
        assertEquals(1, second.cacheHits)
        assertEquals(0, second.classified)
        assertEquals(1, second.regions.size)   // 캐시 히트도 테두리를 그린다
    }

    // 숫자만 바뀌는 카운트다운·가격이 매번 새 호출을 만들면 안 된다
    @Test
    fun `숫자만 다른 같은 광고는 캐시에 걸린다`() = runTest {
        val dao = FakeVerdictDao()
        val classifier = CountingClassifier(Verdict(true, 0.9f, "x"))
        val pipeline = pipeline(dao, classifier)

        pipeline.run(listOf(candidate("특가 12,900원 03:21 남음")))
        pipeline.run(listOf(candidate("특가 45,700원 01:08 남음")))

        assertEquals(1, classifier.calls)
    }

    @Test
    fun `유휴 1회당 최대 3건만 새로 판별한다`() = runTest {
        val dao = FakeVerdictDao()
        val classifier = CountingClassifier(Verdict(true, 0.9f, "x"))

        // 숫자로 구분하면 정규화가 전부 같은 키로 합쳐 캐시 히트가 돼버린다.
        // 서로 다른 카드를 만들려면 숫자가 아닌 부분이 달라야 한다.
        val cards = ('a'..'j').mapIndexed { i, c ->
            candidate("무료 이벤트 상품$c", top = i * 200)
        }
        val result = pipeline(dao, classifier).run(cards)

        assertEquals(3, classifier.calls)
        assertEquals(3, result.classified)
        assertEquals(7, result.skippedByLimit)
    }

    @Test
    fun `시간당 상한에 걸리면 판별하지 않는다`() = runTest {
        val dao = FakeVerdictDao()
        val classifier = CountingClassifier(Verdict(true, 0.9f, "x"))
        val exhausted = RateLimiter(0, 3_600_000L) { clock }

        val result = pipeline(dao, classifier, exhausted).run(listOf(candidate("무료 이벤트")))

        assertEquals(0, classifier.calls)
        assertEquals(1, result.skippedByLimit)
        assertTrue(result.regions.isEmpty())
    }

    // 실패를 저장하면 TTL 30일 동안 그 카드를 다시 볼 기회가 사라진다
    @Test
    fun `판별에 실패하면 캐시에 남기지 않는다`() = runTest {
        val dao = FakeVerdictDao()
        val classifier = CountingClassifier(null)

        val result = pipeline(dao, classifier).run(listOf(candidate("무료 이벤트")))

        assertTrue(dao.rows.isEmpty())
        assertTrue(result.regions.isEmpty())
        assertEquals(0, result.classified)
    }

    @Test
    fun `유효기간이 지난 판정은 다시 판별한다`() = runTest {
        val dao = FakeVerdictDao()
        val classifier = CountingClassifier(Verdict(true, 0.9f, "x"))
        val pipeline = pipeline(dao, classifier)
        val cards = listOf(candidate("무료 배송 이벤트"))

        pipeline.run(cards)
        clock += 31L * 24 * 60 * 60 * 1000
        pipeline.run(cards)

        assertEquals(2, classifier.calls)
    }

    // ── 델타 스캔 (세션 메모) ─────────────────────────────────

    // 캐시만 보는 패스는 화면이 바뀔 때마다 도는데, 그때마다 DB를 다시 읽으면
    // 스크롤 내내 같은 카드를 계속 조회하게 된다
    @Test
    fun `캐시 전용 패스를 반복하면 DB를 다시 읽지 않는다`() = runTest {
        val dao = FakeVerdictDao()
        val classifier = CountingClassifier(Verdict(true, 0.9f, "x"))
        val pipeline = pipeline(dao, classifier)
        val cards = listOf(candidate("무료 배송 이벤트"))

        pipeline.run(cards)                       // 판별 + 저장 (여기서 기억도 채워진다)
        dao.findCount = 0

        val first = pipeline.run(cards, allowClassify = false)
        val second = pipeline.run(cards, allowClassify = false)
        val third = pipeline.run(cards, allowClassify = false)

        assertEquals("판별 패스가 기억을 채워 DB를 아예 읽지 않는다", 0, dao.findCount)
        assertEquals(1, first.memoHits)
        assertEquals(1, second.memoHits)
        assertEquals(1, third.memoHits)
    }

    @Test
    fun `기억된 카드도 계속 테두리를 유지한다`() = runTest {
        val dao = FakeVerdictDao()
        val pipeline = pipeline(dao, CountingClassifier(Verdict(true, 0.9f, "x")))
        val cards = listOf(candidate("무료 배송 이벤트"))

        pipeline.run(cards)
        pipeline.run(cards, allowClassify = false)
        val result = pipeline.run(cards, allowClassify = false)

        assertEquals(1, result.regions.size)
    }

    // 스크롤로 새 카드가 들어오면 그것만 넘어가야 한다
    @Test
    fun `새로 등장한 카드만 DB로 넘어간다`() = runTest {
        val dao = FakeVerdictDao()
        val pipeline = pipeline(dao, CountingClassifier(Verdict(false, 0.9f, "x")))
        val old = candidate("기존 카드 내용")

        pipeline.run(listOf(old))
        dao.findCount = 0

        pipeline.run(listOf(old), allowClassify = false)                       // 기억 적재
        dao.findCount = 0
        pipeline.run(listOf(old, candidate("새 카드 내용", top = 500)), allowClassify = false)

        assertEquals("새 카드 하나만 조회", 1, dao.findCount)
    }

    // memo가 DB와 어긋난 채 굳으면 만료·실패가 영원히 반영되지 않는다
    @Test
    fun `새 판별을 허용하는 패스는 기억을 건너뛰고 DB를 다시 읽는다`() = runTest {
        val dao = FakeVerdictDao()
        val pipeline = pipeline(dao, CountingClassifier(Verdict(true, 0.9f, "x")))
        val cards = listOf(candidate("무료 배송 이벤트"))

        pipeline.run(cards)
        pipeline.run(cards, allowClassify = false)
        dao.findCount = 0

        val result = pipeline.run(cards)

        assertEquals(1, dao.findCount)
        assertEquals(0, result.memoHits)
    }

    @Test
    fun `약한 id 신호가 임계값 아래 판정을 끌어올린다`() = runTest {
        val dao = FakeVerdictDao()
        val classifier = CountingClassifier(Verdict(true, 0.5f, "x"))

        val withoutSignal = pipeline(dao, classifier)
            .run(listOf(candidate("무료 이벤트", id = "com.app:id/title")))
        assertTrue(withoutSignal.regions.isEmpty())          // 0.50 → 미달

        val withSignal = pipeline(FakeVerdictDao(), CountingClassifier(Verdict(true, 0.5f, "x")))
            .run(listOf(candidate("무료 이벤트", id = "com.app:id/ad_slot")))
        assertEquals(1, withSignal.regions.size)             // 0.65 → 표시
    }
}
