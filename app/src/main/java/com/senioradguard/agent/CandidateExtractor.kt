package com.senioradguard.agent

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Agent1 — 노드 트리에서 판별 후보 카드를 뽑는다.
 *
 * 카드 판정 기준(폭 70% 이상, 높이 8~85%)은 Layer 1의 containerOf와 같은 값을
 * 쓴다. 같은 화면을 같은 눈으로 봐야 두 레이어가 같은 카드를 가리킨다.
 */
class CandidateExtractor {

    private companion object {
        const val MAX_DEPTH = 60
        const val MAX_CANDIDATES = 15

        const val MIN_WIDTH_RATIO = 0.7
        const val MIN_HEIGHT_RATIO = 0.08
        const val MAX_HEIGHT_RATIO = 0.85

        /** 이보다 짧은 카드는 판별할 근거가 없어 건너뛴다. */
        const val MIN_TEXT_CHARS = 4
    }

    /**
     * 마지막으로 주소창에서 읽은 도메인. 주소창이 접혀 사라졌을 때 쓴다.
     * 브라우저가 아닌 앱으로 넘어가면 지운다 — 다른 앱 카드에 남의 도메인이
     * 붙으면 캐시가 뒤섞인다.
     */
    private var lastBrowserHost: String? = null

    /**
     * @param exclude Layer 1이 이미 광고로 판정한 영역. 겹치는 카드는 후보에서 뺀다
     *                (확정 표시가 있는데 AI 추정을 덧그릴 이유가 없다)
     */
    fun extract(root: AccessibilityNodeInfo, exclude: List<Rect>): List<AdCandidate> {
        val screen = Rect().also { root.getBoundsInScreen(it) }
        if (screen.width() <= 0 || screen.height() <= 0) return emptyList()

        val sourceKey = sourceKeyOf(root)
        val out = mutableListOf<AdCandidate>()
        collect(root, 0, screen, sourceKey, exclude, out)
        return out
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        depth: Int,
        screen: Rect,
        sourceKey: String,
        exclude: List<Rect>,
        out: MutableList<AdCandidate>
    ) {
        if (depth > MAX_DEPTH || out.size >= MAX_CANDIDATES) return

        if (node.isVisibleToUser && isCard(node, screen)) {
            val rect = Rect().also { node.getBoundsInScreen(it) }
            // 확정 표시와 겹치면 이 가지 전체를 버린다 — 안쪽 카드도 같은 광고다
            if (exclude.any { Rect.intersects(it, rect) }) return

            val content = Content()
            gather(node, 0, content)

            // 로그인·결제 폼일 수 있는 카드는 외부로 보내지 않는다
            if (content.hasEditable) return

            val joined = content.texts.joinToString(" ").trim()
            if (joined.length >= MIN_TEXT_CHARS) {
                out.add(AdCandidate(rect, content.texts.toList(), content.viewIds.toList(), sourceKey))
            }
            // 카드를 찾았으면 더 내려가지 않는다 (카드 안의 카드는 같은 광고의 조각)
            return
        }

        for (i in 0 until node.childCount) {
            collect(node.getChild(i) ?: continue, depth + 1, screen, sourceKey, exclude, out)
        }
    }

    private fun isCard(node: AccessibilityNodeInfo, screen: Rect): Boolean {
        val r = Rect().also { node.getBoundsInScreen(it) }
        if (r.width() <= 0 || r.height() <= 0) return false
        return r.width() >= screen.width() * MIN_WIDTH_RATIO &&
            r.height() >= screen.height() * MIN_HEIGHT_RATIO &&
            r.height() <= screen.height() * MAX_HEIGHT_RATIO
    }

    private class Content {
        val texts = mutableListOf<String>()
        val viewIds = mutableListOf<String>()
        var hasEditable = false
    }

    /** 카드 하위 트리에서 텍스트·id를 모으고 입력 필드 유무를 확인한다. */
    private fun gather(node: AccessibilityNodeInfo, depth: Int, out: Content) {
        if (depth > MAX_DEPTH) return

        if (node.isEditable || node.className?.contains("EditText") == true) {
            out.hasEditable = true
            return
        }

        node.viewIdResourceName?.let { out.viewIds.add(it) }
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.texts.add(it) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { out.texts.add(it) }

        for (i in 0 until node.childCount) {
            gather(node.getChild(i) ?: continue, depth + 1, out)
            if (out.hasEditable) return
        }
    }

    /**
     * 캐시 키의 앞부분. 브라우저는 주소창에서 도메인을 읽고, 앱은 패키지명을 쓴다.
     * 도메인을 쓰는 이유는 같은 사이트의 같은 광고가 재방문 때 캐시에 걸리게 하기
     * 위함이다 — 크롬 하나로 묶으면 사이트가 달라도 같은 키를 공유해 오염된다.
     *
     * Layer 1 경로(보호자 기록의 중복 제거)도 같은 키를 써야 해서 공개해 둔다.
     * 노드 한 개(주소창)만 조회하므로 스캔마다 불러도 부담이 없다.
     */
    fun sourceKeyOf(root: AccessibilityNodeInfo): String {
        val pkg = root.packageName?.toString() ?: return "unknown"
        if (pkg != "com.android.chrome") {
            lastBrowserHost = null
            return pkg
        }

        val urlBar = root.findAccessibilityNodeInfosByViewId("com.android.chrome:id/url_bar")
            ?.firstOrNull()
        val shown = urlBar?.text?.toString()?.trim().orEmpty()

        // 크롬은 아래로 스크롤하면 주소창을 접어 트리에서 사라지게 한다. 그때
        // 패키지명으로 물러나면 같은 페이지가 스크롤 위치에 따라 두 개의 캐시 키를
        // 갖게 되고, 게다가 모든 사이트가 "com.android.chrome" 키를 공유해 서로의
        // 판정에 오염된다. 마지막으로 본 도메인을 기억해 그 구멍을 메운다.
        if (shown.isEmpty()) return lastBrowserHost ?: pkg

        // 주소창은 "hankyung.com/article/..." 처럼 스킴 없이 보여준다
        val host = shown.removePrefix("https://").removePrefix("http://")
            .substringBefore('/')
            .removePrefix("www.")
        if (!host.contains('.')) return lastBrowserHost ?: pkg

        lastBrowserHost = host
        return host
    }
}
