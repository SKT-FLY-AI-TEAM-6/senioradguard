package com.senioradguard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.senioradguard.BuildConfig
import com.senioradguard.agent.AdClassifier
import com.senioradguard.agent.AgentPipeline
import com.senioradguard.agent.CandidateExtractor
import com.senioradguard.agent.GeminiClassifier
import com.senioradguard.agent.StubClassifier
import com.senioradguard.detector.db.AppDatabase
import com.senioradguard.guard.InstallGuard
import com.senioradguard.overlay.AdBorderOverlay
import com.senioradguard.overlay.AdMarkStyle
import com.senioradguard.overlay.OverlayManager
import com.senioradguard.region.AdRegionScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 단일 진입점. 이벤트를 받아 루트 노드를 한 번만 가져오고 각 레이어에 배분한다.
 *
 *   Layer 1  공식 광고 라벨 감지  → 비차단 테두리 (AdBorderOverlay)
 *   Layer 2  LLM 판별            → 캐시 표시는 즉시, 새 판별은 유휴 때만
 *   Layer 3  설치 유도 감지       → 차단 경고 (InstallGuard)
 *
 * ## 테두리는 스캔을 기다리지 않는다 — 두 개의 속도
 * 트리 순회는 아무리 조여도 150~250ms가 걸린다. 여기에 스로틀이 얹히면 광고가
 * 움직인 뒤 테두리가 따라오기까지 수백 ms인데, 60fps는 16.7ms다. **스캔 주기를
 * 손보는 방식으로는 원리적으로 스무스해질 수 없다.**
 *
 * 그래서 두 갈래로 나눈다.
 *
 *   - **빠른 쪽 (매 스크롤 이벤트)** — 노드를 하나도 읽지 않고 이미 그려둔 테두리를
 *     [AccessibilityEvent.getScrollDeltaY]만큼 즉시 민다. layoutParams만 고치므로
 *     프레임 단위로 붙는다. 다음 스캔까지 버티는 추정치다.
 *   - **느린 쪽 (스로틀된 스캔)** — 진짜 좌표를 찾아 추정치를 보정한다. 결과는 항상
 *     몇백 ms 전의 화면이므로 [scrollSinceScanStart]만큼 되밀어 그린다. 이 보정이
 *     없으면 스캔이 끝날 때마다 테두리가 뒤로 튀어 오히려 더 어지럽다.
 *
 * ## 아래는 그 느린 쪽을 제 시간에 끝나게 하는 장치들 (ad-alert-android 이식)
 * 팀원 저장소 `feature/universal-ad-detection`에서 그대로 들여왔다. 하나라도 빠지면
 * 보정이 제때 못 와서 테두리가 옛 자리에 얼어붙거나 깜빡인다.
 *
 *  1. **TYPE_VIEW_SCROLLED 구독** — 이게 없으면 스크롤은 이벤트를 거의 만들지 않는다.
 *     콘텐츠가 바뀌지 않는 순수 스크롤(같은 카드가 위로 밀려 올라감)에서는
 *     CONTENT_CHANGED가 아예 안 오기도 한다. 가장 큰 원인이었다.
 *  2. **트레일링 스로틀** — 스캔 중에 들어온 요청을 *버리지 않고* 뒤로 미룬다.
 *     버리면 드래그의 마지막 위치가 통째로 사라져 손을 뗀 자리에 테두리가 안 온다.
 *  3. **스캔 예산** — 순회에 상한을 둬 한 번의 스캔이 수 초를 잡아먹지 않게 한다
 *     ([AdRegionScanner] 주석 참고).
 *  4. **잘린 결과 홀드** — 예산이 모자라 끊긴 스캔으로는 영역을 갱신하지 않고 직전
 *     영역을 최대 [MAX_TRUNCATED_HOLDS]번 붙잡는다. 무한정 붙잡으면 이미 사라진
 *     광고의 테두리가 영영 남는다.
 *  5. **히스테리시스** — 나타날 때는 즉시, 사라질 때는 [CLEAR_DELAY_MS] 기다린다.
 *     스크롤 중에는 노드가 한 프레임 사라졌다 곧바로 돌아오는 일이 잦은데, 그때마다
 *     지웠다 그리면 테두리가 깜빡이고 알림음까지 다시 울린다.
 */
