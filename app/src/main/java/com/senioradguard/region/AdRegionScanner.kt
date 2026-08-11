package com.senioradguard.region

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 화면 노드 트리를 순회해 공식 광고 표기가 붙은 영역을 찾는다.
 *
 * 팀원 AdDetectService(com.flyai.adalert)에서 이식. 로직과 상수는 변경하지 않았다.
 * AccessibilityService 상속에서 분리하기 위해 inBrowser 판정만 scan() 안으로 옮겼다.
 */
class AdRegionScanner {

    /**
     * 모바일 웹은 화면 구조가 앱과 달라 광고 영역을 다른 방식으로 잡는다.
     *
     * 삼성 인터넷은 제외했다 — 웹 콘텐츠를 접근성 트리에 노출하지 않아 감지 자체가
     * 불가능하다(AdGuardAccessibilityService.targetApps 주석 참고).
     * 브라우저를 추가할 때는 이 목록과 targetApps 양쪽에 넣어야 한다. 한쪽만
     * 넣으면 이벤트는 받지만 앱 방식(containerOf)으로 잘못 처리된다.
     */
    private val browsers = setOf("com.android.chrome")
    private var inBrowser = false

    fun scan(root: AccessibilityNodeInfo): List<Rect> {
        val screen = Rect().also { root.getBoundsInScreen(it) }
        inBrowser = root.packageName?.toString() in browsers
        val regions = mutableListOf<Rect>()
        collectAdRegions(root, 0, screen, regions)
        // 전체 화면 광고가 하나라도 있으면 전체 테두리 하나만 표시
        regions.firstOrNull { it == screen }?.let { regions.retainAll(listOf(it)) }
        return regions
    }

    /**
     * 노드 트리를 직접 순회해 공식 광고 표기를 찾고 광고 영역들을 모은다.
     * (유튜브의 Litho UI는 findAccessibilityNodeInfosByText를 지원하지 않아 직접 순회가 필요)
     */
    private fun collectAdRegions(
        node: AccessibilityNodeInfo,
        depth: Int,
        screen: Rect,
        out: MutableList<Rect>
    ) {
        // 인스타그램 릴스는 광고 라벨이 30단계보다 깊이 있어 여유 있게 잡는다
        if (depth > 60 || out.size >= 5) return
        // 화면 밖 요소는 릴스 페이저가 좌표를 어긋나게, 크롬이 높이 0으로 접어서 알려주므로
        // 실제로 화면에 크기를 차지할 때만 광고로 인정한다 (안 그러면 가짜 영역이 한도를 채운다)
        if (node.isVisibleToUser) {
            val b = Rect().also { node.getBoundsInScreen(it) }
            if (b.width() > 0 && b.height() > 0) {
                // 광고 네트워크 컨테이너는 그 노드 자체가 곧 광고 영역, 라벨은 카드를 거슬러 올라가 찾는다
                val byId = AdLabelRules.isAdContainer(node.viewIdResourceName)
                if (byId || AdLabelRules.isAdLabel("${node.text ?: ""} · ${node.contentDescription ?: ""}")) {
                    val r = if (byId) b else if (inBrowser) adLinkOf(node, screen) else containerOf(node, screen)
                    if (r != null && out.none { it.contains(r) || r.contains(it) }) out.add(r)
                    return
                }
            }
        }
        for (i in 0 until node.childCount) {
            collectAdRegions(node.getChild(i) ?: continue, depth + 1, screen, out)
        }
    }

    /**
     * 모바일 웹의 광고는 링크라서, 라벨을 감싼 가장 가까운 클릭 가능한 조상이 광고 한 칸이다.
     * (웹 문서에는 "광고 카드"에 해당하는 구조가 없어 부모를 계속 올라가면 문서 전체를 잡는다)
     * 광고 한 칸이라기엔 너무 큰 곳까지 올라가면 포기한다 — 그런 광고는 대개 id로 따로 잡힌다.
     */
    private fun adLinkOf(marker: AccessibilityNodeInfo, screen: Rect): Rect? {
        val r = Rect()
        var cur: AccessibilityNodeInfo? = marker
        while (cur != null) {
            cur.getBoundsInScreen(r)
            if (r.height() > screen.height() * 0.5) return null
            if (cur.isClickable) return Rect(r)
            cur = cur.parent
        }
        return null
    }

    /**
     * 라벨 노드에서 가장 가까운 광고 카드 컨테이너(폭 70% 이상, 높이 8~85%)를 찾는다.
     * 스크롤 피드 자체는 카드가 아니므로 피드에 닿으면 탐색을 멈추고, 그때까지 카드가 없으면
     * 라벨이 속한 피드 항목부터 피드 아래 끝까지를 광고 영역으로 본다.
     * (인스타그램은 광고 게시물의 헤더·본문이 각각 별도 피드 항목이라 카드 조상이 없음)
     * 카드를 못 찾거나 영역이 화면의 75% 이상이면 전체 화면 광고로 본다.
     */
    private fun containerOf(marker: AccessibilityNodeInfo, screen: Rect): Rect {
        val r = Rect()
        var cur: AccessibilityNodeInfo? = marker   // 병합 노드는 라벨 노드 자신이 곧 광고 카드
        var item: Rect? = null                     // 직전에 지나온 조상 = 피드의 항목
        while (cur != null) {
            cur.getBoundsInScreen(r)
            if (cur.isScrollable) return item?.apply { bottom = maxOf(bottom, r.bottom) } ?: Rect(screen)
            // 화면 가장자리에 걸쳐 잘려 보이는 카드는 최소 높이 조건을 면제
            val clipped = r.top <= screen.height() * 0.06 || r.bottom >= screen.height() * 0.94
            if (r.width() >= screen.width() * 0.7 && r.height() <= screen.height() * 0.85 &&
                (r.height() >= screen.height() * 0.08 || clipped)
            ) break
            item = Rect(r)
            cur = cur.parent
        }
        val region = if (cur != null) Rect(r) else Rect(screen)
        if (region.height() >= screen.height() * 0.75) region.set(screen)
        return region
    }
}
