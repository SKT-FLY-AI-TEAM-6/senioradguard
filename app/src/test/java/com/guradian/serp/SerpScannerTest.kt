package com.guradian.serp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 노드 순회를 걷어낸 순수 부분 — ① 화면 관문, 검색어 추출, 결과 카드 해석.
 *
 * 실기기 없이 덮을 수 있는 것은 여기까지다. 어느 노드가 결과 한 칸인지를 정하는
 * 크기 조건은 실제 트리가 있어야 검증되므로 실기기 확인 항목으로 남는다.
 */
class SerpScannerTest {

    private val scanner = SerpScanner()

    // ── ① 화면 관문 ────────────────────────────────────────────

    @Test
    fun `검색 결과 페이지만 통과시킨다`() {
        assertTrue(scanner.isSearchScreen("https://www.google.com/search?q=드라마+다시보기"))
        assertTrue(scanner.isSearchScreen("m.search.naver.com/search.naver?query=다시보기"))
        assertTrue(scanner.isSearchScreen("search.daum.net/search?w=tot&q=영화"))

        // 뉴스를 읽는 동안에는 이 기능이 통째로 잠들어 있어야 한다
        assertFalse(scanner.isSearchScreen("https://www.yna.co.kr/view/AKR20260814"))
        assertFalse(scanner.isSearchScreen("tvhot2.com/player/1234"))
        assertFalse(scanner.isSearchScreen(null))
        assertFalse(scanner.isSearchScreen(""))
    }

    @Test
    fun `구글 앱은 주소가 없으므로 검색창에 글자가 있는지로 판단한다`() {
        // 어르신이 구글 검색을 쓰는 경로는 크롬만이 아니다 — 홈 화면 위젯이나
        // 구글 앱으로 바로 검색하는 경우가 오히려 흔한데, 그쪽엔 주소창이 없어
        // 주소 검사가 통째로 헛돈다. 실기기에서 구글 앱으로 검색하니 이 기능이
        // 아무 반응도 하지 않았다.
        assertTrue(scanner.isSearchScreen(null, SerpScanner.GOOGLE_APP, hasQuery = true))

        // 홈 피드에도 큰 카드가 잔뜩 있다. 검색창이 비어 있으면 검색 화면이 아니다 —
        // 패키지명만으로 통과시키면 피드를 계속 훑게 된다.
        assertFalse(scanner.isSearchScreen(null, SerpScanner.GOOGLE_APP, hasQuery = false))

        // 크롬은 여전히 주소로 판단한다 (검색창 텍스트와 무관)
        assertFalse(scanner.isSearchScreen(null, "com.android.chrome", hasQuery = true))
        assertFalse(scanner.isSearchScreen("yna.co.kr/view/1", "com.android.chrome"))
        assertTrue(scanner.isSearchScreen("google.com/search?q=x", "com.android.chrome"))
    }

    // ── 주소창이 접혔을 때 ─────────────────────────────────────

    @Test
    fun `주소창이 접히면 마지막으로 본 주소로 물러난다`() {
        // 실기기에서 드러난 문제다. 크롬은 아래로 스크롤하면 주소창을 트리에서
        // 아예 없앤다. null로 물러나면 ① 관문이 false가 되어 배지가 통째로 지워지고,
        // 스크롤해서 내려가는 결과가 정확히 어르신이 덜 살펴보는 결과다.
        val url = "https://www.google.com/search?q=드라마+다시보기"

        assertEquals(url, scanner.resolvePageUrl("com.android.chrome", url))
        assertEquals("접힘", url, scanner.resolvePageUrl("com.android.chrome", null))
        assertTrue(scanner.isSearchScreen(scanner.resolvePageUrl("com.android.chrome", null)))
    }

    @Test
    fun `다른 앱으로 넘어가면 기억을 버린다`() {
        // 남겨두면 유튜브 화면을 검색 결과로 오인해 엉뚱한 곳에 배지를 그린다
        scanner.resolvePageUrl("com.android.chrome", "google.com/search?q=x")

        assertNull(scanner.resolvePageUrl("com.google.android.youtube", null))
        assertNull("크롬으로 돌아와도 기억은 지워진 상태", scanner.resolvePageUrl("com.android.chrome", null))
    }