class AdGuardAccessibilityService : AccessibilityService() {

    private val targetApps = setOf(
        "com.google.android.youtube",   // 유튜브
        "com.instagram.android",        // 인스타그램
        "com.towneers.www",             // 당근
        "com.android.chrome"            // 크롬 (모바일 웹)
        // 삼성 인터넷(com.sec.android.app.sbrowser)은 제외한다.
        // 실기기 A/B 검증 결과 렌더링된 웹 페이지를 접근성 트리에 전혀 노출하지
        // 않는다 — 같은 URL에서 크롬은 노드 421개에 본문 텍스트가 나오는 반면
        // 삼성 인터넷은 노드 20개(주소창·버튼 등 UI 껍데기)에 텍스트가 0개다.
        // 볼 수 있는 정보가 없어 로직으로는 해결이 불가능하고, 목록에 두면
        // (1) 보호받는다는 오해를 주고 (2) 아무것도 못 찾을 트리를 계속 순회한다.
        // 다른 브라우저를 추가할 때도 이 방법으로 먼저 노출 여부를 확인할 것.
    )

    /**
     * Layer 3이 감시할 스토어. 시스템 이벤트 필터(packageNames)에 넣기 위한 목록이고,
     * 실제 판정은 InstallGuard.isStorePackage가 한다.
     */
    private val storePackages = setOf(
        "com.android.vending",
        "com.sec.android.app.samsungapps"
    )

    private val scanner = AdRegionScanner()

    // TYPE_ACCESSIBILITY_OVERLAY 창은 접근성 서비스 자신의 컨텍스트로 추가해야 한다.
    // applicationContext를 쓰면 창 토큰이 없어 addView가 BadTokenException으로 죽는다
    // ("token null is not valid") — 광고를 감지하는 순간마다 앱이 크래시한다.
    private val borderOverlay by lazy { AdBorderOverlay(this) }

    // Layer 3의 경고창은 TYPE_APPLICATION_OVERLAY(SYSTEM_ALERT_WINDOW)라 창 토큰이
    // 필요 없다. 위 테두리 오버레이와 달리 applicationContext로 붙여도 안전하다.
    private val overlayManager by lazy { OverlayManager(applicationContext) }

