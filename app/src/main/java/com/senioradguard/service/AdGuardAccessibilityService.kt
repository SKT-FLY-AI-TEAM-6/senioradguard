package com.senioradguard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.senioradguard.BuildConfig
import com.senioradguard.agent.AgentPipeline
import com.senioradguard.agent.CandidateExtractor
import com.senioradguard.agent.GeminiClassifier
import com.senioradguard.agent.StubClassifier
import com.senioradguard.detector.db.AppDatabase
import com.senioradguard.guard.InstallGuard
import com.senioradguard.logger.AdEventLogger
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
 * 보호자에게 이미 알린 광고인지 기억한다.
 *
 * Layer 1·2는 스크롤할 때마다 같은 광고를 다시 표시한다. 표시할 때마다 원격에
 * 남기면 한 페이지를 훑는 동안 같은 광고가 수십 건 쌓여 보호자 화면이 쓸모없어진다.
 * 그래서 **출처(도메인/패키지) + 레이어 조합당 한 번만** 남긴다.
 *
 * 서비스가 살아있는 동안만 기억하며, 용량을 넘기면 오래된 것부터 버린다. 버려진
 * 출처를 나중에 다시 방문하면 한 번 더 남는데, 그 정도 중복은 감수한다 —
 * 영구 저장을 하면 "어제 본 광고를 오늘 다시 봤다"를 영영 못 알리게 된다.
 */
internal class SightingLog(private val capacity: Int = 128) {

    private val seen = object : LinkedHashMap<String, Boolean>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) =
            size > capacity
    }

    /** 처음 보는 조합이면 true를 돌려주고 기억한다. */
    fun shouldReport(sourceKey: String, layer: Int): Boolean {
        val key = "$sourceKey|L$layer"
        // containsKey가 아니라 get이어야 한다 — LinkedHashMap의 접근 순서는
        // get/put만 갱신한다. containsKey로 조회하면 자주 보는 사이트가
        // "오래 안 본 것"으로 취급돼 먼저 밀려나고 중복 기록이 난다.
        if (seen[key] != null) return false
        seen[key] = true
        return true
    }
}

/**
 * 스크롤이 언제 멈출지 예측한다.
 *
 * VelocityTracker는 쓸 수 없다 — MotionEvent를 먹는 물건인데 접근성 서비스는
 * MotionEvent를 받지 않는다. 대신 TYPE_VIEW_SCROLLED가 주는 스크롤 델타와
 * 이벤트 도착 시각으로 직접 px/ms 속도를 구한다.
 *
 * 안드로이드 플링은 마찰로 지수 감쇠한다(v = v0·e^(-t/τ)). 그래서 남은 시간은
 * τ·ln(v0 / v멈춤)으로 추정한다. 정확한 물리 재현이 목적이 아니라 "지금 스캔하면
 * 헛일이고 대략 언제쯤 화면이 멎는가"만 알면 되므로 이 정도로 충분하다.
 */
internal class ScrollStopPredictor(private val now: () -> Long = System::currentTimeMillis) {

    private companion object {
        /** 플링 감쇠 시간상수. 실기기 플링이 대략 이 정도로 잦아든다. */
        const val TAU_MS = 180.0

        /** 이 속도 아래면 멈춘 것으로 본다 (≈20px/s). */
        const val STOP_VELOCITY = 0.02

        /** 속도 추정에 쓰는 지수이동평균 계수. 튀는 이벤트 하나에 흔들리지 않게. */
        const val EMA_ALPHA = 0.5

        /** 이 시간 넘게 스크롤 이벤트가 없으면 스크롤 상황이 아니라고 본다. */
        const val SCROLL_IDLE_MS = 250L

        /**
         * 이동량을 알 수 없을 때, 마지막 스크롤 이벤트로부터 이만큼 지나야 멎은
         * 것으로 본다. 크롬처럼 스크롤 위치를 안 주는 앱에서 쓰인다.
         */
        const val UNKNOWN_SETTLE_MS = 150L
    }

    private var velocity = 0.0          // px/ms
    private var velocityKnown = false
    private var lastEventAt = 0L

