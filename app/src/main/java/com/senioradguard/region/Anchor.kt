package com.senioradguard.region

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 스크롤 중에 좌표만 다시 읽으려고 붙잡아 두는 노드.
 * 팀원 ad_claude_fable(com.flyai.adalert)에서 이식 — 로직 동일.
 *
 * 노드 손잡이만 들고 있으면 안 된다. **크롬은 문서가 바뀌면 접근성 노드 id를 재사용한다.**
 * 그래서 `refresh()`가 성공해도 그 자리에 있는 것이 아까 그 광고가 아니라 기사일 수 있다.
 * 붙잡을 때의 지문을 같이 들고 있다가 매번 확인한다.
 */
class Anchor private constructor(
    private val node: AccessibilityNodeInfo,
    private val signature: String,
    /**
     * 이 노드의 좌표가 곧 광고 영역인가.
     *
     * false면 영역이 합성된 것이라(전체 화면 승격, 피드 항목을 아래로 늘린 것)
     * 노드를 따라 움직이면 안 된다. 그래도 노드는 붙잡아 둔다 — **광고가 아직 화면에
     * 있는지**를 확인할 유일한 수단이기 때문이다. 이게 없으면 릴스를 넘긴 뒤에도
     * 다음 영상 위에 테두리가 남는다.
     */
    val follow: Boolean
) {
    companion object {
        fun of(node: AccessibilityNodeInfo?, follow: Boolean = true): Anchor? =
            node?.let { Anchor(it, sign(it), follow) }

        private fun sign(node: AccessibilityNodeInfo): String =
            "${node.viewIdResourceName}|${node.className}"
    }

    /**
     * 카드 하위 트리의 텍스트를 모은다 — 지문(CardText 키) 재료다.
     * 노드가 죽었거나 텍스트가 하나도 없으면 null.
     * IPC가 노드 수만큼 발생하므로 영역이 새로 나타난 스캔에서만 부른다.
     */
    fun gatherText(maxNodes: Int = 40): List<String>? {
        if (!node.refresh()) return null
        val texts = mutableListOf<String>()
        val descs = mutableListOf<String>()
        var visited = 0
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 15 || visited >= maxNodes) return
            visited++
            n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { texts.add(it) }
            n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { descs.add(it) }
            for (i in 0 until n.childCount) {
                walk(n.getChild(i) ?: continue, depth + 1)
            }
        }
        walk(node, 0)
        // 텍스트 노드가 있으면 그것만 쓴다. 이미지 설명(contentDescription)은
        // 소재 로테이션마다 바뀌어 문구가 같은 광고의 지문을 흔든다
        // (실측: 문구는 같고 이미지만 바뀐 광고가 다른 지문이 돼 연계를 놓쳤다).
        // 이미지 전용 광고만 설명으로 폴백한다.
        return when {
            texts.isNotEmpty() -> texts
            descs.isNotEmpty() -> descs
            else -> null
        }
    }

    /** 지금 좌표. 사라졌거나 **다른 요소로 바뀌었으면** null */
    fun bounds(): Rect? {
        if (!node.refresh()) return null
        if (sign(node) != signature) return null
        // 인스타는 다음 스토리로 넘어간 뒤에도 이전 페이지의 노드를 잠깐 살려 둔다.
        // 좌표는 멀쩡한 값이 그대로 오므로 가시성까지 봐야 한다.
        if (!node.isVisibleToUser) return null
        val r = Rect().also { node.getBoundsInScreen(it) }
        // 크롬은 화면 밖 노드를 높이 0으로 접어서 알려준다
        return if (r.width() > 0 && r.height() > 0) r else null
    }
}