    private val installGuard by lazy {
        InstallGuard(
            overlayManager = overlayManager,
            onBack = { performGlobalAction(GLOBAL_ACTION_BACK) },
            onForceHome = { performGlobalAction(GLOBAL_ACTION_HOME) },
            currentForegroundPackage = { rootInActiveWindow?.packageName?.toString() }
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * 스캔 진행 중 플래그. 트리 순회는 수십~수백 ms가 걸릴 수 있는데 이벤트는 그보다
     * 빨리 들어오므로 겹쳐 돌리지 않는다. AdRegionScanner가 inBrowser·예산 상태를
     * 들고 있어 동시 실행도 안전하지 않다.
     *
     * 단 **겹친 요청을 버리지는 않는다** — [postScan]이 트레일링으로 다시 예약한다.
     */
    private val scanning = AtomicBoolean(false)

    /** Layer 2 진행 중 플래그. 판별은 왕복이 길어 겹쳐 돌면 호출만 늘어난다. */
    private val classifying = AtomicBoolean(false)

    private val extractor = CandidateExtractor()

    private val pipeline by lazy {
        // 키가 없으면 규칙 기반 대역으로 물러난다 — 키를 아직 안 받은 팀원도 앱을
        // 빌드해 파이프라인 전 구간을 돌려볼 수 있어야 한다.
        val key = BuildConfig.GEMINI_API_KEY
        val classifier: AdClassifier =
            if (key.isNotBlank()) GeminiClassifier(key) else StubClassifier()
        Log.i(TAG, "layer2 판별기=${classifier.source}")

        AgentPipeline(
            verdictDao = AppDatabase.getInstance(this).adVerdictDao(),
            classifier = classifier
        )
    }

    // ──────────────────────────────────────────────────────────
    // 스캔 주기 관리 — 전부 메인 스레드에서만 만진다
    // ──────────────────────────────────────────────────────────

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
     * 이벤트가 끊겨도 광고가 아직 화면에 있는지 직접 재확인하고, 사라졌을 때만 표시를 해제.
     * packageNames로 대상 앱 이벤트만 받으므로, 다른 앱으로 나갔을 때 테두리를 지우는
     * 것도 이 재확인이 담당한다.
     */
    private val recheck = Runnable { postScan() }

    // ──────────────────────────────────────────────────────────
    // 스캔 코루틴 안에서만 만지는 상태
    // (scanning 플래그가 직렬화를 보장하므로 락이 필요 없다)
    // ──────────────────────────────────────────────────────────

    /** 스캔이 잘려서 직전 영역을 붙잡고 있은 횟수 */
    private var truncatedHolds = 0

    /** 광고가 안 보이기 시작한 시각. 0이면 지금 보이는 중 */
    private var emptySince = 0L

    override fun onServiceConnected() {
        isConnected = true
        serviceInfo = AccessibilityServiceInfo().apply {
            // 전체 이벤트를 받으면 모든 앱의 모든 UI 변화가 IPC로 배달된다.
            // 실제로 쓰는 네 종류만 남긴다 (VIEW_CLICKED는 Layer 3용).
            //
            // TYPE_VIEW_SCROLLED가 핵심이다. 순수 스크롤은 화면 "내용"이 바뀌는 게
            // 아니라 같은 카드가 밀려 올라가는 것이라 CONTENT_CHANGED가 오지 않거나
            // 아주 드물게만 온다. 이걸 구독하지 않으면 손가락을 떼고 한참 뒤에야
            // 테두리가 따라온다 — 실제로 이게 없어서 박스가 얼어붙어 보였다.
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_VIEW_CLICKED
            // 시스템 단계에서 걸러 우리 프로세스를 아예 깨우지 않는다.
            packageNames = (targetApps + storePackages).toTypedArray()
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            // 병합하지 않고 전부 받는다.
            //
            // 이 값이 150이면 스크롤 이벤트도 초당 6~7개로 묶여서 온다. 테두리를
            // 아무리 싸게 옮겨도 초당 6프레임이면 끊겨 보인다 — 스무스함의 상한이
            // 여기서 정해진다. 0으로 두면 스크롤하는 뷰가 만드는 대로 들어온다.
            //
            // 늘어나는 건 이벤트 수뿐이고, 무거운 트리 순회는 코드 쪽 스로틀
            // (SCAN_INTERVAL_MS)이 200ms로 따로 묶는다. 추가 이벤트가 하는 일은
            // layoutParams 몇 개를 고치는 offsetBy가 전부다.
            notificationTimeout = 0
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return

        // ── Layer 3: 설치 유도 감지 ──
        // 스토어는 targetApps가 아니므로 아래 필터보다 먼저 처리해야 한다.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            installGuard.isStorePackage(pkg)
        ) {
            installGuard.onStoreRedirect(pkg)
            return
        }

        if (pkg !in targetApps) return

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val clickedText = event.contentDescription?.toString()
                ?: event.text.joinToString(separator = " ")
            installGuard.onClick(clickedText, pkg)
            // 클릭만으로는 화면이 바뀌지 않아 Layer 1이 다시 스캔할 게 없다.
            // (화면이 바뀌면 CONTENT_CHANGED가 따로 온다)
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // 페이지가 새로 떴다. 광고를 나중에 끼워 넣는 사이트를 위해 재스캔을 예약해 둔다.
            handler.removeCallbacks(lazyRescan)
            for (d in LAZY_RESCAN_MS) handler.postDelayed(lazyRescan, d)
        }

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val dy = scrollDeltaY(event)
            if (dy != 0) {
                // 스캔을 기다리지 않고 지금 있는 테두리를 바로 민다. 화면이 위로
                // 굴렀으면(dy > 0) 광고도 위로 가므로 테두리는 -dy만큼 움직인다.
                borderOverlay.offsetBy(-dy)
                // 지금 도는 스캔은 이만큼 구르기 *전* 화면을 읽고 있다. 결과가
                // 돌아왔을 때 그대로 그리면 테두리가 뒤로 튄다 — 보정에 쓴다.
                scrollSinceScanStart += dy
            }
        }

        requestScan()
    }

