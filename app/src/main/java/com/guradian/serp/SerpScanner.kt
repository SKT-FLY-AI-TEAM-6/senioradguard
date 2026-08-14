package com.guradian.serp

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 검색 결과 목록에서 결과 한 칸씩을 뽑는다. **스크린샷을 찍지 않는다.**
 *
 * ## 왜 검색 결과가 광고만큼 위험한가
 * "드라마 다시보기"를 검색하면 공식 OTT와 불법 다시보기 사이트가 **같은 목록에
 * 나란히** 나온다. 둘의 생김새는 거의 같고, 광고가 아니므로 광고 감지는 아무것도
 * 표시하지 않는다. 어르신 입장에서는 위에 있는 것을 누른다.
 *
 * ## 여기서는 주소가 이미 글자로 보인다
 * 검색 결과는 도메인·제목·설명을 화면에 그대로 노출한다. 접근성 트리에서 그대로
 * 읽히므로 그림을 볼 이유가 없다. 이 클래스가 하는 일은 **어느 노드 덩어리가 결과
 * 한 칸인지**를 정하고 그 안의 글자를 도메인/제목/설명으로 나누는 것뿐이다.
 *
 * ## 결과 한 칸을 어떻게 알아보는가
 * 구글·네이버는 결과마다 뷰 id를 주지 않는다. 그래서 모양으로 찾는다 —
 * **화면 폭의 절반 이상을 차지하고, 높이가 한 화면의 4~45% 사이인 클릭 가능한
 * 덩어리**. 이 조건은 결과 카드에는 맞고 아이콘·버튼·목록 전체에는 안 맞는다.
 * 하나 찾으면 그 아래로는 내려가지 않는다 — 안쪽 요소는 같은 결과의 조각이다.
 */
class SerpScanner {

    companion object {
        private const val MAX_DEPTH = 45
        private const val MAX_RESULTS = 10

        /**
         * 한 번의 스캔에서 볼 노드 수 기본 상한. 노드 하나가 IPC 한 번이다.
         * 호출부가 목적에 따라 줄여 잡는다 — 위치만 따라갈 때는 짧게, 판정할 때는 넉넉히.
         */
        const val DEFAULT_NODE_BUDGET = 1500

        /** 결과 한 칸의 크기 기준. 너무 작으면 아이콘, 너무 크면 목록 전체다. */
        private const val MIN_WIDTH_RATIO = 0.5
        private const val MIN_HEIGHT_RATIO = 0.04
        private const val MAX_HEIGHT_RATIO = 0.45

        /** 이보다 짧으면 판별할 근거가 없다. */
        private const val MIN_TITLE_CHARS = 4

        /** 제목을 찾을 때 볼 앞쪽 줄 수. 그 뒤로는 설명·부가 링크다. */
        private const val TITLE_LOOKAHEAD = 3

        private const val MAX_TITLE_CHARS = 120
        private const val MAX_SNIPPET_CHARS = 200

        private const val CHROME = "com.android.chrome"
        private const val CHROME_URL_BAR = "$CHROME:id/url_bar"

        /**
         * 구글 앱. **주소창이라는 것이 없다.**
         *
         * 어르신이 구글 검색을 쓰는 경로는 크롬만이 아니다. 홈 화면의 구글 위젯이나
         * 구글 앱으로 바로 검색하는 경우가 오히려 흔한데, 이쪽에는 주소창이 없어
         * 크롬용 관문(주소 문자열 검사)이 통째로 헛돈다.
         *
         * 대신 **패키지명 자체가 관문이 된다.** 이 앱은 검색이 본업이라 화면에 결과가
         * 떠 있다는 것이 곧 검색 결과 화면이라는 뜻이고, 결과 카드는 크롬과 똑같이
         * 도메인·제목·설명을 글자로 노출한다 (실기기 확인: 카드의 content-desc에
         * "티비핫 https://tvhot2.com 티비핫 - 누누티비…" 형태로 통째로 들어 있다).
         */
        const val GOOGLE_APP = "com.google.android.googlequicksearchbox"

        /**
         * 구글 앱 검색 결과 화면의 검색어 칸.
         *
         * **입력창(EditText)이 아니라 TextView다.** 검색을 마친 화면에는 편집 가능한
         * 노드가 트리에 하나도 없다(실기기 덤프 확인, 2026-08-14). 입력창만 찾으면
         * 검색어를 영영 못 읽고, 그것을 관문 근거로 쓰면 이 앱에서 기능이 통째로
         * 잠자코 있게 된다.
         *
         * id의 `srp`는 search results page다. **이 노드가 있다는 것 자체가 검색 결과
         * 화면이라는 뜻**이라, 검색어를 읽는 일과 화면을 판정하는 일이 한 번에 끝난다.
         */
        private const val GOOGLE_SEARCH_BOX = "$GOOGLE_APP:id/googleapp_srp_search_box_text"

        /** 검색 결과 페이지로 볼 주소 조각. */
        private val SEARCH_URL_MARKERS = listOf(
            "google.com/search", "google.co.kr/search", "google.com/webhp",
            "search.naver.com", "search.daum.net", "m.search.naver.com",
            "bing.com/search", "duckduckgo.com/?q", "search.yahoo.co"
        )

        /** 검색어가 담기는 쿼리 파라미터. 엔진마다 다르다. */
        private val QUERY_KEYS = listOf("q=", "query=", "p=", "wd=")
    }