    /**
     * @param deltaPx 이번 이벤트의 스크롤 이동량(부호 무시).
     *   **null은 "이동량을 알 수 없음"이고 0과 다르다.** 크롬을 비롯한 여러 앱이
     *   스크롤 위치를 -1로 주기 때문에 이동량을 모르는 경우가 흔한데, 이걸 0으로
     *   뭉뚱그리면 "멈췄다"로 잘못 읽혀 스크롤 도중에 스캔이 나간다.
     *   모를 때는 속도 없이 "스크롤 중"이라는 사실만 기록한다.
     */
    fun record(deltaPx: Int?) {
        val t = now()
        val dt = t - lastEventAt
        val fresh = lastEventAt != 0L && dt in 1..SCROLL_IDLE_MS
        lastEventAt = t

        if (deltaPx == null) {
            velocityKnown = false
            return
        }
        if (!fresh) {
            // 첫 이벤트이거나 한참 만에 온 이벤트는 속도를 낼 근거가 없다
            velocity = 0.0
            velocityKnown = false
            return
        }
        velocityKnown = true
        // 이동량 0은 "안 움직였다"는 확실한 신호다. EMA로 서서히 줄이면 절반씩
        // 깎이느라 정지 인식이 100ms 넘게 늦는다. 곧바로 멈춘 것으로 본다.
        velocity = if (deltaPx == 0) {
            0.0
        } else {
            EMA_ALPHA * (kotlin.math.abs(deltaPx) / dt.toDouble()) + (1 - EMA_ALPHA) * velocity
        }
    }

    /** 방금까지 스크롤 이벤트가 오고 있었는가 (이동량을 몰라도 판단 가능). */
    fun isScrollActive(): Boolean =
        lastEventAt != 0L && now() - lastEventAt <= SCROLL_IDLE_MS

    /** 화면이 실제로 움직이는 중인가. 표시를 걷어낼지 판단하는 데 쓴다. */
    fun isScrolling(): Boolean =
        isScrollActive() && (!velocityKnown || velocity > STOP_VELOCITY)

    /**
     * 지금부터 스크롤이 멎기까지 남은 시간(ms). 멈췄으면 0.
     *
     * 속도를 알면 감쇠 모델로 계산하고, 모르면 마지막 스크롤 이벤트로부터
     * 일정 시간이 지나야 멎은 것으로 본다. 어느 쪽이든 새 스크롤 이벤트가 올
     * 때마다 다시 계산되므로, 결과적으로 "마지막 스크롤 뒤 한 번"만 훑게 된다.
     */
    fun predictStopDelayMs(): Long {
        if (!isScrollActive()) return 0
        val elapsed = now() - lastEventAt

        if (!velocityKnown) return (UNKNOWN_SETTLE_MS - elapsed).coerceAtLeast(0)
        if (velocity <= STOP_VELOCITY) return 0

        val remaining = TAU_MS * kotlin.math.ln(velocity / STOP_VELOCITY)
        return (remaining - elapsed).toLong().coerceAtLeast(0)
    }

    fun reset() {
        velocity = 0.0
        velocityKnown = false
        lastEventAt = 0L
    }
}