    /**
     * 이 스크롤로 화면이 세로로 몇 px 움직였는가. 노드를 하나도 읽지 않는다.
     *
     * 값을 채우지 않는 뷰는 UNDEFINED(-1)를 그대로 준다. 1px 스크롤은 눈에 보이지도
     * 않으므로 둘을 구분하지 않고 함께 버린다. 화면 높이를 넘는 값은 페이지 점프이거나
     * 쓰레기값이니 밀지 않고 스캔에 맡긴다.
     *
     * 크롬 실측(2026-08-12, SM-S937N): 진짜 값은 `android.widget.FrameLayout`
     * (합성 뷰)에서 오고 한 번에 120~360px씩, 합계가 실제 스크롤 거리와 맞는다.
     * `android.webkit.WebView`에서 오는 이벤트는 전부 -1이라 위 조건에서 걸러진다.
     * 도착 간격은 약 100ms로, 이건 크롬이 이벤트를 만드는 주기라 우리가 못 줄인다.
     */
    private fun scrollDeltaY(event: AccessibilityEvent): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return 0
        val dy = event.scrollDeltaY
        if (dy > -2 && dy < 2) return 0
        val limit = resources.displayMetrics.heightPixels
        if (dy > limit || dy < -limit) return 0
        return dy
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
        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() !in targetApps) {
            // 대상 앱을 벗어났다. 여기는 히스테리시스를 거치지 않고 바로 지운다 —
            // 다른 앱 화면에 이전 앱의 테두리가 남아 있으면 그게 오탐이다.
            truncatedHolds = 0
            emptySince = 0L
            withContext(Dispatchers.Main) { apply(emptyList(), emptyList()) }
            return
        }

        val result = scanner.scan(root)
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

        // 캐시만 보는 Layer 2. 판별기를 부르지 않으므로 화면이 바뀔 때마다 돌려도 되고,
        // 그래야 점선이 카드를 따라다닌다. 다만 이건 이번 프레임의 **두 번째** 트리
        // 순회라 상한을 걸어 확정 테두리의 추종을 방해하지 않게 한다. 새 판별은 유휴 때만.
        val guessed = if (isAiEnabled()) {
            runCatching {
                pipeline.run(
                    extractor.extract(root, stable, budgetMs = SCROLL_EXTRACT_BUDGET_MS),
                    allowClassify = false
                ).regions
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        withContext(Dispatchers.Main) { apply(stable, guessed) }
    }

    private fun apply(confirmed: List<Rect>, guessed: List<Rect>) {
        handler.removeCallbacks(recheck)
        if (confirmed.isNotEmpty() || guessed.isNotEmpty()) handler.postDelayed(recheck, RECHECK_MS)

        // 스캔이 도는 동안 굴러간 만큼 결과를 되민다 (안 그러면 테두리가 뒤로 튄다)
        val dy = -scrollSinceScanStart
        val shifted = confirmed.shiftedBy(dy)
        borderOverlay.show(AdMarkStyle.CONFIRMED, shifted)
        borderOverlay.show(AdMarkStyle.AI_GUESS, guessed.shiftedBy(dy))

        confirmedRegions = shifted
        scheduleLayer2()
    }

    private fun List<Rect>.shiftedBy(dy: Int): List<Rect> =
        if (dy == 0 || isEmpty()) this else map { Rect(it).apply { offset(0, dy) } }

    // ──────────────────────────────────────────────────────────
    // Layer 2 — AI 판별 파이프라인
    // ──────────────────────────────────────────────────────────

    /**
     * 화면이 바뀔 때마다 증가. 파이프라인은 왕복에 수 초가 걸릴 수 있어, 결과가
     * 돌아왔을 때 화면이 이미 넘어갔으면 표시하지 않는다 (판정은 DB에 남아 다음에
     * 같은 카드가 나오면 캐시로 즉시 뜬다).
     */
    private var screenGeneration = 0

    /** 스캔 코루틴이 읽고 메인 스레드가 쓴다 */
    @Volatile
    private var confirmedRegions: List<Rect> = emptyList()

    private val runLayer2 = Runnable {
        if (!isAiEnabled()) return@Runnable
        if (!classifying.compareAndSet(false, true)) return@Runnable

        val generation = screenGeneration
        val excluded = confirmedRegions
        scope.launch {
            try {
                val root = rootInActiveWindow
                if (root == null || root.packageName?.toString() !in targetApps) return@launch
                // 유휴 경로라 상한을 걸지 않는다. 후보를 빠짐없이 봐야 판별이 의미가 있다.
                val candidates = extractor.extract(root, excluded)
                val result = pipeline.run(candidates)
                // 유휴 1회에 한 줄. 이벤트마다가 아니라 스크롤이 멈췄을 때만 찍히므로
                // 부담이 없고, 캐시가 실제로 듣는지 눈으로 볼 수 있는 유일한 창이다.
                Log.i(
                    TAG,
                    "layer2 출처=${candidates.firstOrNull()?.sourceKey ?: "-"} " +
                        "후보=${candidates.size} 캐시=${result.cacheHits} " +
                        "판별=${result.classified} 보류=${result.skippedByLimit} " +
                        "표시=${result.regions.size}"
                )
                withContext(Dispatchers.Main) {
                    // 결과가 늦게 왔고 그 사이 화면이 바뀌었으면 버린다
                    if (generation == screenGeneration) {
                        borderOverlay.show(AdMarkStyle.AI_GUESS, result.regions)
                    }
                }
            } finally {
                classifying.set(false)
            }
        }
    }

    /**
     * 스크롤이 멈춘 뒤에만 돌린다. 스크롤 중에는 화면이 계속 바뀌어 판별해봐야
     * 표시할 자리가 사라지고, 호출만 낭비된다.
     */
    private fun scheduleLayer2() {
        screenGeneration++
        handler.removeCallbacks(runLayer2)
        if (isAiEnabled()) handler.postDelayed(runLayer2, LAYER2_IDLE_MS)
    }

    /** 기본 OFF 옵트인. 화면 텍스트가 외부로 나가므로 사용자가 켜야만 동작한다. */
    private fun isAiEnabled(): Boolean =
        getSharedPreferences("settings", MODE_PRIVATE).getBoolean(PREF_AI_CLASSIFY, false)

    override fun onInterrupt() {
        apply(emptyList(), emptyList())
    }

    override fun onDestroy() {
        isConnected = false
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        borderOverlay.dismissAll()
        overlayManager.dismiss()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AdGuard"

        /**
         * 스캔 최소 간격. 스크롤 중에는 이벤트가 초당 수십 번 온다.
         * 이 구간에 들어온 요청은 버리지 않고 트레일링으로 미룬다.
         */
        private const val SCAN_INTERVAL_MS = 200L

        /** 이벤트가 끊겨도 광고가 아직 화면에 있는지 직접 확인하는 주기 */
        private const val RECHECK_MS = 1000L

        /** 광고가 안 보인다고 판단해도 이만큼은 테두리를 유지한다 (깜빡임 방지) */
        private const val CLEAR_DELAY_MS = 700L

        /** 스캔이 잘렸을 때 직전 영역을 몇 번까지 붙잡고 있을지 */
        private const val MAX_TRUNCATED_HOLDS = 3

        /**
         * 페이지가 뜬 뒤 광고를 나중에 끼워 넣는 사이트를 위한 재스캔 시각.
         * 이게 없으면 광고 삽입 이벤트가 스로틀 구간에 떨어졌을 때 그 화면은
         * 사용자가 다시 스크롤할 때까지 영원히 재스캔되지 않는다.
         */
        private val LAZY_RESCAN_MS = longArrayOf(600, 1800, 3500)

        /** 스크롤 경로에서 도는 Layer 2 후보 추출의 시간 상한 */
        private const val SCROLL_EXTRACT_BUDGET_MS = 150L

        /** 스크롤이 이만큼 멈춰 있어야 Layer 2 판별을 돌린다. */
        private const val LAYER2_IDLE_MS = 600L

        /** "settings" prefs — AI 광고 판별 옵트인 키. MainActivity 토글과 공유한다. */
        const val PREF_AI_CLASSIFY = "ai_classify"

        /**
         * 서비스가 지금 시스템에 연결돼 있는지. 설정 화면의 "켜짐" 표시와 별개다 —
         * 설정에는 켜짐으로 남은 채 서비스만 죽는 경우를 구분하려고 둔다
         * (ServiceStatus 참고).
         *
         * 프로세스가 통째로 죽으면 이 값도 함께 사라지고 false로 시작한다.
         * MainActivity가 같은 프로세스라 그 상태를 그대로 읽는다.
         */
        @Volatile
        var isConnected: Boolean = false
            private set
    }
}