    /**
     * 지금 화면이 검색 결과인가. — **① 화면 관문**
     *
     * 여기서 false면 트리를 훑지도 않는다. 뉴스를 읽는 동안에는 이 기능이
     * 통째로 잠들어 있어야 한다.
     *
     * @param pageUrl 크롬 주소창에서 읽은 원문. 브라우저가 아니면 null
     */
    fun isSearchScreen(
        pageUrl: String?,
        packageName: String = CHROME,
        hasQuery: Boolean = false
    ): Boolean {
        // 구글 앱은 주소가 없으니 **검색창에 글자가 있는가**로 판단한다.
        // 패키지명만으로 통과시키면 홈 피드(뉴스 카드가 잔뜩 있고 검색창은 비어 있다)
        // 까지 계속 훑게 된다.
        if (packageName == GOOGLE_APP) return hasQuery

        val url = pageUrl?.lowercase()?.trim() ?: return false
        if (url.isEmpty()) return false
        return SEARCH_URL_MARKERS.any { it in url }
    }

    /**
     * 주소에서 검색어를 뽑는다. 판별기에 문맥으로 넘긴다 — "다시보기"를 검색한
     * 맥락에서 "무료 시청"은 훨씬 강한 신호다.
     *
     * 크롬 주소창은 스크롤 위치에 따라 축약해서 보여주므로 실패할 수 있다.
     * 못 뽑으면 빈 문자열이고, 판별은 그대로 진행된다.
     */
    fun queryOf(pageUrl: String?): String {
        val url = pageUrl ?: return ""
        val queryPart = url.substringAfter('?', "").ifEmpty { return "" }

        for (pair in queryPart.split('&')) {
            val key = QUERY_KEYS.firstOrNull { pair.startsWith(it) } ?: continue
            return decode(pair.removePrefix(key))
        }
        return ""
    }

    /**
     * 마지막으로 주소창에서 읽은 주소. 주소창이 접혔을 때 쓴다.
     *
     * **인스턴스를 공유해서 쓸 것.** 이 상태가 인스턴스마다 따로 놀면 접힘 폴백이
     * 반쪽만 동작한다.
     */
    private var lastPageUrl: String? = null

