package com.senioradguard.url

import com.senioradguard.risk.RiskCategory
import com.senioradguard.risk.RiskLevel
import com.senioradguard.risk.RiskVerdict

import com.senioradguard.agent.RateLimiter
import com.senioradguard.detector.db.IllegalDomain
import com.senioradguard.detector.db.IllegalDomainDao
import com.senioradguard.detector.db.UrlRisk
import com.senioradguard.detector.db.UrlRiskDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "언제 판별할지"만 검증한다. 판정 내용은 [UrlRiskPipelineTest]가 덮는다.
 */
class UrlRiskGuardTest {

    private var clock = 1_000_000L

    private val seen = mutableListOf<Pair<String, RiskLevel>>()

    private class MemoryIllegalDao : IllegalDomainDao {
        val stored = mutableMapOf<String, IllegalDomain>()
        override suspend fun findBySuffixes(suffixes: List<String>) =
            suffixes.mapNotNull { stored[it] }.maxByOrNull { it.domain.length }
        override suspend fun insertAll(rows: List<IllegalDomain>) =
            rows.forEach { stored[it.domain] = it }
        override suspend fun count() = stored.size
        override suspend fun clearAll() = stored.clear()
    }

    private class MemoryRiskDao : UrlRiskDao {
        val rows = mutableMapOf<String, UrlRisk>()
        override suspend fun find(host: String, notBefore: Long) =
            rows[host]?.takeIf { it.updatedAt >= notBefore }
        override suspend fun upsert(risk: UrlRisk) { rows[risk.host] = risk }
        override suspend fun recent(limit: Int) = rows.values.take(limit)
        override suspend fun deleteExpired(notBefore: Long) = 0
    }

    private val classifier = object : UrlRiskClassifier {
        override val source = "FAKE"
        var calls = 0
        override suspend fun classify(link: AdLink, signals: List<Signal>): RiskVerdict {
            calls++
            return RiskAggregator.heuristic(signals).copy(source = source)
        }
    }

    private fun guard(allowClassify: Boolean = true) = UrlRiskGuard(
        pipeline = UrlRiskPipeline(
            illegalDao = MemoryIllegalDao(),
            riskDao = MemoryRiskDao(),
            classifier = classifier,
            limiter = RateLimiter(30, 3_600_000L) { clock }
        ) { clock },
        onVerdict = { link, verdict -> seen += link.components.domain to verdict.level },
        allowClassify = { allowClassify },
        now = { clock }
    )

