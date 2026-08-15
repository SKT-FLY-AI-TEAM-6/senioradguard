package com.guradian.serp

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **AI 호출 트리거의 명세다.** 이 파일이 통과하는 동안은 "언제 판별기가 불리는가"의
 * 답이 바뀌지 않는다.
 *
 * 비용이 전부 여기서 갈리므로, 규칙·캐시로 끝나야 할 것이 판별기까지 내려가면
 * 검색 한 번에 호출 한 건씩이 계속 나간다.
 */
class SerpRiskEngineTest {

    /** 무엇이 판별기까지 내려왔는지 기록하는 가짜 판별기. */
    private class SpyClassifier(
        private val answers: Map<String, Int> = emptyMap()
    ) : SerpClassifier {
        override val source = SerpVerdict.SOURCE_LLM
        val calls = mutableListOf<List<String>>()
        var lastQuery: String? = null

        override suspend fun classify(
            query: String,
            items: List<SerpClassifier.Request>
        ): Map<String, SerpVerdict> {
            calls += items.map { it.host }
            lastQuery = query
            return items.associate {
                it.host to SerpVerdict.of(
                    RiskCategory.UNVERIFIED_THIRD_PARTY,
                    answers[it.host] ?: 10,
                    "판별기 판정",
                    source
                )
            }
        }
    }

    private fun result(host: String, title: String = "제목입니다", snippet: String = "") =
        SerpResult(host, title, snippet)

    // ── ④ 미지 관문 ────────────────────────────────────────────

    @Test
    fun `규칙으로 확정된 것은 판별기까지 내려가지 않는다`() = runTest {
        val spy = SpyClassifier()
        val engine = SerpRiskEngine(spy)

        val outcome = engine.evaluate(
            "드라마 다시보기",
            listOf(
                result("tving.com", "티빙"),               // 규칙: 확정 안전
                result("tvhot2.com", "무료 다시보기"),      // 규칙: 확정 위험
                result("onnada.com", "TV 편성표")          // 규칙: 결론 없음 → 판별기
            )
        )

        assertEquals("판별기에 내려간 것은 하나뿐이어야 한다", 1, spy.calls.size)
        assertEquals(listOf("onnada.com"), spy.calls.first())
        assertEquals(RiskGrade.LOW, outcome.verdicts[0].grade)
        assertEquals(RiskGrade.HIGH, outcome.verdicts[1].grade)
    }

    @Test
    fun `한 화면은 배치 한 번으로 끝난다`() = runTest {
        val spy = SpyClassifier()
        val engine = SerpRiskEngine(spy)

        engine.evaluate(
            "다시보기",
            listOf(result("a-site.com"), result("b-site.com"), result("c-site.com"))
        )

        assertEquals("호출은 1회", 1, spy.calls.size)
        assertEquals("세 건이 한 배치에 실려야 한다", 3, spy.calls.first().size)
    }

    @Test
    fun `같은 호스트가 여러 번 나와도 한 번만 보낸다`() = runTest {
        val spy = SpyClassifier()
        val engine = SerpRiskEngine(spy)

        val outcome = engine.evaluate(
            "다시보기",
            listOf(
                result("some-site.com", "첫 번째 페이지"),
                result("some-site.com", "두 번째 페이지")
            )
        )

        assertEquals(listOf("some-site.com"), spy.calls.first())
        // 판정은 둘 다 채워져야 한다 — 호스트 단위 판정을 두 칸에 나눠 준다
        assertEquals(2, outcome.verdicts.size)
        assertTrue(outcome.verdicts.all { it.source == SerpVerdict.SOURCE_LLM })
    }

    @Test
    fun `검색어가 판별기에 함께 넘어간다`() = runTest {
        val spy = SpyClassifier()
        SerpRiskEngine(spy).evaluate("드라마 다시보기", listOf(result("some-site.com")))
        assertEquals("드라마 다시보기", spy.lastQuery)
    }

    // ── 캐시 ──────────────────────────────────────────────────

    @Test
    fun `두 번째 검색에서 같은 도메인은 판별기를 부르지 않는다`() = runTest {
        val spy = SpyClassifier()
        val engine = SerpRiskEngine(spy)

        engine.evaluate("다시보기", listOf(result("some-site.com", "첫 화면")))
        // 다른 화면(다른 지문)인데 도메인이 겹친다
        engine.evaluate("영화", listOf(result("some-site.com", "둘째 화면"), result("tving.com")))

        assertEquals("두 번째는 캐시로 끝나야 한다", 1, spy.calls.size)
    }

