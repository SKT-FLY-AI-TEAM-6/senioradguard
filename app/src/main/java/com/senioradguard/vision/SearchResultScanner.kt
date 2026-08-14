package com.senioradguard.vision

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.senioradguard.url.LinkHarvester

/**
 * 검색 결과 목록에서 결과 한 칸씩을 ROI로 뽑는다.
 *
 * ## 왜 검색 결과가 광고만큼 위험한가
 * "영화 다시보기"를 검색하면 공식 OTT와 불법 다시보기 사이트가 **같은 목록에
 * 나란히** 나온다. 둘의 생김새는 거의 같다. 광고가 아니므로 Layer 1·2는 아무것도
 * 표시하지 않고, 어르신 입장에서는 위에 있는 것을 누른다.
 *
 * ## 여기서는 주소가 글자로 보인다
 * 검색 결과는 도메인을 화면에 그대로 노출한다. 그래서 불법 목록에 있는 사이트는
 * **스크린샷을 찍기도 전에** 걸러진다(VisionRiskPipeline.byShownUrl). 목록에 없는
 * 처음 보는 사이트만 그림까지 가서 판별한다.
 */
class SearchResultScanner {

    private companion object {
        const val MAX_DEPTH = 45
        const val MAX_RESULTS = 8
        const val MAX_NODES = 1500

        /** 결과 한 칸의 크기 기준. 너무 작으면 아이콘, 너무 크면 목록 전체다. */
        const val MIN_WIDTH_RATIO = 0.55
        const val MIN_HEIGHT_RATIO = 0.04
        const val MAX_HEIGHT_RATIO = 0.40

        /** 이보다 짧으면 판별할 근거가 없다. */
        const val MIN_TEXT_CHARS = 6

        const val MAX_TEXT_CHARS = 200

        /** 검색 결과 페이지로 볼 주소 조각. */
        val SEARCH_URL_MARKERS = listOf(
            "google.com/search", "google.co.kr/search",
            "search.naver.com", "search.daum.net",
            "bing.com/search", "duckduckgo.com/"
        )

        /** 검색을 자체 화면으로 보여주는 앱. */
        val SEARCH_PACKAGES = setOf("com.google.android.googlequicksearchbox")
    }

    /**
     * 지금 화면이 검색 결과인가.
     *
     * @param pageUrl 크롬 주소창에서 읽은 원문. 브라우저가 아니면 null
     */
    fun isSearchScreen(pageUrl: String?, packageName: String): Boolean {
        if (packageName in SEARCH_PACKAGES) return true
        val url = pageUrl?.lowercase() ?: return false
        return SEARCH_URL_MARKERS.any { it in url }
    }

    /**
     * 결과 항목들을 뽑는다.
     *
     * @param exclude 이미 다른 레이어가 표시한 영역. 검색 결과 위에 뜨는 광고는
     *                Layer 1·2가 먼저 잡으므로 중복해서 다루지 않는다
     */
    fun extract(
        root: AccessibilityNodeInfo,
        exclude: List<Rect>,
        sourceKey: String
    ): List<Roi> {
        val screen = Rect().also { root.getBoundsInScreen(it) }
        if (screen.width() <= 0 || screen.height() <= 0) return emptyList()

        val out = mutableListOf<Roi>()
        collect(root, 0, screen, exclude, sourceKey, intArrayOf(MAX_NODES), out)
        return out
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        depth: Int,
        screen: Rect,
        exclude: List<Rect>,
        sourceKey: String,
        budget: IntArray,
        out: MutableList<Roi>
    ) {
        if (depth > MAX_DEPTH || out.size >= MAX_RESULTS || budget[0] <= 0) return
        budget[0]--

        if (node.isVisibleToUser && node.isClickable && isResultSized(node, screen)) {
            val rect = Rect().also { node.getBoundsInScreen(it) }
            if (exclude.none { Rect.intersects(it, rect) }) {
                val content = Content()
                gather(node, 0, content)

                // 로그인·검색 입력창이 들어 있는 덩어리는 결과가 아니다
                if (!content.hasEditable) {
                    val text = content.texts.joinToString(" ").trim().take(MAX_TEXT_CHARS)
                    if (text.length >= MIN_TEXT_CHARS) {
                        out.add(
                            Roi(
                                rect = rect,
                                kind = RoiKind.SEARCH_RESULT,
                                shownText = text,
                                shownUrl = content.url.orEmpty(),
                                sourceKey = sourceKey
                            )
                        )
                    }
                }
            }
            // 결과 한 칸을 찾았으면 더 내려가지 않는다 — 안쪽 요소는 같은 결과의 조각이다
            return
        }

        for (i in 0 until node.childCount) {
            collect(node.getChild(i) ?: continue, depth + 1, screen, exclude, sourceKey, budget, out)
            if (out.size >= MAX_RESULTS || budget[0] <= 0) return
        }
    }

    private fun isResultSized(node: AccessibilityNodeInfo, screen: Rect): Boolean {
        val r = Rect().also { node.getBoundsInScreen(it) }
        if (r.width() <= 0 || r.height() <= 0) return false
        return r.width() >= screen.width() * MIN_WIDTH_RATIO &&
            r.height() >= screen.height() * MIN_HEIGHT_RATIO &&
            r.height() <= screen.height() * MAX_HEIGHT_RATIO
    }

    private class Content {
        val texts = mutableListOf<String>()
        var url: String? = null
        var hasEditable = false
    }

    private fun gather(node: AccessibilityNodeInfo, depth: Int, out: Content) {
        if (depth > MAX_DEPTH) return

        if (node.isEditable || node.className?.contains("EditText") == true) {
            out.hasEditable = true
            return
        }

        if (out.url == null) out.url = LinkHarvester.urlOf(node)
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.texts.add(it) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { out.texts.add(it) }

        for (i in 0 until node.childCount) {
            gather(node.getChild(i) ?: continue, depth + 1, out)
            if (out.hasEditable) return
        }
    }
}
