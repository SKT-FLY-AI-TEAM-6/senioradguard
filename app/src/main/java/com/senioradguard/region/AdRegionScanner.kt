package com.senioradguard.region

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 화면 노드 트리를 순회해 공식 광고 표기가 붙은 영역을 찾는다.
 *
 * 팀원 AdDetectService(com.flyai.adalert)에서 이식. 판정 규칙과 상수는 그대로 두고,
 * `feature/universal-ad-detection`의 **예산 기반 중단**만 추가로 들여왔다.
 *
 * ## 왜 예산이 필요한가 — 이게 없으면 테두리가 드래그를 못 따라간다
 * 노드를 하나 읽을 때마다 대상 앱 프로세스로 IPC가 오가고, **그 앱이 바쁘면 호출이
 * 통째로 멈춘다.** 팀원 쪽 실측에서 노드 144개를 읽는 데 11.8초가 걸린 적이 있다
 * (페이지 로딩 중인 크롬). 상한이 없으면 스크롤 도중 스캔 한 번이 수 초를 잡아먹고,
 * 그동안 들어온 이벤트는 전부 밀려 테두리가 옛 자리에 얼어붙는다.
 *
 * 상한을 걸면 대신 "다 못 본 결과"가 나온다. 그 결과로 영역을 갱신하면 아직 화면에
 * 있는 광고의 테두리가 사라지므로, 잘렸다는 사실을 [Result.truncated]로 알려
 * 호출자가 직전 영역을 붙잡을 수 있게 한다.
 */
class AdRegionScanner {

    private companion object {
        /**
         * 팀원 실측값. 크롬의 모바일 뉴스 페이지가 500~1300 노드였고 전부 훑는 데
         * 150~250ms가 걸렸다(S25 Edge). 너무 조이면 광고에 닿기 전에 끊긴다 —
         * 실제로 120ms로 뒀을 때 700 노드에서 잘려 광고를 통째로 놓쳤다.
         */
        const val NODE_BUDGET = 5000
        const val TIME_BUDGET_MS = 400L
        const val MAX_DEPTH = 60
        const val MAX_REGIONS = 5

        /**
         * 조상을 거슬러 올라가는 횟수 상한. `.parent` 하나가 IPC 한 번이라, 상한이
         * 없으면 깊은 웹 문서에서 라벨 하나당 수십 번의 왕복이 생긴다. 예산을 조상
         * 탐색이 전부 써버리면 정작 형제 노드에 있는 광고를 못 본다.
         */
        const val MAX_CLIMB = 12
    }

    /**
     * 스캔 한 번의 결과.
     *
     * 예전에는 `List<Rect>`만 돌려줬는데, 그러면 "광고가 없다"와 "예산이 모자라
     * 못 봤다"가 같은 빈 목록으로 뭉개진다. 둘은 정반대로 처리해야 한다.
     */
    class Result(
        val regions: List<Rect>,
        /** 예산이 모자라 도중에 끊겼는가 — 끊긴 결과로는 영역을 갱신하면 안 된다 */
        val truncated: Boolean,
        val visited: Int,
        val elapsedMs: Long
    )

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

    /** 노드마다 Rect를 새로 만들면 스크롤 한 번에 수천 개가 쌓여 GC가 프레임을 먹는다 */
    private val scratch = Rect()
    private var visited = 0
    private var deadline = 0L
    private var truncated = false

    fun scan(root: AccessibilityNodeInfo): Result {
        val started = SystemClock.uptimeMillis()
        visited = 0
        truncated = false
        deadline = started + TIME_BUDGET_MS

        val screen = Rect().also { root.getBoundsInScreen(it) }
        inBrowser = root.packageName?.toString() in browsers
        val regions = mutableListOf<Rect>()
        collectAdRegions(root, 0, screen, regions)
        // 전체 화면 광고가 하나라도 있으면 전체 테두리 하나만 표시
        regions.firstOrNull { it == screen }?.let { regions.retainAll(listOf(it)) }

        return Result(
            regions = regions,
            truncated = truncated,
            visited = visited,
            elapsedMs = SystemClock.uptimeMillis() - started
        )
    }

    /** 예산이 남았는가. 다 쓴 순간 [truncated]를 세워 호출자가 결과를 신뢰하지 않게 한다. */
    private fun budgetLeft(): Boolean {
        if (visited >= NODE_BUDGET || SystemClock.uptimeMillis() > deadline) {
            truncated = true
            return false
        }
        return true
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
        if (depth > MAX_DEPTH || out.size >= MAX_REGIONS) return
        if (!budgetLeft()) return
        visited++

        // 화면 밖 요소는 릴스 페이저가 좌표를 어긋나게, 크롬이 높이 0으로 접어서 알려주므로
        // 실제로 화면에 크기를 차지할 때만 광고로 인정한다 (안 그러면 가짜 영역이 한도를 채운다)
        if (node.isVisibleToUser) {
            node.getBoundsInScreen(scratch)
            if (scratch.width() > 0 && scratch.height() > 0) {
                // 광고 네트워크 컨테이너는 그 노드 자체가 곧 광고 영역, 라벨은 카드를 거슬러 올라가 찾는다
                val byId = AdLabelRules.isAdContainer(node.viewIdResourceName)
                if (byId || AdLabelRules.isAdLabel("${node.text ?: ""} · ${node.contentDescription ?: ""}")) {
                    val r = when {
                        byId -> Rect(scratch)
                        inBrowser -> adLinkOf(node, screen)
                        else -> containerOf(node, screen)
                    }
                    if (r != null && out.none { it.contains(r) || r.contains(it) }) out.add(r)
                    return
                }
            }
        }
        for (i in 0 until node.childCount) {
            collectAdRegions(node.getChild(i) ?: continue, depth + 1, screen, out)
            if (out.size >= MAX_REGIONS || truncated) return
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
        var up = 0
        while (cur != null && up < MAX_CLIMB) {
            cur.getBoundsInScreen(r)
            if (r.height() > screen.height() * 0.5) return null
            if (cur.isClickable) return Rect(r)
            cur = cur.parent
            up++
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
        var up = 0
        while (cur != null && up < MAX_CLIMB) {
            cur.getBoundsInScreen(r)
            if (cur.isScrollable) return item?.apply { bottom = maxOf(bottom, r.bottom) } ?: Rect(screen)
            // 화면 가장자리에 걸쳐 잘려 보이는 카드는 최소 높이 조건을 면제
            val clipped = r.top <= screen.height() * 0.06 || r.bottom >= screen.height() * 0.94
            if (r.width() >= screen.width() * 0.7 && r.height() <= screen.height() * 0.85 &&
                (r.height() >= screen.height() * 0.08 || clipped)
            ) break
            item = Rect(r)
            cur = cur.parent
            up++
        }
        val region = if (cur != null) Rect(r) else Rect(screen)
        if (region.height() >= screen.height() * 0.75) region.set(screen)
        return region
    }
}