    /**
     * 크롬 주소창의 현재 주소. 크롬이 아니면 null.
     *
     * ## 접히면 기억해 둔 값으로 물러난다 — 실기기에서 드러난 문제
     * 크롬은 아래로 스크롤하면 주소창을 접어 접근성 트리에서 **사라지게** 한다.
     * 그때 null로 물러나면 ① 화면 관문이 false가 되어 배지가 통째로 지워진다.
     * 실기기 확인(2026-08-14, SM-S937N, 구글 "드라마 다시보기")에서 두 번 스크롤하자
     * 화면에 누누티비 클론(noonootvk2.store)이 떠 있는데도 경고가 하나도 남지 않았다.
     * **스크롤해서 내려가는 결과가 정확히 어르신이 덜 살펴보는 결과다.**
     *
     * 마지막으로 본 주소를 기억해 그 구멍을 메운다. 같은 페이지를 보는 동안은
     * 주소가 바뀌지 않으므로 기억한 값이 곧 현재 값이다.
     *
     * 다른 페이지로 넘어가면? 크롬은 페이지를 새로 열 때 주소창을 다시 보여주므로
     * 그 시점에 새 주소로 갱신된다. 검색 결과가 아니면 ① 관문에서 걸러진다.
     */
    fun pageUrlOf(root: AccessibilityNodeInfo): String? {
        val pkg = root.packageName?.toString().orEmpty()
        val shown = if (pkg != CHROME) null else runCatching {
            root.findAccessibilityNodeInfosByViewId(CHROME_URL_BAR)
                ?.firstOrNull()
                ?.text?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()

        return resolvePageUrl(pkg, shown)
    }

    /** 노드 접근을 걷어낸 순수 부분. 단위 테스트가 여기를 덮는다. */
    internal fun resolvePageUrl(pkg: String, shown: String?): String? {
        if (pkg != CHROME) {
            // 다른 앱으로 넘어갔다. 남겨두면 그 앱 화면을 검색 결과로 오인한다.
            lastPageUrl = null
            return null
        }
        if (shown != null) lastPageUrl = shown
        return lastPageUrl
    }

    /**
     * 검색창에 적힌 검색어. 주소가 없는 구글 앱에서 쓴다.
     *
     * 결과 카드를 모을 때는 입력창이 든 덩어리를 버리는데([gather]의 hasEditable),
     * 검색어만큼은 그 입력창에서 나온다. 목적이 달라 경로도 따로 둔다.
     */
    fun queryFromSearchBox(root: AccessibilityNodeInfo): String {
        // 구글 앱 — 검색 결과 화면의 검색어 칸을 id로 곧장 찾는다.
        runCatching {
            root.findAccessibilityNodeInfosByViewId(GOOGLE_SEARCH_BOX)
                ?.firstOrNull()?.text?.toString()?.trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it.take(60) }

        // 폴백 — 앱 버전이 바뀌어 id가 달라졌거나 아직 입력 중인 화면.
        val out = StringBuilder()
        findEditable(root, 0, out)
        return out.toString().trim().take(60)
    }

    private fun findEditable(node: AccessibilityNodeInfo, depth: Int, out: StringBuilder) {
        if (depth > MAX_DEPTH || out.isNotEmpty()) return

        val editable = node.isEditable || node.className?.contains("EditText") == true
        if (editable) {
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.append(it) }
            return
        }
        for (i in 0 until node.childCount) {
            findEditable(node.getChild(i) ?: continue, depth + 1, out)
            if (out.isNotEmpty()) return
        }
    }

    /** 결과 한 칸과 그 자리. 판정은 [result]만 보고, 배지는 [rect]에 그린다. */
    data class Hit(val rect: Rect, val result: SerpResult)

    /**
     * 결과 항목들을 뽑는다. 도메인을 못 읽은 덩어리는 버린다 — 도메인이 없으면
     * 판정의 기준 단위가 없고, 제목만으로 위험도를 매기면 오탐이 걷잡을 수 없다.
     */
    fun extract(
        root: AccessibilityNodeInfo,
        nodeBudget: Int = DEFAULT_NODE_BUDGET
    ): List<Hit> {
        val screen = Rect().also { root.getBoundsInScreen(it) }
        if (screen.width() <= 0 || screen.height() <= 0) return emptyList()

        val out = mutableListOf<Hit>()
        collect(root, 0, screen, intArrayOf(nodeBudget), out)
        return out
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        depth: Int,
        screen: Rect,
        budget: IntArray,
        out: MutableList<Hit>
    ) {
        if (depth > MAX_DEPTH || out.size >= MAX_RESULTS || budget[0] <= 0) return
        budget[0]--

        if (node.isVisibleToUser && node.isClickable && isResultSized(node, screen)) {
            val texts = mutableListOf<String>()
            val hasEditable = booleanArrayOf(false)
            gather(node, 0, texts, hasEditable)

            // 검색 입력창이 들어 있는 덩어리는 결과가 아니다.
            if (!hasEditable[0]) {
                toResult(node, texts)?.let { out += it }
            }
            // 결과 한 칸을 찾았으면 더 내려가지 않는다.
            return
        }

        for (i in 0 until node.childCount) {
            collect(node.getChild(i) ?: continue, depth + 1, screen, budget, out)
            if (out.size >= MAX_RESULTS || budget[0] <= 0) return
        }
    }

