package com.guradian.overlay

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.guradian.rule.RuleEngine
import com.guradian.service.GuardianAccessibilityService.Companion.TAG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 스캔 주기 관리 · 스크롤 추종 · 히스테리시스. — task 1
 *
 * 원본 senioradguard에서는 이 장치들이 접근성 서비스 안에 흩어져 있었다. 서비스가
 * "레이어 배분만" 한다는 원래 의도대로 얇아지도록 통째로 이 클래스로 뽑았다.
 * **상수와 로직은 한 글자도 바꾸지 않았다.**
 *
 * ## 테두리는 스캔을 기다리지 않는다 — 두 개의 속도
 * 트리 순회는 아무리 조여도 150~250ms가 걸린다. 여기에 스로틀이 얹히면 광고가
 * 움직인 뒤 테두리가 따라오기까지 수백 ms인데, 60fps는 16.7ms다. **스캔 주기를
 * 손보는 방식으로는 원리적으로 스무스해질 수 없다.**
 *
 *   - **빠른 쪽 ([onScroll])** — 노드를 하나도 읽지 않고 이미 그려둔 테두리를
 *     스크롤량만큼 즉시 민다. layoutParams만 고치므로 프레임 단위로 붙는다.
 *   - **느린 쪽 (스로틀된 스캔)** — 진짜 좌표를 찾아 추정치를 보정한다. 결과는 항상
 *     몇백 ms 전의 화면이므로 [scrollSinceScanStart]만큼 되밀어 그린다. 이 보정이
 *     없으면 스캔이 끝날 때마다 테두리가 뒤로 튀어 오히려 더 어지럽다.
 *
 * ## 느린 쪽을 제 시간에 끝나게 하는 다섯 장치
 * 하나라도 빠지면 보정이 제때 못 와서 테두리가 옛 자리에 얼어붙거나 깜빡인다.
 *
 *  1. **TYPE_VIEW_SCROLLED 구독** (서비스 설정) — 이게 없으면 순수 스크롤에서
 *     CONTENT_CHANGED가 아예 안 오기도 한다. 가장 큰 원인이었다.
 *  2. **트레일링 스로틀** — 스캔 중에 들어온 요청을 *버리지 않고* 뒤로 미룬다.
 *     버리면 드래그의 마지막 위치가 통째로 사라져 손을 뗀 자리에 테두리가 안 온다.
 *  3. **스캔 예산** — [com.guradian.rule.AdRegionScanner] 주석 참고.
 *  4. **잘린 결과 홀드** — 끊긴 스캔으로는 영역을 갱신하지 않고 직전 영역을 최대
 *     [MAX_TRUNCATED_HOLDS]번 붙잡는다. 무한정 붙잡으면 이미 사라진 광고의
 *     테두리가 영영 남는다.
 *  5. **히스테리시스** — 나타날 때는 즉시, 사라질 때는 [CLEAR_DELAY_MS] 기다린다.
 *     스크롤 중에는 노드가 한 프레임 사라졌다 곧바로 돌아오는 일이 잦은데, 그때마다
 *     지웠다 그리면 테두리가 깜빡인다.
 *
 * **주사율은 아직 맞추지 않았다.** 실험 기기 3종의 주사율이 달라 지금 조정하면
 * 한 대 기준으로만 맞는다. 아래 상수는 최종본 이후에 손댈 것.
 */