    @Test
    fun `다른 페이지로 넘어가면 새 주소로 갱신된다`() {
        scanner.resolvePageUrl("com.android.chrome", "google.com/search?q=x")

        // 크롬은 페이지를 새로 열 때 주소창을 다시 보여준다
        val landed = scanner.resolvePageUrl("com.android.chrome", "tvhot2.com/player/1")

        assertEquals("tvhot2.com/player/1", landed)
        assertFalse("검색 결과가 아니므로 ① 관문에서 걸러진다", scanner.isSearchScreen(landed))
    }

    // ── 검색어 ────────────────────────────────────────────────

    @Test
    fun `검색어를 주소에서 뽑는다`() {
        assertEquals(
            "드라마 다시보기",
            scanner.queryOf("https://www.google.com/search?q=%EB%93%9C%EB%9D%BC%EB%A7%88+%EB%8B%A4%EC%8B%9C%EB%B3%B4%EA%B8%B0&hl=ko")
        )
        assertEquals("영화", scanner.queryOf("search.naver.com/search.naver?query=영화&where=m"))
    }

    @Test
    fun `주소창이 접혀 검색어를 못 뽑아도 빈 문자열로 넘어간다`() {
        // 판별은 검색어 없이도 진행된다. 문맥이 하나 빠질 뿐이다.
        assertEquals("", scanner.queryOf("google.com/search"))
        assertEquals("", scanner.queryOf(null))
    }

    // ── 결과 카드 해석 ─────────────────────────────────────────

    @Test
    fun `구글 형태의 카드에서 도메인과 제목을 나눈다`() {
        val card = listOf("TVING", "tving.com › vod", "TVING - 티빙 드라마 다시보기", "오리지널 시리즈와 방송 다시보기")

        val parsed = scanner.parseCard(card)

        assertEquals("tving.com", parsed!!.first)
        assertEquals("TVING - 티빙 드라마 다시보기", parsed.second)
        assertTrue(parsed.third.contains("오리지널"))
    }

    @Test
    fun `주소 표기가 흔들려도 같은 호스트로 읽는다`() {
        assertEquals("tving.com", UrlParts.normalizeHost("https://www.tving.com/vod/1"))
        assertEquals("tving.com", UrlParts.normalizeHost("tving.com › vod"))
        assertEquals("tving.com", UrlParts.normalizeHost("WWW.TVING.COM"))
        assertEquals("tving.com", UrlParts.normalizeHost("tving.com:443/x"))
    }

    @Test
    fun `도메인을 못 읽은 덩어리는 버린다`() {
        // 도메인이 없으면 판정의 기준 단위가 없다. 제목만으로 위험도를 매기면
        // 오탐이 걷잡을 수 없어진다.
        assertNull(scanner.parseCard(listOf("관련 검색어", "드라마 추천")))
        assertNull(scanner.parseCard(emptyList()))
    }

    @Test
    fun `제목이 없으면 버린다`() {
        assertNull(scanner.parseCard(listOf("tving.com", "홈")))
    }

    // ── 호스트 분해 ────────────────────────────────────────────

    @Test
    fun `등록 도메인을 정확히 잘라낸다`() {
        UrlParts.of("blog.naver.com").let {
            assertEquals("naver.com", it.root)
            assertEquals("blog", it.subdomain)
            assertEquals("com", it.tld)
        }
        // co.kr은 2단계 접미사다. naver.co.kr을 "co.kr"로 자르면 모든 co.kr이 한 도메인이 된다
        UrlParts.of("news.kbs.co.kr").let {
            assertEquals("kbs.co.kr", it.root)
            assertEquals("news", it.subdomain)
            assertEquals("co.kr", it.tld)
        }
        UrlParts.of("naver.secure-login.top").let {
            assertEquals("secure-login.top", it.root)
            assertEquals("naver", it.subdomain)
        }
    }

    @Test
    fun `IP 주소를 알아본다`() {
        assertTrue(UrlParts.of("175.45.176.12").isIpAddress)
        assertFalse(UrlParts.of("tving.com").isIpAddress)
    }
}