    /**
     * 모아온 글자 조각을 도메인 · 제목 · 설명으로 나눈다.
     *
     * 순수 함수라 단위 테스트가 여기를 덮는다. 노드 순회는 위에서 끝났고, 결과
     * 한 칸의 **해석**이 실제로 틀리기 쉬운 부분이라 테스트가 필요한 곳도 여기다.
     */
    internal fun parseCard(texts: List<String>): Triple<String, String, String>? {
        val cleaned = texts.map { it.replace(Regex("""\s+"""), " ").trim() }
            .filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return null

        // 도메인이 들어 있는 조각을 찾는다. 구글은 결과 맨 윗줄에 "tving.com › vod"
        // 처럼 보여주고, 네이버는 제목 아래에 따로 둔다. 위치가 아니라 모양으로 찾는다.
        var host = ""
        var hostIndex = -1
        for ((i, text) in cleaned.withIndex()) {
            val candidate = UrlParts.normalizeHost(text)
            if (candidate.contains('.') && UrlParts.of(candidate).root.isNotEmpty()) {
                host = candidate
                hostIndex = i
                break
            }
        }
        if (host.isEmpty()) return null

        // 제목은 앞쪽 몇 줄 중 가장 긴 것. 구글은 사이트 이름("TVING")을 제목
        // ("TVING - 티빙 드라마 다시보기") 위에 따로 한 줄 더 보여주는데, 첫 줄을
        // 그냥 집으면 사이트 이름이 제목 자리에 온다.
        //
        // **이 나눔은 근사치다.** 제목과 설명은 둘 다 판별기에 넘어가므로 경계가
        // 한 칸 밀려도 판정은 달라지지 않는다. 중요한 것은 호스트를 맞게 읽는 것과
        // 글자를 하나도 흘리지 않는 것이다.
        val body = cleaned.filterIndexed { i, _ -> i != hostIndex }
        val title = body.take(TITLE_LOOKAHEAD)
            .filter { it.length >= MIN_TITLE_CHARS }
            .maxByOrNull { it.length }
            .orEmpty()
            .take(MAX_TITLE_CHARS)
        if (title.isEmpty()) return null

        val snippet = body.filter { it != title }
            .joinToString(" ")
            .take(MAX_SNIPPET_CHARS)

        return Triple(host, title, snippet)
    }

    private fun toResult(node: AccessibilityNodeInfo, texts: List<String>): Hit? {
        val (host, title, snippet) = parseCard(texts) ?: return null
        val rect = Rect().also { node.getBoundsInScreen(it) }
        return Hit(rect, SerpResult(host = host, title = title, snippet = snippet))
    }

    private fun isResultSized(node: AccessibilityNodeInfo, screen: Rect): Boolean {
        val r = Rect().also { node.getBoundsInScreen(it) }
        if (r.width() <= 0 || r.height() <= 0) return false
        return r.width() >= screen.width() * MIN_WIDTH_RATIO &&
            r.height() >= screen.height() * MIN_HEIGHT_RATIO &&
            r.height() <= screen.height() * MAX_HEIGHT_RATIO
    }

    private fun gather(
        node: AccessibilityNodeInfo,
        depth: Int,
        out: MutableList<String>,
        hasEditable: BooleanArray
    ) {
        if (depth > MAX_DEPTH || hasEditable[0]) return

        if (node.isEditable || node.className?.contains("EditText") == true) {
            hasEditable[0] = true
            return
        }

        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }

        for (i in 0 until node.childCount) {
            gather(node.getChild(i) ?: continue, depth + 1, out, hasEditable)
            if (hasEditable[0]) return
        }
    }

    /** %EB%93%9C... 형태를 되돌린다. URLDecoder를 쓰지 않는 이유는 예외 처리 때문이다. */
    private fun decode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value.replace('+', ' '), "UTF-8") }
            .getOrDefault(value)
            .take(60)
}