class BorderTracker(
    private val overlay: AdBorderOverlay,
    private val engine: RuleEngine,
    private val scope: CoroutineScope
) {

    /**
     * 스캔이 끝날 때마다 불린다. Layer 2가 **캐시만 보는 경로**를 여기에 건다 —
     * 판별한 카드는 스크롤해도 점선이 따라와야 하기 때문이다. 반환한 영역은
     * 확정 테두리와 같은 되밀기 보정을 받아 AI_GUESS로 그려진다.
     *
     * 서비스가 아니라 여기에 거는 이유: 되밀기 보정값([scrollSinceScanStart])이
     * 이 클래스 안에만 있어서, 밖에서 그리면 점선만 뒤로 튄다.
     */
    var onScanComplete: (suspend (root: AccessibilityNodeInfo, confirmed: List<Rect>) -> List<Rect>)? = null

    /** 스캔 코루틴이 읽고 메인 스레드가 쓴다. Layer 2와 액션바가 읽는다. */
    @Volatile
    var confirmedRegions: List<Rect> = emptyList()
        private set

    /**
     * 화면이 바뀔 때마다 증가. Layer 2는 왕복에 수 초가 걸릴 수 있어, 결과가
     * 돌아왔을 때 화면이 이미 넘어갔으면 표시하지 않는다.
     */
    @Volatile
    var generation: Int = 0
        private set

    private val handler = Handler(Looper.getMainLooper())

    /**
     * 스캔 진행 중 플래그. 트리 순회는 수십~수백 ms가 걸릴 수 있는데 이벤트는 그보다
     * 빨리 들어오므로 겹쳐 돌리지 않는다. AdRegionScanner가 inBrowser·예산 상태를
     * 들고 있어 동시 실행도 안전하지 않다.
     *
     * 단 **겹친 요청을 버리지는 않는다** — [postScan]이 트레일링으로 다시 예약한다.
     */
    private val scanning = AtomicBoolean(false)

    /** 루트 노드를 가져오는 방법. 대상 앱이 아니면 null을 주기로 한다. */
    private var rootProvider: () -> AccessibilityNodeInfo? = { null }

    // ── 스캔 주기 관리 — 전부 메인 스레드에서만 만진다 ──

    private var lastScan = 0L
    private var scanQueued = false

    /**
     * 지금 도는 스캔이 시작된 뒤로 화면이 세로로 구른 총량.
     *
     * 스캔 결과는 항상 몇백 ms 전의 화면이다. 그 사이에 사용자가 계속 스크롤했다면
     * 결과를 그대로 그리는 순간 테두리가 **뒤로 튄다.** 손가락으로 밀어둔 위치를
     * 스캔이 도로 끌어내리는 셈이라, 즉시 이동을 넣어도 오히려 더 어지러워진다.
     * 결과를 그릴 때 이만큼 되밀어 그 튐을 없앤다.
     *
     * 쓰기(스크롤 이벤트)·초기화(스캔 발사)·읽기(결과 반영)가 전부 메인 스레드라
     * 동기화가 필요 없다.
     */
    private var scrollSinceScanStart = 0

    /**
     * 스로틀 구간이나 스캔 진행 중에 들어온 요청을 버리지 않고 뒤로 미룬다.
     * 버리면 (a) 드래그의 마지막 위치를 잃고 (b) 광고를 지연 로드하는 사이트에서
     * 광고 삽입 이벤트가 드롭 구간에 떨어지면 그 화면은 영영 재스캔되지 않는다.
     */
    private val trailingScan = Runnable {
        scanQueued = false
        postScan()
    }

    /** 페이지가 뜬 뒤 광고를 나중에 끼워 넣는(지연 로드) 사이트를 위한 재스캔 */
    private val lazyRescan = Runnable { requestScan() }

    /**
     * 이벤트가 끊겨도 광고가 아직 화면에 있는지 직접 재확인하고, 사라졌을 때만 해제.
     * packageNames로 대상 앱 이벤트만 받으므로, 다른 앱으로 나갔을 때 테두리를 지우는
     * 것도 이 재확인이 담당한다.
     */
    private val recheck = Runnable { postScan() }

    // ── 스캔 코루틴 안에서만 만지는 상태 (scanning 플래그가 직렬화를 보장) ──

    /** 스캔이 잘려서 직전 영역을 붙잡고 있은 횟수 */
    private var truncatedHolds = 0

    /** 광고가 안 보이기 시작한 시각. 0이면 지금 보이는 중 */
    private var emptySince = 0L

    /**
     * 이벤트 하나를 받는다. 메인 스레드.
     *
     * @param root 루트 노드를 주는 함수. **대상 앱이 아니면 null을 줄 것** —
     *        그 판정(targetApps)은 서비스의 몫으로 남긴다.
     */
    fun onEvent(event: AccessibilityEvent, root: () -> AccessibilityNodeInfo?) {
        rootProvider = root

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // 페이지가 새로 떴다. 광고를 나중에 끼워 넣는 사이트를 위해 재스캔을 예약해 둔다.
            handler.removeCallbacks(lazyRescan)
            for (d in LAZY_RESCAN_MS) handler.postDelayed(lazyRescan, d)
        }

        requestScan()
    }

    /**
     * 화면이 [deltaY]만큼 굴렀다. 노드를 하나도 읽지 않는다.
     *
     * 화면이 위로 굴렀으면(deltaY > 0) 광고도 위로 가므로 테두리는 -deltaY만큼 움직인다.
     */
    fun onScroll(deltaY: Int) {
        if (deltaY == 0) return
        overlay.offsetBy(-deltaY)
        // 지금 도는 스캔은 이만큼 구르기 *전* 화면을 읽고 있다. 결과가
        // 돌아왔을 때 그대로 그리면 테두리가 뒤로 튄다 — 보정에 쓴다.
        scrollSinceScanStart += deltaY
    }

    /** 표시를 전부 지운다. 서비스가 죽거나 인터럽트될 때. */
    fun clear() {
        handler.removeCallbacksAndMessages(null)
        apply(emptyList(), emptyList())
    }

    /**
     * 메인 스레드. 스로틀만 하고 실제 순회는 코루틴에 맡긴다.
     * 스로틀 구간에 들어온 요청은 버리지 않고 [trailingScan]으로 미룬다.
     */
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
            // 통째로 사라지므로 끝난 직후에 다시 돌도록 예약만 해 둔다.
            if (!scanQueued) {
                scanQueued = true
                handler.postDelayed(trailingScan, SCAN_INTERVAL_MS)
            }
            return
        }
        lastScan = SystemClock.uptimeMillis()
        handler.removeCallbacks(recheck)
        if (!scanning.compareAndSet(false, true)) return

        // 지금부터의 스크롤이 이 스캔의 "밀린 만큼"이 된다
        scrollSinceScanStart = 0

        scope.launch {
            try {
                scanWork()
            } catch (e: Throwable) {
                Log.w(TAG, "scan failed", e)
            } finally {
                scanning.set(false)
            }
        }
    }

    /**
     * 백그라운드. 트리를 읽기만 하고 화면은 건드리지 않는다.
     *
     * rootInActiveWindow도 여기서 부른다 — 이것 자체가 IPC라 메인 스레드에서 부르면
     * 대상 앱이 바쁠 때 그만큼 UI가 멈춘다.
     */
    private suspend fun scanWork() {
        val root = rootProvider()
        if (root == null) {
            // 대상 앱을 벗어났다. 여기는 히스테리시스를 거치지 않고 바로 지운다 —
            // 다른 앱 화면에 이전 앱의 테두리가 남아 있으면 그게 오탐이다.
            truncatedHolds = 0
            emptySince = 0L
            withContext(Dispatchers.Main) { apply(emptyList(), emptyList()) }
            return
        }

        val result = engine.scan(root)
        if (result.truncated) {
            Log.d(TAG, "scan truncated: visited=${result.visited} ${result.elapsedMs}ms")
        }

        // 예산이 모자라 도중에 끊긴 결과로는 영역을 갱신하지 않고 직전 영역을 유지한다.
        // 부분적으로만 훑은 화면에서 영역을 갱신하면 그 자체가 오탐이 되기 때문이다.
        //
        // 다만 무한정 유지하면 안 된다. 무거운 페이지에서 스캔이 계속 잘리면 이미 사라진
        // 광고의 테두리가 영영 남는다. 몇 번까지만 붙잡고 그 뒤에는 부분 결과를 받아들인다.
        val shown = confirmedRegions
        val regions =
            if (result.truncated && shown.isNotEmpty() && truncatedHolds < MAX_TRUNCATED_HOLDS) {
                truncatedHolds++
                shown
            } else {
                truncatedHolds = 0
                result.regions
            }

        // 히스테리시스 — 나타날 때는 즉시, 사라질 때는 잠깐 기다린다.
        val stable = when {
            regions.isNotEmpty() -> {
                emptySince = 0L
                regions
            }
            shown.isEmpty() -> regions              // 원래 없었으면 그대로 없음
            else -> {
                val now = SystemClock.uptimeMillis()
                if (emptySince == 0L) emptySince = now
                if (now - emptySince < CLEAR_DELAY_MS) shown    // 아직 기다린다
                else {
                    emptySince = 0L
                    regions
                }
            }
        }

        // Layer 2의 캐시 전용 경로. 판별기를 부르지 않으므로 화면이 바뀔 때마다 돌려도
        // 되고, 그래야 점선이 카드를 따라다닌다. 걸려 있지 않으면 아무 일도 없다.
        val guessed = runCatching { onScanComplete?.invoke(root, stable) }
            .getOrNull().orEmpty()

        withContext(Dispatchers.Main) { apply(stable, guessed) }
    }

    private fun apply(confirmed: List<Rect>, guessed: List<Rect>) {
        handler.removeCallbacks(recheck)
        if (confirmed.isNotEmpty() || guessed.isNotEmpty()) handler.postDelayed(recheck, RECHECK_MS)

        // 스캔이 도는 동안 굴러간 만큼 결과를 되민다 (안 그러면 테두리가 뒤로 튄다)
        val dy = -scrollSinceScanStart
        val shifted = confirmed.shiftedBy(dy)
        overlay.show(AdMarkStyle.CONFIRMED, shifted)
        overlay.show(AdMarkStyle.AI_GUESS, guessed.shiftedBy(dy))

        confirmedRegions = shifted
        generation++
    }

    private fun List<Rect>.shiftedBy(dy: Int): List<Rect> =
        if (dy == 0 || isEmpty()) this else map { Rect(it).apply { offset(0, dy) } }

    private companion object {
        /**
         * 스캔 최소 간격. 스크롤 중에는 이벤트가 초당 수십 번 온다.
         * 이 구간에 들어온 요청은 버리지 않고 트레일링으로 미룬다.
         */
        const val SCAN_INTERVAL_MS = 200L

        /** 이벤트가 끊겨도 광고가 아직 화면에 있는지 직접 확인하는 주기 */
        const val RECHECK_MS = 1000L

        /** 광고가 안 보인다고 판단해도 이만큼은 테두리를 유지한다 (깜빡임 방지) */
        const val CLEAR_DELAY_MS = 700L

        /** 스캔이 잘렸을 때 직전 영역을 몇 번까지 붙잡고 있을지 */
        const val MAX_TRUNCATED_HOLDS = 3

        /**
         * 페이지가 뜬 뒤 광고를 나중에 끼워 넣는 사이트를 위한 재스캔 시각.
         * 이게 없으면 광고 삽입 이벤트가 스로틀 구간에 떨어졌을 때 그 화면은
         * 사용자가 다시 스크롤할 때까지 영원히 재스캔되지 않는다.
         */
        val LAZY_RESCAN_MS = longArrayOf(600, 1800, 3500)
    }
}
