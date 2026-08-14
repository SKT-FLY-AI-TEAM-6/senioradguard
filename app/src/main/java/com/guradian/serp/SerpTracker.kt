package com.guradian.serp

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 검색 결과 배지가 화면을 따라다니게 만드는 부분. 이 파일이 스크롤 문제의 답이다.
 *
 * ## 설계의 핵심 — 판정과 좌표를 분리한다
 * 처음 구현은 둘을 한 덩어리로 들고 있었다. `evaluate()`가 "이 사각형에 이 판정"을
 * 함께 돌려주고, 화면이 움직이면 그 쌍이 통째로 못 쓰게 됐다. 실기기에서 드러난
 * 두 결함이 **같은 원인**이었다.
 *
 *  - 스크롤하면 배지가 사라짐 → 좌표가 낡아서 판정까지 버림
 *  - 판별기 응답이 100% 버려짐 → 돌아왔을 때 좌표가 이미 달라져 있음
 *
 * 그래서 둘을 갈랐다.
 *
 * ```
 *   판정 = 호스트에 붙는다 (tvhot2.com → 위험)   — SerpRiskEngine 캐시. 화면과 무관
 *   좌표 = 화면에서 매번 새로 읽는다              — 이 클래스의 anchors
 *   그리기 = anchors를 돌면서 호스트로 판정을 조회 — redraw()
 * ```
 *
 * 이렇게 되면 두 결함이 **고쳐지는 게 아니라 존재할 수 없게 된다.**
 * 스크롤하면 좌표만 새로 읽어 다시 그리고(판정은 그대로 있다), 판별기 응답이 5초 뒤에
 * 와도 호스트에 얹으면 그 시점의 좌표에 저절로 붙는다. 화면 세대 비교도 필요 없다 —
 * 늦게 온 응답을 버릴 이유 자체가 없어졌다.
 *
 * ## 두 속도로 돈다
 *  - **위치 갱신**: 이벤트마다, [SCAN_INTERVAL_MS] 스로틀. 트리만 훑고 판별기는 안 부른다
 *  - **판정 갱신**: 화면이 [IDLE_MS] 조용해진 뒤 한 번. 여기서만 규칙·캐시·판별기가 돈다
 *
 * 스크롤 중에도 위치 갱신은 계속 돌므로 배지가 손가락을 따라온다. 그 사이 판별기는
 * 불리지 않으므로 비용도 늘지 않는다.
 *
 * ## 요청을 버리지 않는다
 * 스로틀 구간이나 스캔 진행 중에 들어온 요청은 [trailingScan]으로 미룬다. 버리면
 * 드래그의 마지막 위치를 잃는다 — 손을 뗀 자리에서 배지가 어긋난 채로 멈춘다.
 * (`BorderTracker`가 광고 테두리에서 같은 문제를 같은 방식으로 푼다.)
 */