    @Test
    fun `TTL이 지나면 다시 판별한다`() = runTest {
        var clock = 0L
        val spy = SpyClassifier()
        val engine = SerpRiskEngine(spy, ttlMillis = 1000, now = { clock })

        engine.evaluate("다시보기", listOf(result("some-site.com")))
        clock += 1001
        engine.reset()
        engine.evaluate("다시보기", listOf(result("some-site.com")))

        assertEquals(2, spy.calls.size)
    }

    @Test
    fun `판별에 실패하면 캐시에 남기지 않는다`() = runTest {
        // 실패한 판정을 저장하면 TTL 동안 그 도메인을 다시 볼 기회가 사라진다.
        val failing = object : SerpClassifier {
            override val source = SerpVerdict.SOURCE_LLM
            var calls = 0
            override suspend fun classify(
                query: String,
                items: List<SerpClassifier.Request>
            ): Map<String, SerpVerdict> {
                calls++
                return emptyMap()
            }
        }
        val engine = SerpRiskEngine(failing)

        engine.evaluate("다시보기", listOf(result("some-site.com")))
        engine.reset()
        engine.evaluate("다시보기", listOf(result("some-site.com")))

        assertEquals("다음 기회에 다시 시도해야 한다", 2, failing.calls)
    }

    // ── ③ 변경 관문 ────────────────────────────────────────────

    @Test
    fun `같은 화면이 다시 떠도 판별기를 부르지 않는다`() = runTest {
        val spy = SpyClassifier()
        val engine = SerpRiskEngine(spy)
        val screen = listOf(result("some-site.com"), result("tving.com"))

        val first = engine.evaluate("다시보기", screen)
        val second = engine.evaluate("다시보기", screen)

        assertEquals(1, spy.calls.size)
        assertTrue("두 번째는 생략으로 표시된다", second.skippedUnchanged)
        assertEquals(first.verdicts.map { it.score }, second.verdicts.map { it.score })
    }

    @Test
    fun `화면이 바뀌면 다시 판정한다`() = runTest {
        val spy = SpyClassifier()
        val engine = SerpRiskEngine(spy)

        engine.evaluate("다시보기", listOf(result("some-site.com")))
        engine.evaluate("다시보기", listOf(result("other-site.com")))

        assertEquals(2, spy.calls.size)
    }

    // ── 상한 ──────────────────────────────────────────────────

    @Test
    fun `호출 상한에 걸리면 규칙 판정으로 조용히 물러난다`() = runTest {
        val spy = SpyClassifier()
        // 토큰 0개 — 처음부터 상한에 걸린 상태
        val engine = SerpRiskEngine(spy, limiter = SerpCallBudget(capacity = 0, refillMs = 1))

        val outcome = engine.evaluate(
            "다시보기",
            listOf(result("some-site.com"), result("tvhot2.com", "무료 다시보기"))
        )

        assertTrue("판별기는 불리지 않는다", spy.calls.isEmpty())
        // 기능이 멈추지는 않는다 — 규칙으로 잡히는 것은 그대로 잡힌다
        assertEquals(RiskGrade.HIGH, outcome.verdicts[1].grade)
        assertEquals(2, outcome.verdicts.size)
    }

    @Test
    fun `배치 상한을 넘는 화면은 앞에서부터 자른다`() = runTest {
        val spy = SpyClassifier()
        val engine = SerpRiskEngine(spy)
        val many = (1..12).map { result("site$it-unknown.com") }

        engine.evaluate("다시보기", many)

        assertEquals(SerpRiskEngine.MAX_BATCH, spy.calls.first().size)
    }

    // ── 규칙과 판별기의 관계 ────────────────────────────────────

    @Test
    fun `확신이 '위험'에 못 미치는 신호는 판별기가 최종 판단한다`() = runTest {
        // 도박 문구(55)는 확정 신호지만 '위험'(70)에는 못 미친다. 규칙이 여기서
        // 끝내면 틀렸을 때 고칠 기회가 없으므로 판별기로 넘긴다.
        val spy = SpyClassifier(answers = mapOf("unknown-site.com" to 10))
        val engine = SerpRiskEngine(spy)

        val outcome = engine.evaluate(
            "스포츠 중계",
            listOf(result("unknown-site.com", "무료 중계", "꽁머니 지급 안전 놀이터"))
        )

        assertEquals(listOf("unknown-site.com"), spy.calls.first())
        // 판별기가 10점(안전)을 줘도 확정 신호가 만든 하한 아래로는 안 내려간다
        assertTrue(outcome.verdicts[0].score >= RiskGrade.MEDIUM.minScore)
    }

    @Test
    fun `결과가 없으면 아무것도 하지 않는다`() = runTest {
        val spy = SpyClassifier()
        val outcome = SerpRiskEngine(spy).evaluate("다시보기", emptyList())
        assertTrue(outcome.verdicts.isEmpty())
        assertTrue(spy.calls.isEmpty())
    }
}
