package com.guradian.agent

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.guradian.rule.BrowserHost

/**
 * Agent1 — 노드 트리에서 판별 후보 카드를 뽑는다.
 *
 * 카드 판정 기준(폭 70% 이상, 높이 8~85%)은 Layer 1의 `containerOf`와 같은 값을
 * 쓴다. 같은 화면을 같은 눈으로 봐야 두 레이어가 같은 카드를 가리킨다.
 *
 * @param browserHost 출처 판정. **Layer 1과 같은 인스턴스를 넘길 것** — 주소창
 *        접힘 폴백이 상태를 들고 있어서, 갈라지면 한쪽만 도메인을 기억한다.
 */
class CandidateExtractor(private val browserHost: BrowserHost = BrowserHost()) {

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
     * @param exclude Layer 1이 이미 광고로 판정한 영역. 겹치는 카드는 후보에서 뺀다
     *                (확정 표시가 있는데 AI 추정을 덧그릴 이유가 없다)
     * @param budgetMs 순회 시간 상한. 0이면 상한 없음.
     *
     * 스크롤 중에는 이 순회가 Layer 1 스캔에 **이어서** 한 번 더 도는 두 번째 트리
     * 순회다(캐시만 보는 경로). 상한이 없으면 느린 앱에서 그 왕복이 통째로 늘어나
     * 확정 테두리까지 손가락을 못 따라온다. 반대로 [광고 찾기]를 눌러 도는 판별
     * 경로는 후보를 빠짐없이 봐야 하므로 상한을 걸지 않는다.
     *
     * 마감 시각을 필드가 아니라 **인자로 들고 내려간다.** 스크롤 경로와 버튼 판별
     * 경로는 서로 다른 코루틴이라 동시에 돌 수 있는데, 필드에 두면 스크롤 쪽이
     * 써넣은 짧은 마감을 버튼 쪽 순회가 이어받아 후보를 0개로 끊어버린다.
     */
    fun extract(
        root: AccessibilityNodeInfo,
        exclude: List<Rect>,
        budgetMs: Long = 0L
    ): List<AdCandidate> {
        val screen = Rect().also { root.getBoundsInScreen(it) }
        if (screen.width() <= 0 || screen.height() <= 0) return emptyList()

        val deadline = if (budgetMs > 0L) SystemClock.uptimeMillis() + budgetMs else 0L
        val sourceKey = sourceKeyOf(root)
        val out = mutableListOf<AdCandidate>()
        collect(root, 0, screen, sourceKey, exclude, deadline, out)
        return out
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        depth: Int,
        screen: Rect,
        sourceKey: String,
        exclude: List<Rect>,
        deadline: Long,
        out: MutableList<AdCandidate>
    ) {
        if (depth > MAX_DEPTH || out.size >= MAX_CANDIDATES) return
        if (deadline > 0L && SystemClock.uptimeMillis() > deadline) return

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
            collect(node.getChild(i) ?: continue, depth + 1, screen, sourceKey, exclude, deadline, out)
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
     * 주소창 판정 자체는 [BrowserHost]로 옮겼다(task 1). 여기 남은 것은 "브라우저가
     * 아니면 패키지명"이라는 폴백뿐이다.
     */
    private fun sourceKeyOf(root: AccessibilityNodeInfo): String =
        browserHost.of(root) ?: root.packageName?.toString() ?: "unknown"
}
