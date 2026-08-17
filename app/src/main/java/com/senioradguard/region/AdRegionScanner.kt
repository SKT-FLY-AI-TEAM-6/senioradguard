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

        /**
         * 실기기(SM-S921N, 크롬 + m.news.nate.com)에서 이 값이 400일 때 **모든
         * 스캔이 예산에 걸려 잘렸다** — `visited=380~450, 402ms`가 연속으로 찍혔고,
         * 같은 페이지 전체가 531 노드였으니 거의 다 와서 끊긴 셈이다. 잘린 결과로는
         * 영역을 갱신하지 않으므로 새 광고 감지가 몇 스캔씩 밀린다.
         *
         * 그래서 700으로 올렸더니 잘림은 사라졌지만 **폰이 눈에 띄게 느려졌다.**
         * 스캔 한 번이 스로틀 간격(200ms)보다 길어져 사실상 쉬지 않고 IPC를 때리게
         * 되고, 그 부담은 그대로 대상 앱(크롬)의 프레임을 먹는다. 감지가 조금 늦는
         * 것보다 폰이 느려지는 쪽이 훨씬 나쁘다.
         *
         * 500은 그 사이다 — 노드당 약 1ms이므로 이 페이지(531)는 대개 완주하면서
         * 스로틀 간격을 크게 넘지 않는다. **이 값을 더 올리지 말 것.** 올리면 렉이
         * 돌아온다. 감지를 더 빠르게 해야 하면 예산이 아니라 순회량을 줄일 것.
         */
        const val TIME_BUDGET_MS = 500L
        const val MAX_DEPTH = 60
        const val MAX_REGIONS = 5

        /**
         * 페이지 주소 다수결 표본으로 삼을 서로 다른 호스트의 상한 (ad_claude_fable 이식).
         * 크롬은 현재 페이지 주소를 어느 노드에도 실어 주지 않고 주소창은 스크롤로
         * 사라지므로, 최상위 문서(iframe 밖) 링크들이 가리키는 호스트의 다수결로 역산한다.
         */
        const val HOST_SAMPLE_LIMIT = 24

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
        /**
         * [regions]와 1:1로 대응하는, 그 좌표를 만들어낸 노드. 스크롤 중에
         * 트리를 다시 훑지 않고 이 노드의 좌표만 다시 읽는 추적에 쓴다
         * (ad_claude_fable에서 이식). 판정 로직은 바꾸지 않았고 노드 참조만
         * 함께 돌려준다.
         */
        val anchors: List<Anchor?>,
        /** 예산이 모자라 도중에 끊겼는가 — 끊긴 결과로는 영역을 갱신하면 안 된다 */
        val truncated: Boolean,
        val visited: Int,
        val elapsedMs: Long,
        /** 링크 다수결로 역산한 현재 페이지 호스트 (강제 이동 안내용, 웹이 아니면 null) */
        val pageHost: String? = null,
        /** 이번 스캔에서 웹 문서 뿌리를 봤는가 */
        val sawWeb: Boolean = false
    )

    /** 영역과 그 근거 노드 한 쌍 (내부용) */
    private class Resolved(val rect: Rect, val anchor: Anchor?)

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
    private val hostCounts = HashMap<String, Int>()
    private var sawWeb = false

    fun scan(root: AccessibilityNodeInfo): Result {
        val started = SystemClock.uptimeMillis()
        visited = 0
        truncated = false
        hostCounts.clear()
        sawWeb = false
        deadline = started + TIME_BUDGET_MS

        val screen = Rect().also { root.getBoundsInScreen(it) }
        inBrowser = root.packageName?.toString() in browsers
        val found = mutableListOf<Resolved>()
        collectAdRegions(root, 0, screen, found, inNestedDoc = false)
        // 전체 화면 광고가 하나라도 있으면 전체 테두리 하나만 표시
        found.firstOrNull { it.rect == screen }?.let { full -> found.retainAll(listOf(full)) }

        return Result(
            regions = found.map { it.rect },
            anchors = found.map { it.anchor },
            truncated = truncated,
            visited = visited,
            elapsedMs = SystemClock.uptimeMillis() - started,
            pageHost = hostCounts.maxByOrNull { it.value }?.key,
            sawWeb = sawWeb
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
        out: MutableList<Resolved>,
        inNestedDoc: Boolean
    ) {
        // 인스타그램 릴스는 광고 라벨이 30단계보다 깊이 있어 여유 있게 잡는다
        if (depth > MAX_DEPTH || out.size >= MAX_REGIONS) return
        if (!budgetLeft()) return
        visited++

        // ── 페이지 호스트 다수결 (ad_claude_fable 이식) ──
        // iframe 안은 남의 문서(광고망 링크가 다수라 표를 오염시킨다) — 최상위 문서
        // 링크만 센다. 화면 밖 링크도 표본이 된다 (주소 추정에는 가시성이 무관).
        var childNested = inNestedDoc
        if (inBrowser) {
            val role = WebNode.role(node)
            if (!sawWeb &&
                (node.className?.toString() == WebNode.WEB_HOST_CLASS || role == "rootWebArea")
            ) {
                sawWeb = true
            }
            if (!inNestedDoc && role?.lowercase() == "link") {
                WebNode.targetHost(node)?.let { h ->
                    if (hostCounts.size < HOST_SAMPLE_LIMIT || hostCounts.containsKey(h)) {
                        hostCounts.merge(h, 1, Int::plus)
                    }
                }
            }
            if (role == "iframe" || role == "iframePresentational") childNested = true
        }

        // 화면 밖 요소는 릴스 페이저가 좌표를 어긋나게, 크롬이 높이 0으로 접어서 알려주므로
        // 실제로 화면에 크기를 차지할 때만 광고로 인정한다 (안 그러면 가짜 영역이 한도를 채운다)
        if (node.isVisibleToUser) {
            node.getBoundsInScreen(scratch)
            if (scratch.width() > 0 && scratch.height() > 0) {
                // 광고 네트워크 컨테이너는 그 노드 자체가 곧 광고 영역, 라벨은 카드를 거슬러 올라가 찾는다
                val byId = AdLabelRules.isAdContainer(node.viewIdResourceName)
                if (byId || AdLabelRules.isAdLabel("${node.text ?: ""} · ${node.contentDescription ?: ""}")) {
                    val r = when {
                        byId -> Resolved(Rect(scratch), Anchor.of(node))
                        inBrowser -> adLinkOf(node, screen)
                        else -> containerOf(node, screen)
                    }
                    if (r != null && out.none { overlaps(it.rect, r.rect) }) {
                        out.add(r)
                    }
                    return
                }
            }
        }
        for (i in 0 until node.childCount) {
            collectAdRegions(node.getChild(i) ?: continue, depth + 1, screen, out, childNested)
            if (out.size >= MAX_REGIONS || truncated) return
        }
    }

    /**
     * 같은 광고를 두 경로(컨테이너 id, 라벨)가 조금 다른 좌표로 잡으면 테두리가
     * 두 겹이 된다(실사용 리포트). 완전 포함이 아니어도 작은 쪽의 절반 이상이
     * 겹치면 같은 광고로 본다.
     */
    private fun overlaps(a: Rect, b: Rect): Boolean {
        val inter = Rect()
        if (!inter.setIntersect(a, b)) return false
        val interArea = inter.width().toLong() * inter.height()
        val smaller = minOf(a.width().toLong() * a.height(), b.width().toLong() * b.height())
        return smaller > 0 && interArea * 2 >= smaller
    }

    /**
     * 모바일 웹의 광고는 링크라서, 라벨을 감싼 가장 가까운 클릭 가능한 조상이 광고 한 칸이다.
     * (웹 문서에는 "광고 카드"에 해당하는 구조가 없어 부모를 계속 올라가면 문서 전체를 잡는다)
     * 광고 한 칸이라기엔 너무 큰 곳까지 올라가면 포기한다 — 그런 광고는 대개 id로 따로 잡힌다.
     */
    private fun adLinkOf(marker: AccessibilityNodeInfo, screen: Rect): Resolved? {
        val r = Rect()
        var cur: AccessibilityNodeInfo? = marker
        var up = 0
        var fallback: Resolved? = null
        while (cur != null && up < MAX_CLIMB) {
            cur.getBoundsInScreen(r)
            if (r.height() > screen.height() * 0.5) break
            if (cur.isClickable) return Resolved(Rect(r), Anchor.of(cur))
            // 클릭 플래그 없는 iframe 배너(구글 디스플레이·쿠팡 파트너스 등)를 위한
            // 대비 — 광고 한 칸처럼 생긴 조상을 기억해 둔다. 실측(연합뉴스TV 기사):
            // iframe 광고는 클릭을 JS로 받아 어느 조상에도 isClickable이 없어,
            // AD 라벨을 찾고도 영역을 통째로 버리고 있었다.
            if (r.width() >= screen.width() * 0.25 && r.height() >= screen.height() * 0.04) {
                fallback = Resolved(Rect(r), Anchor.of(cur))
            }
            cur = cur.parent
            up++
        }
        return fallback
    }

    /**
     * 라벨 노드에서 가장 가까운 광고 카드 컨테이너(폭 70% 이상, 높이 8~85%)를 찾는다.
     * 스크롤 피드 자체는 카드가 아니므로 피드에 닿으면 탐색을 멈추고, 그때까지 카드가 없으면
     * 라벨이 속한 피드 항목부터 피드 아래 끝까지를 광고 영역으로 본다.
     * (인스타그램은 광고 게시물의 헤더·본문이 각각 별도 피드 항목이라 카드 조상이 없음)
     * 카드를 못 찾거나 영역이 화면의 75% 이상이면 전체 화면 광고로 본다.
     *
     * 합성 좌표(전체 화면 승격, 피드 항목을 늘린 것)는 노드를 따라 움직이면 안 되므로
     * follow=false 앵커에 라벨 노드를 담는다 — 광고가 사라졌는지 확인할 유일한 수단이다.
     */
    private fun containerOf(marker: AccessibilityNodeInfo, screen: Rect): Resolved {
        val r = Rect()
        var cur: AccessibilityNodeInfo? = marker   // 병합 노드는 라벨 노드 자신이 곧 광고 카드
        var item: Rect? = null                     // 직전에 지나온 조상 = 피드의 항목
        var card: AccessibilityNodeInfo? = null    // 카드로 확정된 노드 (스크롤 추적에 쓴다)
        var up = 0
        while (cur != null && up < MAX_CLIMB) {
            cur.getBoundsInScreen(r)
            if (cur.isScrollable) {
                return Resolved(
                    item?.apply { bottom = maxOf(bottom, r.bottom) } ?: Rect(screen),
                    Anchor.of(marker, follow = false)
                )
            }
            // 화면 가장자리에 걸쳐 잘려 보이는 카드는 최소 높이 조건을 면제
            val clipped = r.top <= screen.height() * 0.06 || r.bottom >= screen.height() * 0.94
            if (r.width() >= screen.width() * 0.7 && r.height() <= screen.height() * 0.85 &&
                (r.height() >= screen.height() * 0.08 || clipped)
            ) {
                card = cur
                break
            }
            item = Rect(r)
            cur = cur.parent
            up++
        }
        val region = if (cur != null) Rect(r) else Rect(screen)
        if (region.height() >= screen.height() * 0.75) {
            region.set(screen)
            return Resolved(region, Anchor.of(marker, follow = false))
        }
        return Resolved(region, Anchor.of(card))
    }
}