    // 사용자가 스스로 친 주소까지 광고 착지로 오인하면 안 된다
    @Test
    fun `광고를 누른 적이 없으면 주소가 바뀌어도 판별하지 않는다`() = runTest {
        val guard = guard()
        assertFalse(guard.onPageChanged("https://tvhot2.com/player/1"))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `광고를 누른 뒤 바뀐 주소를 판별한다`() = runTest {
        val guard = guard()
        guard.onAdClicked("무료 다시보기", "yna.co.kr")

        assertTrue(guard.onPageChanged("https://tvhot2.com/player/1"))
        assertEquals(listOf("tvhot2.com" to RiskLevel.HIGH), seen)
    }

    @Test
    fun `클릭한 지 오래됐으면 그 이동으로 보지 않는다`() = runTest {
        val guard = guard()
        guard.onAdClicked("광고", "yna.co.kr")
        clock += UrlRiskGuard.CLICK_WINDOW_MS + 1

        assertFalse(guard.onPageChanged("https://tvhot2.com/player/1"))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `클릭 기억은 한 번만 쓰인다`() = runTest {
        val guard = guard()
        guard.onAdClicked("광고", "yna.co.kr")

        guard.onPageChanged("https://tvhot2.com/player/1")
        assertFalse(guard.onPageChanged("https://another-site.com/x"))
        assertEquals(1, seen.size)
    }

    @Test
    fun `같은 주소로 이벤트가 여러 번 와도 한 번만 판별한다`() = runTest {
        val guard = guard()
        guard.onAdClicked("광고", "yna.co.kr")

        guard.onPageChanged("https://tvhot2.com/player/1")
        guard.onPageChanged("https://tvhot2.com/player/1")
        assertEquals(1, seen.size)
    }

    // 스크롤을 오르내릴 때마다 같은 광고 서버로 경고가 반복되면 사용자가 앱을 꺼버린다
    @Test
    fun `한 세션에서 같은 호스트는 한 번만 알린다`() = runTest {
        val guard = guard()
        val links = listOf(
            UrlParser.parse("https://tvhot2.com/a", "yna.co.kr", "광고", true)!!,
            UrlParser.parse("https://tvhot2.com/b", "yna.co.kr", "광고", true)!!
        )
        guard.onAdLinksSeen(links)
        guard.onAdLinksSeen(links)

        assertEquals(1, seen.size)
    }

    @Test
    fun `AI 토글이 꺼져 있어도 확실한 위험은 알린다`() = runTest {
        val guard = guard(allowClassify = false)
        guard.onAdClicked("무료 다시보기", "yna.co.kr")
        guard.onPageChanged("https://tvhot2.com/player/1")

        assertEquals(0, classifier.calls)
        assertEquals(listOf("tvhot2.com" to RiskLevel.HIGH), seen)
    }

    // 상한에 걸려 규칙만으로 낸 '낮음'까지 기억하면, 판별기가 살아나도
    // 그 도메인을 이 세션 내내 다시 보지 않는다
    @Test
    fun `결론이 안 난 낮음 판정은 기억하지 않는다`() = runTest {
        val guard = guard(allowClassify = false)
        val link = UrlParser.parse("https://plain-shop.com/a", "yna.co.kr", "", true)!!

        guard.onAdLinksSeen(listOf(link))
        guard.onAdLinksSeen(listOf(link))

        assertEquals(2, seen.size)
    }

    @Test
    fun `대상 앱을 벗어나면 클릭 기억이 사라진다`() = runTest {
        val guard = guard()
        guard.onAdClicked("광고", "yna.co.kr")
        assertTrue(guard.hasPendingClick())

        guard.reset()

        assertFalse(guard.hasPendingClick())
        assertFalse(guard.onPageChanged("https://tvhot2.com/player/1"))
    }

    // ── 클릭 이벤트가 오지 않는 경우 (크롬 웹 배너 — 실기기 확인) ──────

    // 클릭 이벤트에만 기대면 모바일 웹 광고는 거의 전부 놓친다
    @Test
    fun `광고가 떠 있던 화면에서 다른 사이트로 넘어가면 판별한다`() = runTest {
        val guard = guard()
        guard.onAdsShown("hankyung.com")
        guard.onPageChanged("https://www.hankyung.com/article/1")   // 현재 위치 기록

        assertTrue(guard.onPageChanged("https://tvhot2.com/player/1"))
        assertEquals(listOf("tvhot2.com" to RiskLevel.HIGH), seen)
    }

    // 기사 → 기사 이동은 광고 착지가 아니다
    @Test
    fun `같은 사이트 안의 이동은 판별하지 않는다`() = runTest {
        val guard = guard()
        guard.onAdsShown("hankyung.com")
        guard.onPageChanged("https://www.hankyung.com/article/1")

        assertFalse(guard.onPageChanged("https://markets.hankyung.com/article/2"))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `광고가 없던 화면의 이동은 판별하지 않는다`() = runTest {
        val guard = guard()
        guard.onPageChanged("https://www.hankyung.com/article/1")

        assertFalse(guard.onPageChanged("https://tvhot2.com/player/1"))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `광고를 본 지 오래되면 이동만으로는 판별하지 않는다`() = runTest {
        val guard = guard()
        guard.onAdsShown("hankyung.com")
        guard.onPageChanged("https://www.hankyung.com/article/1")
        clock += UrlRiskGuard.ADS_SHOWN_WINDOW_MS + 1

        assertFalse(guard.onPageChanged("https://tvhot2.com/player/1"))
    }

    // 착지 문맥은 판별기에 그대로 넘어간다. 사실이 아닌 값을 넣으면 판단이 틀어진다
    @Test
    fun `클릭 없이 이동만 본 건은 광고 요소로 단정하지 않는다`() = runTest {
        var captured: AdLink? = null
        val guard = UrlRiskGuard(
            pipeline = UrlRiskPipeline(
                MemoryIllegalDao(), MemoryRiskDao(), classifier,
                RateLimiter(30, 3_600_000L) { clock }
            ) { clock },
            onVerdict = { link, _ -> captured = link },
            now = { clock }
        )
        guard.onAdsShown("hankyung.com")
        guard.onPageChanged("https://www.hankyung.com/article/1")
        guard.onPageChanged("https://tvhot2.com/player/1")

        assertFalse(captured!!.context.isAdElement)
        assertEquals("hankyung.com", captured!!.context.sourcePageUrl)
    }

    @Test
    fun `클릭이 있으면 광고 요소로 표시한다`() = runTest {
        var captured: AdLink? = null
        val guard = UrlRiskGuard(
            pipeline = UrlRiskPipeline(
                MemoryIllegalDao(), MemoryRiskDao(), classifier,
                RateLimiter(30, 3_600_000L) { clock }
            ) { clock },
            onVerdict = { link, _ -> captured = link },
            now = { clock }
        )
        guard.onAdClicked("무료 다시보기", "yna.co.kr")
        guard.onPageChanged("https://tvhot2.com/player/1")

        assertTrue(captured!!.context.isAdElement)
        assertEquals("무료 다시보기", captured!!.context.anchorText)
    }

    @Test
    fun `주소창을 읽을 필요를 알려준다`() {
        val guard = guard()
        assertFalse(guard.wantsPageUrl())

        guard.onAdsShown("hankyung.com")
        assertTrue(guard.wantsPageUrl())

        clock += UrlRiskGuard.ADS_SHOWN_WINDOW_MS + 1
        assertFalse(guard.wantsPageUrl())

        guard.onAdClicked("광고", "hankyung.com")
        assertTrue(guard.wantsPageUrl())
    }

    @Test
    fun `주소를 읽을 수 없으면 조용히 넘어간다`() = runTest {
        val guard = guard()
        guard.onAdClicked("광고", "yna.co.kr")

        assertFalse(guard.onPageChanged("설정 화면"))
        assertTrue(seen.isEmpty())
    }
}
