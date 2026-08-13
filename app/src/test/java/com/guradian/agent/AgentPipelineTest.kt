package com.guradian.agent

import android.graphics.Rect
import com.guradian.store.InMemoryVerdictStore
import com.guradian.store.VerdictStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class CountingClassifier(
    private val verdict: Verdict?
) : AdClassifier {
    override val source = "FAKE"
    var calls = 0

    /** 파이프라인이 후보의 출처를 넘겨주는지 확인하려고 마지막 값을 남긴다. */
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

    private fun store() = InMemoryVerdictStore(now = { clock })

    private fun pipeline(
        store: VerdictStore,
        classifier: AdClassifier,
        limiter: RateLimiter = RateLimiter(60, 3_600_000L) { clock }
    ) = AgentPipeline(store, classifier, limiter)

    @Test
    fun `후보가 없으면 아무것도 하지 않는다`() = runTest {
        val classifier = CountingClassifier(Verdict(true, 0.9f, "x"))
        val result = pipeline(store(), classifier).run(emptyList())

        assertTrue(result.regions.isEmpty())
        assertEquals(0, classifier.calls)
    }

    @Test
    fun `판별기에 후보의 출처를 함께 넘긴다`() = runTest {
        // 출처는 판정을 가르는 신호다. 같은 "70% 할인"이라도 쇼핑몰이면 그 앱의
        // 본래 기능이고 뉴스 사이트면 끼어든 광고라, 빠지면 오탐이 돌아온다.
        val classifier = CountingClassifier(Verdict(true, 0.9f, "x"))

        pipeline(store(), classifier).run(listOf(candidate("무료 배송 이벤트")))

        assertEquals("example.com", classifier.lastSourceKey)
    }

    @Test
    fun `광고로 판정되면 영역을 반환하고 저장한다`() = runTest {
        val store = store()
        val classifier = CountingClassifier(Verdict(true, 0.9f, "광고 문구"))

        val result = pipeline(store, classifier).run(listOf(candidate("무료 배송 이벤트")))

        assertEquals(1, result.regions.size)
        assertEquals(1, result.classified)
        assertEquals(1, store.size())
    }

    // 절감의 대부분이 부정 판정 캐시에서 나온다
    @Test
    fun `광고가 아니어도 판정을 저장한다`() = runTest {
        val store = store()
        val classifier = CountingClassifier(Verdict(false, 0.8f, "기사"))

        val result = pipeline(store, classifier).run(listOf(candidate("오늘의 날씨")))

        assertTrue(result.regions.isEmpty())
        assertEquals(1, store.size())
    }

    @Test
    fun `두 번째 실행은 캐시를 써 판별기를 부르지 않는다`() = runTest {
        val store = store()
        val classifier = CountingClassifier(Verdict(true, 0.9f, "x"))
        val pipeline = pipeline(store, classifier)
        val cards = listOf(candidate("무료 배송 이벤트"))

        pipeline.run(cards)
        val second = pipeline.run(cards)

        assertEquals(1, classifier.calls)
        assertEquals(1, second.cacheHits)
        assertEquals(0, second.classified)
        assertEquals(1, second.regions.size)   // 캐시 히트도 테두리를 그린다
    }

    // 판별한 카드는 스크롤해도 점선이 따라와야 한다 — 그 경로가 이 모드다
    @Test
    fun `캐시 전용 모드는 판별기를 부르지 않고 캐시만 그린다`() = runTest {
        val store = store()
        val classifier = CountingClassifier(Verdict(true, 0.9f, "x"))
        val cards = listOf(candidate("무료 배송 이벤트"))

        pipeline(store, classifier).run(cards)
        val cacheOnly = pipeline(store, classifier).run(cards, allowClassify = false)

        assertEquals(1, classifier.calls)          // 늘지 않았다
        assertEquals(1, cacheOnly.regions.size)
        assertEquals(1, cacheOnly.cacheHits)
    }

    @Test
    fun `캐시 전용 모드는 미스를 그냥 넘긴다`() = runTest {
        val classifier = CountingClassifier(Verdict(true, 0.9f, "x"))

        val result = pipeline(store(), classifier)
            .run(listOf(candidate("처음 보는 카드")), allowClassify = false)

        assertEquals(0, classifier.calls)
        assertTrue(result.regions.isEmpty())
        assertEquals(0, result.cacheHits)
    }

    // 숫자만 바뀌는 카운트다운·가격이 매번 새 호출을 만들면 안 된다
    @Test
    fun `숫자만 다른 같은 광고는 캐시에 걸린다`() = runTest {
        val store = store()
        val classifier = CountingClassifier(Verdict(true, 0.9f, "x"))
        val pipeline = pipeline(store, classifier)

        pipeline.run(listOf(candidate("특가 12,900원 03:21 남음")))
        pipeline.run(listOf(candidate("특가 45,700원 01:08 남음")))

        assertEquals(1, classifier.calls)
    }

    @Test
    fun `1회당 최대 3건만 새로 판별한다`() = runTest {
        val classifier = CountingClassifier(Verdict(true, 0.9f, "x"))

        // 숫자로 구분하면 정규화가 전부 같은 키로 합쳐 캐시 히트가 돼버린다.
        // 서로 다른 카드를 만들려면 숫자가 아닌 부분이 달라야 한다.
        val cards = ('a'..'j').mapIndexed { i, c ->
            candidate("무료 이벤트 상품$c", top = i * 200)
        }
        val result = pipeline(store(), classifier).run(cards)

        assertEquals(3, classifier.calls)
        assertEquals(3, result.classified)
        assertEquals(7, result.skippedByLimit)
    }

    @Test
    fun `시간당 상한에 걸리면 판별하지 않는다`() = runTest {
        val classifier = CountingClassifier(Verdict(true, 0.9f, "x"))
        val exhausted = RateLimiter(0, 3_600_000L) { clock }

        val result = pipeline(store(), classifier, exhausted).run(listOf(candidate("무료 이벤트")))

        assertEquals(0, classifier.calls)
        assertEquals(1, result.skippedByLimit)
        assertTrue(result.regions.isEmpty())
    }

    // 실패를 저장하면 TTL 30일 동안 그 카드를 다시 볼 기회가 사라진다
    @Test
    fun `판별에 실패하면 캐시에 남기지 않는다`() = runTest {
        val store = store()
        val classifier = CountingClassifier(null)

        val result = pipeline(store, classifier).run(listOf(candidate("무료 이벤트")))

        assertEquals(0, store.size())
        assertTrue(result.regions.isEmpty())
        assertEquals(0, result.classified)
    }

    @Test
    fun `유효기간이 지난 판정은 다시 판별한다`() = runTest {
        val store = store()
        val classifier = CountingClassifier(Verdict(true, 0.9f, "x"))
        val pipeline = pipeline(store, classifier)
        val cards = listOf(candidate("무료 배송 이벤트"))

        pipeline.run(cards)
        clock += 31L * 24 * 60 * 60 * 1000
        pipeline.run(cards)

        assertEquals(2, classifier.calls)
    }

    @Test
    fun `약한 id 신호가 임계값 아래 판정을 끌어올린다`() = runTest {
        val withoutSignal = pipeline(store(), CountingClassifier(Verdict(true, 0.5f, "x")))
            .run(listOf(candidate("무료 이벤트", id = "com.app:id/title")))
        assertTrue(withoutSignal.regions.isEmpty())          // 0.50 → 미달

        val withSignal = pipeline(store(), CountingClassifier(Verdict(true, 0.5f, "x")))
            .run(listOf(candidate("무료 이벤트", id = "com.app:id/ad_slot")))
        assertEquals(1, withSignal.regions.size)             // 0.65 → 표시
    }
}