class SerpTracker(
    private val scanner: SerpScanner,
    private val engine: SerpRiskEngine,
    private val overlay: SerpBadgeOverlay,
    private val scope: CoroutineScope
) {

    companion object {
        /**
         * 위치 갱신 최소 간격. 크롬은 스크롤 중 약 100ms마다 이벤트를 보내므로
         * 그보다 조금 길게 잡아 한 번씩 건너뛴다. 트리 순회가 이보다 오래 걸리면
         * 어차피 [scanning] 플래그가 막는다.
         */
        const val SCAN_INTERVAL_MS = 150L

        /**
         * 화면이 이만큼 조용해지면 판정을 갱신한다. 스크롤 중에 판별기를 부르면
         * 응답이 올 때쯤 그 결과는 화면 밖이다.
         */
        const val IDLE_MS = 700L

        /**
         * 결과를 못 찾은 스캔이 이만큼 연속돼야 배지를 지운다.
         *
         * 한 번이라도 비면 지우게 하면 배지가 깜빡인다 — 크롬은 스크롤·지연 로딩
         * 도중에 트리를 잠깐 비우고, 순회가 예산에 걸려 잘리기도 한다. 그때마다
         * 지우면 **정작 위험한 결과 위에서 경고가 사라진다.**
         */
        const val EMPTY_TOLERANCE = 3

        /** 위치 갱신에서 훑을 노드 수 상한. 판정 갱신은 넉넉히 본다. */
        const val TRACK_NODE_BUDGET = 900
        const val JUDGE_NODE_BUDGET = 1800

        /**
         * 배지가 떠 있는 동안 스스로 다시 확인하는 주기.
         *
         * **이벤트만 믿으면 배지를 못 지운다.** 서비스는 `packageNames`에 적힌 앱의
         * 이벤트만 받는데, 검색 화면을 떠나 가는 곳(런처·잠금화면·다른 앱)은 그
         * 목록에 없다. 그래서 크롬을 닫거나 화면을 껐다 켜면 **지우라고 알려줄
         * 이벤트가 아예 오지 않는다** — 실기기에서 잠금화면 위에 테두리가 그대로
         * 남았다.
         *
         * 이벤트가 끊겨도 주기적으로 화면을 직접 확인하고, 검색 결과가 아니면 지운다.
         * 배지가 떠 있을 때만 도는 타이머라 평소에는 아무 일도 하지 않는다.
         * (`BorderTracker`가 광고 테두리에서 같은 이유로 같은 장치를 쓴다.)
         */
        const val RECHECK_MS = 1000L
    }

    /** 루트 노드를 가져오는 방법. 대상 앱이 아니면 null을 주기로 한다. */
    private var rootProvider: () -> AccessibilityNodeInfo? = { null }

    private val handler = Handler(Looper.getMainLooper())

    /**
     * 스캔 진행 중 플래그. 트리 순회는 수십~수백 ms가 걸리는데 이벤트는 그보다 빨리
     * 들어온다. **겹친 요청을 버리지는 않는다** — [trailingScan]이 다시 예약한다.
     */
    private val scanning = AtomicBoolean(false)

    // ── 메인 스레드에서만 만지는 상태 ──

    private var lastScan = 0L
    private var scanQueued = false

    /** 다음 스캔에서 판정까지 갱신할지. 유휴 타이머가 세운다. */
    private var judgePending = false

    /**
     * 지금 도는 스캔이 시작된 뒤로 화면이 세로로 구른 총량.
     *
     * 스캔 결과는 항상 몇십~몇백 ms 전의 화면이다. 그 사이 사용자가 계속 스크롤했다면
     * 결과를 그대로 그리는 순간 배지가 **뒤로 튄다.** 손가락으로 밀어둔 위치를 스캔이
     * 도로 끌어내리는 셈이다. 결과를 그릴 때 이만큼 되밀어 그 튐을 없앤다.
     */
    private var scrollSinceScanStart = 0

    /** 지금 화면에 있는 결과들의 자리. 스캔할 때마다 통째로 갈린다. */
    private var anchors: List<SerpScanner.Hit> = emptyList()

    /** 결과를 못 찾은 스캔이 연속으로 몇 번인가. */
    private var emptyScans = 0

    /** 마지막으로 읽은 검색어. 판별기에 문맥으로 넘긴다. */
    private var query = ""

    /**
     * 이벤트 하나를 받는다. 메인 스레드.
     *
     * @param root 루트 노드를 주는 함수. **대상 앱이 아니면 null을 줄 것**
     */
    fun onEvent(event: AccessibilityEvent, root: () -> AccessibilityNodeInfo?) {
        rootProvider = root

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // 다른 페이지로 넘어갔다. 이전 화면의 자리는 즉시 버린다 — 남겨두면
            // 새 페이지 위에 옛 배지가 뜬 채로 700ms가 흐른다.
            // **판정 캐시는 지우지 않는다.** 호스트에 붙은 판정은 페이지가 바뀌어도
            // 그대로 유효하고, 뒤로 가기로 돌아오면 곧바로 다시 쓰인다.
            anchors = emptyList()
            engine.reset()
            overlay.clear()
        }

        requestScan()
        scheduleJudge()
    }

    /**
     * 화면이 [deltaY]만큼 굴렀다. **노드를 하나도 읽지 않는다.**
     *
     * 화면이 위로 굴렀으면(deltaY > 0) 결과도 위로 가므로 배지는 -deltaY만큼 움직인다.
     * 이건 다음 스캔이 도착하기 전까지의 임시 보정이고, 정확한 자리는 스캔이 정한다.
     */
    fun onScroll(deltaY: Int) {
        if (deltaY == 0) return
        overlay.offsetBy(-deltaY)
        anchors = anchors.map { it.copy(rect = Rect(it.rect).apply { offset(0, -deltaY) }) }
        scrollSinceScanStart += deltaY
    }

    /** 표시를 전부 지운다. 서비스가 죽거나 인터럽트될 때. */
    fun clear() {
        handler.removeCallbacksAndMessages(null)
        anchors = emptyList()
        overlay.clear()
    }

    // ── 스캔 예약 ────────────────────────────────────────────

    private val trailingScan = Runnable {
        scanQueued = false
        postScan()
    }

    private val judgeTimer = Runnable {
        judgePending = true
        requestScan()
    }

    /**
     * 이벤트가 끊겨도 배지가 아직 유효한지 스스로 확인한다.
     *
     * 판별기는 부르지 않는다(판정 갱신이 아니라 위치·유효성 확인이다). 검색 화면을
     * 벗어났으면 [runScan]의 첫 두 줄에서 [dropAll]로 빠진다.
     */
    private val recheck = Runnable { requestScan() }

    private fun scheduleJudge() {
        handler.removeCallbacks(judgeTimer)
        handler.postDelayed(judgeTimer, IDLE_MS)
    }

    /** 메인 스레드. 스로틀만 하고 실제 순회는 코루틴에 맡긴다. */
    private fun requestScan() {
        val now = SystemClock.uptimeMillis()
        val since = now - lastScan
        if (since >= SCAN_INTERVAL_MS) {
            handler.removeCallbacks(trailingScan)
            scanQueued = false
            postScan()
        } else if (!scanQueued) {
            scanQueued = true
            handler.postDelayed(trailingScan, SCAN_INTERVAL_MS - since)
        }
    }

    private fun postScan() {
        if (scanning.get()) {
            // 이전 스캔이 아직 돌고 있다. 여기서 버리면 드래그의 마지막 위치가
            // 사라지므로 끝난 직후에 다시 돌도록 예약만 해 둔다.
            if (!scanQueued) {
                scanQueued = true
                handler.postDelayed(trailingScan, SCAN_INTERVAL_MS)
            }
            return
        }

        val judge = judgePending
        judgePending = false
        lastScan = SystemClock.uptimeMillis()
        scrollSinceScanStart = 0
        scanning.set(true)
        scope.launch { runScan(judge) }
    }

    // ── 스캔 ────────────────────────────────────────────────

    private suspend fun runScan(judge: Boolean) {
        try {
            val root = rootProvider() ?: return dropAll()
            val pkg = root.packageName?.toString().orEmpty()
            val pageUrl = runCatching { scanner.pageUrlOf(root) }.getOrNull()

            // 검색어는 주소에서, 주소가 없으면(구글 앱) 검색창에서 읽는다.
            // 구글 앱에서는 이 값이 곧 ① 관문의 근거가 된다.
            val typed = scanner.queryOf(pageUrl)
                .ifBlank { runCatching { scanner.queryFromSearchBox(root) }.getOrDefault("") }

            // ① 화면 관문 — 검색 결과가 아니면 여기서 끝. 트리를 훑지 않는다.
            // 크롬은 주소로, 구글 앱은 검색창에 글자가 있는지로 판단한다.
            if (!scanner.isSearchScreen(pageUrl, pkg, hasQuery = typed.isNotBlank())) {
                return dropAll()
            }

            query = typed.ifBlank { query }

            val budget = if (judge) JUDGE_NODE_BUDGET else TRACK_NODE_BUDGET
            val hits = runCatching { scanner.extract(root, budget) }.getOrDefault(emptyList())

            if (hits.isEmpty()) {
                withContext(Dispatchers.Main) {
                    // 히스테리시스 — 한 번 비었다고 지우면 배지가 깜빡인다
                    if (++emptyScans >= EMPTY_TOLERANCE) {
                        handler.removeCallbacks(recheck)
                        anchors = emptyList()
                        overlay.clear()
                    } else {
                        // 아직 붙잡고 있는 중이라도 다시 확인할 약속은 남겨둔다
                        handler.removeCallbacks(recheck)
                        handler.postDelayed(recheck, RECHECK_MS)
                    }
                }
                return
            }

            withContext(Dispatchers.Main) {
                emptyScans = 0
                // 스캔이 도는 동안 더 구른 만큼 되밀어 배지가 뒤로 튀지 않게 한다
                anchors = hits.shiftedBy(-scrollSinceScanStart)
                redraw()
            }

            if (!judge) return

            // ②③④ 관문 — 여기서만 규칙·캐시·판별기가 돈다
            val outcome = engine.evaluate(query, hits.map { it.result })
            Log.i(
                SERP_TAG,
                "serp 검색어='$query' 결과=${hits.size} 판별=${outcome.classifiedHosts.size} " +
                    "생략=${outcome.skippedUnchanged} " +
                    outcome.verdicts.filter { it.grade.isShown }
                        .groupingBy { it.grade.grade }.eachCount()
            )

            // 판정만 갱신하고 좌표는 건드리지 않는다. 응답이 늦게 왔더라도 그 사이
            // 스캔이 갱신해 둔 **지금의** anchors 위에 얹힌다 — 버릴 이유가 없다.
            withContext(Dispatchers.Main) { redraw() }
        } finally {
            scanning.set(false)
        }
    }

    /**
     * 검색 결과 화면이 아니다 — 표시를 전부 걷는다.
     *
     * 조건 없이 지운다. "anchors가 비어 있으면 건너뛴다"로 두면, 스크롤로 anchors만
     * 비워진 채 창이 남아 있는 순간에 지우지 못한다. [SerpBadgeOverlay.clear]는
     * 이미 비어 있으면 아무 일도 하지 않으므로 반복 호출이 싸다.
     */
    private suspend fun dropAll() = withContext(Dispatchers.Main) {
        handler.removeCallbacks(recheck)
        anchors = emptyList()
        overlay.clear()
        engine.reset()
    }

    /**
     * 지금의 자리에 지금 아는 판정을 얹는다. **그리기는 항상 이 함수 하나를 지난다.**
     *
     * 아직 판정이 없는 결과([RiskGrade.UNKNOWN] 포함)는 아무것도 그리지 않는다.
     * 모르는 것에 초록을 칠하지 않기 위해서다 — 판별기가 답하면 다음 [redraw]에서
     * 저절로 나타난다.
     */
    private fun redraw() {
        val marks = anchors.mapNotNull { hit ->
            engine.known(hit.result.host)
                ?.takeIf { it.grade.isShown }
                ?.let { hit.rect to it }
        }
        if (marks.isEmpty()) overlay.clear() else overlay.show(marks)

        // 무언가 그렸으면 스스로 다시 확인할 약속을 잡는다. 화면을 떠나는 순간에는
        // 우리에게 이벤트가 오지 않으므로, 이 타이머가 유일한 지우개다.
        handler.removeCallbacks(recheck)
        if (marks.isNotEmpty()) handler.postDelayed(recheck, RECHECK_MS)
    }

    private fun List<SerpScanner.Hit>.shiftedBy(dy: Int): List<SerpScanner.Hit> =
        if (dy == 0) this
        else map { it.copy(rect = Rect(it.rect).apply { offset(0, dy) }) }
}
