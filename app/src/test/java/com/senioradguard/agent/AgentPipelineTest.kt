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
    override suspend fun classify(text: String): Verdict? {
        calls++
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