/**
 * 단일 진입점. 이벤트를 받아 루트 노드를 한 번만 가져오고 각 레이어에 배분한다.
 *
 *   Layer 1  공식 광고 라벨 감지  → 비차단 테두리 (AdBorderOverlay)
 *   Layer 2  LLM 판별            → Phase 2에서 추가
 *   Layer 3  설치 유도 감지       → 차단 경고 (InstallGuard)
 *
 * 부하 관리 (실기기에서 ServiceANR이 관측되어 도입):
 *   - 시스템이 관심 있는 이벤트/패키지만 배달하도록 좁힌다 (eventTypes, packageNames)
 *   - 트리 순회는 백그라운드 스레드에서 수행하고 결과 반영만 메인으로 돌린다
 *   - 스캔이 진행 중이면 새 이벤트는 버린다 (큐가 쌓이지 않게)
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
    private val borderOverlay by lazy {
        AdBorderOverlay(this).apply { onCloseAllAds = ::closeAllAds }
    }

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
     * 스캔 진행 중 플래그. 트리 순회는 수십 ms가 걸릴 수 있는데 이벤트는 그보다
     * 빨리 들어오므로, 겹치는 요청은 버린다. AdRegionScanner가 inBrowser 상태를
     * 들고 있어 동시 실행도 안전하지 않다.
     */
    private val scanning = AtomicBoolean(false)

    /** Layer 2 진행 중 플래그. 판별은 왕복이 길어 겹쳐 돌면 호출만 늘어난다. */
    private val classifying = AtomicBoolean(false)

    private val scrollPredictor = ScrollStopPredictor()

    /** API 28 미만에서 스크롤 이동량을 위치 차이로 구하기 위한 직전 위치. */
    private var lastScrollY = 0

    /** 마지막으로 예약한 지연. 로그에만 쓴다. */
    private var pendingScanDelay = 0L

    /**
     * 최적화 효과 계측. 예전에는 화면 변경 이벤트 하나에 스캔 하나였으므로,
     * "이번 스캔이 이벤트 몇 개를 모았는가"가 곧 줄인 스캔 수다.
     */
    private var scanCount = 0
    private var lastScanAt = 0L
    private var eventsSinceScan = 0

    /**
     * 그중 화면 변경 이벤트만 따로. 예전 코드는 이 이벤트 하나에 스캔 하나였으므로
     * 이 수가 곧 "예전이었다면 났을 스캔 수"다.
     */
    private var contentEventsSinceScan = 0
    private var contentEventsTotal = 0

    private val extractor = CandidateExtractor()

    /** 같은 광고를 보호자에게 반복해서 알리지 않도록 막는다. */
    private val sightings = SightingLog()

    private val pipeline by lazy {
        // 키가 없는 팀원도 빌드·실행이 되도록 스텁으로 물러난다. 판정 품질은
        // 크게 다르지만 파이프라인 동작 자체는 같아서 개발에 지장이 없다.
        val classifier = if (BuildConfig.GEMINI_API_KEY.isNotBlank()) {
            GeminiClassifier(BuildConfig.GEMINI_API_KEY)
        } else {
            StubClassifier()
        }
        Log.i(TAG, "판별기=${classifier.source} (${classifier.javaClass.simpleName})")
        AgentPipeline(
            verdictDao = AppDatabase.getInstance(this).adVerdictDao(),
            classifier = classifier
        )
    }

    /**
     * 이벤트가 끊겨도 광고가 아직 화면에 있는지 직접 재확인하고, 사라졌을 때만 표시를 해제.
     * packageNames로 대상 앱 이벤트만 받으므로, 다른 앱으로 나갔을 때 테두리를 지우는
     * 것도 이 재확인이 담당한다.
     */
    private val recheck = Runnable {
        val root = rootInActiveWindow
        if (root != null && root.packageName?.toString() in targetApps) {
            scanAsync(root)
        } else {
            applyLayer1(emptyList())
        }
    }

    override fun onServiceConnected() {
        isConnected = true
        serviceInfo = AccessibilityServiceInfo().apply {
            // 전체 이벤트를 받으면 모든 앱의 모든 UI 변화가 IPC로 배달된다.
            // 실제로 쓰는 세 종류만 남긴다 (VIEW_CLICKED는 Layer 3용).
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                // 스크롤 속도를 재려고 추가. 이 이벤트로는 스캔하지 않고 예측만 한다.
                AccessibilityEvent.TYPE_VIEW_SCROLLED
            // 시스템 단계에서 걸러 우리 프로세스를 아예 깨우지 않는다.
            packageNames = (targetApps + storePackages).toTypedArray()
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            // 코드 쪽에서 다시 스로틀하지 않아도 되도록 시스템 병합 간격을 늘린다.
            notificationTimeout = 200
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

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            scrollPredictor.record(scrollDeltaOf(event))
            if (scrollPredictor.isScrolling()) {
                // 스크롤 중에는 표시를 지운다. 스캔 없이 남겨두면 테두리가 제자리에
                // 멈춰 엉뚱한 카드를 가리키게 된다 — 광고가 아닌 것을 광고로 표시하는
                // 쪽이 잠깐 안 보이는 것보다 나쁘다.
                //
                // (직전 영역을 스크롤 델타만큼 평행이동하는 방법도 검토했으나 버렸다.
                //  고정 헤더·하단 배너는 따라 움직이지 않아 그것들이 어긋난다.)
                clearMarks()
            }
            scheduleScan()
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            contentEventsSinceScan++
            contentEventsTotal++
        }
        scheduleScan()
    }

    /**
     * API 28+는 스크롤 이동량을 직접 준다. 그 아래는 스크롤 위치(scrollY)의 변화로
     * 대신하고, 그마저 없으면(크롬을 포함해 여러 앱이 -1을 준다) **null**을 돌린다.
     * 0을 돌리면 "안 움직였다"로 읽혀 스크롤 도중에 스캔이 나가버린다.
     */
    private fun scrollDeltaOf(event: AccessibilityEvent): Int? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && event.scrollDeltaY != 0) {
            return event.scrollDeltaY
        }
        val y = event.scrollY
        if (y < 0) return null          // 이동량을 알 수 없음 (0과 구분해야 한다)
        val delta = y - lastScrollY
        lastScrollY = y
        return delta
    }

    // ──────────────────────────────────────────────────────────
    // 스캔 스케줄링 — 언제 훑을지
    // ──────────────────────────────────────────────────────────

    /**
     * 다음 스캔을 예약한다. 이미 예약된 게 있으면 취소하고 다시 잡는다.
     *
     * 예전에는 CONTENT_CHANGED가 올 때마다 그 자리에서 전체 트리를 훑었다.
     * 스크롤 한 번에 이벤트가 수십 개 오므로 그만큼 헛스캔이 났다. 지금은
     * **스크롤이 멎을 시점을 예측해 그때 한 번만** 훑는다.
     */
    private fun scheduleScan() {
        eventsSinceScan++
        val predicted = scrollPredictor.predictStopDelayMs()
        // 예측이 0이면 이미 멈춘 상황이다. 그래도 곧바로 훑지 않고 한두 프레임
        // 모아서 처리한다 — 화면 하나 바뀔 때 CONTENT_CHANGED가 여러 번 온다.
        val target = (if (predicted > 0) predicted + SETTLE_MS else 0)
            .coerceIn(MIN_SCAN_DELAY_MS, MAX_SCAN_DELAY_MS)
        // 스크롤 이벤트가 올 때마다 이 값이 다시 계산되고 예약이 갱신되므로,
        // 결과적으로 "마지막 스크롤 이후 한 번"만 훑는다.

        pendingScanDelay = target
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, frameAlignedDelay(target))
    }

    /**
     * 지연 시간을 프레임 경계에 맞춰 올림한다. 기기의 실제 주사율을 읽어 계산하므로
     * 60Hz면 16.7ms, 120Hz면 8.3ms 단위가 된다.
     *
     * refreshRate는 DisplayMetrics가 아니라 Display에 있다.
     */
    private fun frameAlignedDelay(delayMs: Long): Long {
        val interval = frameIntervalMs
        if (interval <= 0f) return delayMs
        val frames = kotlin.math.ceil(delayMs / interval).toInt().coerceAtLeast(1)
        return (frames * interval).toLong()
    }

    /**
     * 기기 주사율에서 계산한 프레임 간격.
     *
     * Context.getDisplay()를 쓰면 안 된다. 서비스는 화면에 연결된 컨텍스트가 아니라
     * API 30+에서 UnsupportedOperationException을 던지고, 접근성 서비스가 통째로
     * 죽는다(실기기에서 dumpsys accessibility의 Crashed services에 올라갔다).
     * 화면과 무관한 컨텍스트에서 디스플레이를 얻는 정식 경로는 DisplayManager다.
     */
    private val frameIntervalMs: Float by lazy {
        val hz = runCatching {
            val dm = getSystemService(DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.refreshRate ?: 60f
        }.getOrDefault(60f)
        val interval = if (hz > 1f) 1000f / hz else 1000f / 60f
        Log.i(TAG, "주사율=${"%.1f".format(hz)}Hz 프레임간격=${"%.2f".format(interval)}ms")
        interval
    }

    /**
     * 예약된 시각에 스캔한다.
     *
     * 한때 여기서 Choreographer.postFrameCallback으로 vsync 직후까지 한 번 더
     * 미뤘다. **그게 감지를 통째로 죽였다.** 우리 프로세스에는 보이는 창이 없어서
     * 다른 앱을 보는 동안에는 프레임 콜백이 오지 않고, 콜백이 안 오니 스캔도 영영
     * 실행되지 않았다. 실기기에서 확인한 증상:
     *
     *   우리 앱이 화면에 떠 있을 때        스캔 4건
     *   크롬으로 막 전환한 직후            스캔 2건
     *   크롬에서 20초 지난 뒤 스크롤       스캔 0건
     *
     * 정작 보호가 필요한 상황(다른 앱을 보는 중)에만 죽는 최악의 형태였다.
     * 지연을 프레임 배수로 맞추는 것(frameAlignedDelay)은 그대로 두되, 실행은
     * 핸들러에서 바로 한다.
     */
    private val scanRunnable = Runnable {
        val root = rootInActiveWindow ?: return@Runnable
        if (root.packageName?.toString() !in targetApps) return@Runnable
        scanAsync(root)
    }

    /**
     * 트리 순회를 백그라운드로 넘기고 결과 반영만 메인 스레드에서 한다.
     * 이전 스캔이 아직 돌고 있으면 이번 이벤트는 버린다.
     */
    private fun scanAsync(root: AccessibilityNodeInfo) {
        if (!scanning.compareAndSet(false, true)) return

        val startedAt = System.currentTimeMillis()
        scanCount++
        Log.i(
            TAG,
            "scan #$scanCount 모은이벤트=$eventsSinceScan " +
                "(구방식스캔=$contentEventsSinceScan, 누적=$contentEventsTotal) " +
                "(직전 +${if (lastScanAt == 0L) 0 else startedAt - lastScanAt}ms, " +
                "예약지연=${pendingScanDelay}ms)"
        )
        lastScanAt = startedAt
        eventsSinceScan = 0
        contentEventsSinceScan = 0

        scope.launch {
            try {
                val confirmed = scanner.scan(root)
                // 캐시만 보는 Layer 2. 판별기를 부르지 않으므로 화면이 바뀔 때마다
                // 돌려도 되고, 그래야 점선이 카드를 따라다닌다. 새 판별은 유휴 때만.
                val guessed = if (isAiEnabled()) {
                    runCatching {
                        pipeline.run(extractor.extract(root, confirmed), allowClassify = false)
                            .regions
                    }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                // 트리 접근은 이 백그라운드 블록에서 끝낸다 (apply는 메인 스레드).
                val sourceKey = runCatching { extractor.sourceKeyOf(root) }.getOrNull()
                withContext(Dispatchers.Main) { apply(confirmed, guessed, sourceKey) }
            } finally {
                scanning.set(false)
            }
        }
    }

    private fun applyLayer1(regions: List<Rect>) = apply(regions, emptyList())

    /**
     * "광고 모두 닫기" — 표시 중인 각 광고 영역에서 닫기(X) 버튼을 찾아 누른다.
     *
     * 사용자가 직접 누른 버튼에 대한 응답이다. 앱이 알아서 광고를 없애는 게 아니라,
     * 작은 X를 찾아 누르기 어려운 사람을 대신해 그 동작을 수행한다.
     *
     * **못 찾았다고 뒤로 가기를 하면 안 된다.** 처음엔 그렇게 만들었는데, 웹 배너
     * 광고는 접근성 트리에 X가 거의 없어서 사실상 매번 폴백이 걸렸고, 결과적으로
     * 광고가 아니라 사용자가 보던 페이지가 닫혔다. 읽던 것을 잃는 쪽이 광고 몇 개
     * 남는 것보다 훨씬 나쁘다.
     *
     * 뒤로 가기는 **전면 광고일 때만** 쓴다. 화면을 통째로 덮은 광고는 뒤로 가기가
     * 광고 자체를 닫는 동작이라 페이지를 잃지 않는다.
     */
    private fun closeAllAds() {
        val root = rootInActiveWindow ?: return
        val screen = Rect().also { root.getBoundsInScreen(it) }
        val targets = confirmedRegions + aiRegions

        var closed = 0
        var fullScreenAd = false

        for (region in targets) {
            val x = findCloseButton(root, region, 0)
            if (x != null && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                closed++
                continue
            }
            // 화면 대부분을 덮었으면 전면 광고로 본다 (AdRegionScanner가 그런 광고를
            // 화면 전체 영역으로 접어서 돌려준다)
            if (region.height() >= screen.height() * FULLSCREEN_RATIO) fullScreenAd = true
        }

        Log.i(TAG, "광고 모두 닫기: 영역 ${targets.size}개 중 $closed 개 닫음, 전면광고=$fullScreenAd")

        when {
            closed > 0 -> Unit                       // 닫았으면 그걸로 끝
            fullScreenAd -> performGlobalAction(GLOBAL_ACTION_BACK)
            else -> toast("이 광고는 닫기 버튼이 없어요")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * region 안에서 닫기 버튼으로 보이는 클릭 가능한 노드를 찾는다.
     *
     * 광고의 X는 텍스트가 없고 contentDescription이나 viewId로만 정체를 드러내는
     * 경우가 대부분이라 둘 다 본다. 또 광고 영역 전체를 누르면 광고를 클릭하는
     * 셈이 되므로, **작은 노드만** 후보로 삼는다.
     */
    private fun findCloseButton(
        node: AccessibilityNodeInfo,
        region: Rect,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (depth > CLOSE_SEARCH_DEPTH) return null

        val bounds = Rect().also { node.getBoundsInScreen(it) }
        // 이 가지가 광고 영역과 아예 겹치지 않으면 더 볼 필요가 없다
        if (!Rect.intersects(bounds, region)) return null

        if (node.isClickable && node.isVisibleToUser && isCloseSized(bounds) && looksLikeClose(node)) {
            return node
        }
        for (i in 0 until node.childCount) {
            findCloseButton(node.getChild(i) ?: continue, region, depth + 1)?.let { return it }
        }
        return null
    }

    /** 닫기 버튼은 작다. 큰 노드는 광고 본체이므로 누르면 안 된다. */
    private fun isCloseSized(b: Rect): Boolean {
        val max = (CLOSE_MAX_DP * resources.displayMetrics.density).toInt()
        return b.width() in 1..max && b.height() in 1..max
    }

    private fun looksLikeClose(node: AccessibilityNodeInfo): Boolean {
        val text = "${node.text ?: ""} ${node.contentDescription ?: ""}".lowercase()
        if (CLOSE_TEXTS.any { it in text }) return true

        val id = node.viewIdResourceName?.lowercase() ?: return false
        return id.split(Regex("[^a-z0-9]+")).any { it in CLOSE_ID_TOKENS }
    }

    /** 스크롤 중 표시를 걷어낸다. 스캔 없이 두면 테두리가 엉뚱한 자리를 가리킨다. */
    private fun clearMarks() {
        if (confirmedRegions.isEmpty() && !aiMarksShown) return
        borderOverlay.show(AdMarkStyle.CONFIRMED, emptyList())
        borderOverlay.show(AdMarkStyle.AI_GUESS, emptyList())
        confirmedRegions = emptyList()
        aiRegions = emptyList()
        aiMarksShown = false
    }

    private fun apply(confirmed: List<Rect>, guessed: List<Rect>, sourceKey: String? = null) {
        handler.removeCallbacks(recheck)
        if (confirmed.isNotEmpty() || guessed.isNotEmpty()) handler.postDelayed(recheck, 1000)

        borderOverlay.show(AdMarkStyle.CONFIRMED, confirmed)
        borderOverlay.show(AdMarkStyle.AI_GUESS, guessed)
        aiMarksShown = guessed.isNotEmpty()

        confirmedRegions = confirmed
        aiRegions = guessed
        reportSighting(sourceKey, layer = 1, count = confirmed.size)
        reportSighting(sourceKey, layer = 2, count = guessed.size)
        scheduleLayer2()
    }

    /**
     * 광고를 표시했다는 사실을 보호자에게 남긴다.
     *
     * 광고 문구 자체는 올리지 않는다. 어르신이 무엇을 읽고 있었는지까지 보호자에게
     * 넘길 이유가 없고, 보호자가 알아야 할 것은 "어디서 광고가 몇 건 떴는가"다.
     * 출처는 도메인 또는 패키지명까지만 남는다.
     */
    private fun reportSighting(sourceKey: String?, layer: Int, count: Int) {
        if (sourceKey == null || count == 0) return
        if (!sightings.shouldReport(sourceKey, layer)) return
        AdEventLogger.logAdMarked(sourceKey, "광고 ${count}건 표시", layer)
    }

    // ──────────────────────────────────────────────────────────
    // Layer 2 — AI 판별 파이프라인
    // ──────────────────────────────────────────────────────────

    /**
     * 화면이 바뀔 때마다 증가. 파이프라인은 왕복에 수 초가 걸릴 수 있어, 결과가
     * 돌아왔을 때 화면이 이미 넘어갔으면 표시하지 않는다 (판정은 DB에 남아 다음에
     * 같은 카드가 나오면 캐시로 즉시 뜬다).
     */
    private var screenGeneration = 0
    private var confirmedRegions: List<Rect> = emptyList()

    /** AI 점선이 지금 떠 있는가. 스크롤 시작 시 걷어낼지 판단하는 데만 쓴다. */
    private var aiMarksShown = false

    /** 지금 점선으로 표시 중인 영역. "광고 모두 닫기"가 대상으로 쓴다. */
    private var aiRegions: List<Rect> = emptyList()

    private val runLayer2 = Runnable {
        if (!isAiEnabled()) return@Runnable
        val root = rootInActiveWindow ?: return@Runnable
        if (root.packageName?.toString() !in targetApps) return@Runnable
        if (!classifying.compareAndSet(false, true)) return@Runnable

        val generation = screenGeneration
        val excluded = confirmedRegions
        scope.launch {
            try {
                val candidates = extractor.extract(root, excluded)
                val result = pipeline.run(candidates)
                // 유휴 1회에 한 줄. 이벤트마다가 아니라 스크롤이 멈췄을 때만 찍히므로
                // 부담이 없고, 캐시가 실제로 듣는지 눈으로 볼 수 있는 유일한 창이다.
                for (t in result.traces) {
                    Log.i(
                        TAG,
                        "  A1 출처=${t.sourceKey} 글자수=${t.chars} " +
                            "| A2 isAd=${t.rawIsAd} conf=${"%.2f".format(t.rawConfidence)} " +
                            "| A4 신호=${t.weakSignal} conf=${"%.2f".format(t.finalConfidence)} " +
                            "| 표시=${t.marked} :: ${t.reason.take(70)}"
                    )
                }
                Log.i(
                    TAG,
                    "layer2 출처=${candidates.firstOrNull()?.sourceKey ?: "-"} " +
                        "후보=${candidates.size} 델타생략=${result.memoHits} " +
                        "캐시=${result.cacheHits} " +
                        "판별=${result.classified} 보류=${result.skippedByLimit} " +
                        "표시=${result.regions.size}"
                )
                withContext(Dispatchers.Main) {
                    // 결과가 늦게 왔고 그 사이 화면이 바뀌었으면 버린다
                    if (generation == screenGeneration) {
                        borderOverlay.show(AdMarkStyle.AI_GUESS, result.regions)
                        aiMarksShown = result.regions.isNotEmpty()
                        aiRegions = result.regions
                        reportSighting(
                            candidates.firstOrNull()?.sourceKey,
                            layer = 2,
                            count = result.regions.size
                        )
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
        applyLayer1(emptyList())
    }

    override fun onDestroy() {
        isConnected = false
        handler.removeCallbacks(recheck)
        handler.removeCallbacks(runLayer2)
        handler.removeCallbacks(scanRunnable)
        scope.cancel()
        borderOverlay.dismissAll()
        overlayManager.dismiss()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AdGuard"

        /** 닫기 버튼 탐색 깊이. 광고 카드 안쪽이라 아주 깊지 않다. */
        private const val CLOSE_SEARCH_DEPTH = 25

        /** 이보다 큰 노드는 닫기 버튼이 아니라 광고 본체로 본다 (dp). */
        private const val CLOSE_MAX_DP = 72

        /** 이 비율 이상 화면을 덮으면 전면 광고로 보고 뒤로 가기를 허용한다. */
        private const val FULLSCREEN_RATIO = 0.75

        private val CLOSE_TEXTS = setOf(
            "닫기", "광고 닫기", "close", "dismiss", "skip ad", "광고 건너뛰기", "✕", "×"
        )

        private val CLOSE_ID_TOKENS = setOf(
            "close", "dismiss", "btnclose", "closebutton", "adclose", "cancel", "skip"
        )

        /**
         * 예측된 정지 시각에 조금 더 얹는 여유. 스크롤이 멎어도 앱이 마지막 항목을
         * 채워 넣는 시간이 있어, 딱 맞춰 훑으면 반쯤 그려진 화면을 본다.
         */
        private const val SETTLE_MS = 80L

        /** 예약 지연의 하한. 화면 하나 바뀔 때 오는 이벤트 여러 개를 모으는 용도. */
        private const val MIN_SCAN_DELAY_MS = 32L

        /**
         * 예약 지연의 상한. 예측이 빗나가도(감쇠 모델과 다르게 움직이는 스크롤,
         * 손가락을 대고 천천히 끄는 경우) 이 시간 안에는 반드시 한 번 훑는다.
         */
        private const val MAX_SCAN_DELAY_MS = 600L

        /** 스크롤이 이만큼 멈춰 있어야 Layer 2를 돌린다. */
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
