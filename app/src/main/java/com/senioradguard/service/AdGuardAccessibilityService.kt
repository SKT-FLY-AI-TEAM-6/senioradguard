package com.senioradguard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.senioradguard.BuildConfig
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
     * 스캔 진행 중 플래그. 트리 순회는 수십 ms가 걸릴 수 있는데 이벤트는 그보다
     * 빨리 들어오므로, 겹치는 요청은 버린다. AdRegionScanner가 inBrowser 상태를
     * 들고 있어 동시 실행도 안전하지 않다.
     */
    private val scanning = AtomicBoolean(false)

    /** Layer 2 진행 중 플래그. 판별은 왕복이 길어 겹쳐 돌면 호출만 늘어난다. */
    private val classifying = AtomicBoolean(false)

    private val extractor = CandidateExtractor()

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
                AccessibilityEvent.TYPE_VIEW_CLICKED
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

        val root = rootInActiveWindow ?: return
        scanAsync(root)
    }

    /**
     * 트리 순회를 백그라운드로 넘기고 결과 반영만 메인 스레드에서 한다.
     * 이전 스캔이 아직 돌고 있으면 이번 이벤트는 버린다.
     */
    private fun scanAsync(root: AccessibilityNodeInfo) {
        if (!scanning.compareAndSet(false, true)) return
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
                withContext(Dispatchers.Main) { apply(confirmed, guessed) }
            } finally {
                scanning.set(false)
            }
        }
    }

    private fun applyLayer1(regions: List<Rect>) = apply(regions, emptyList())

    private fun apply(confirmed: List<Rect>, guessed: List<Rect>) {
        handler.removeCallbacks(recheck)
        if (confirmed.isNotEmpty() || guessed.isNotEmpty()) handler.postDelayed(recheck, 1000)

        borderOverlay.show(AdMarkStyle.CONFIRMED, confirmed)
        borderOverlay.show(AdMarkStyle.AI_GUESS, guessed)

        confirmedRegions = confirmed
        scheduleLayer2()
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
        applyLayer1(emptyList())
    }

    override fun onDestroy() {
        isConnected = false
        handler.removeCallbacks(recheck)
        handler.removeCallbacks(runLayer2)
        scope.cancel()
        borderOverlay.dismissAll()
        overlayManager.dismiss()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AdGuard"

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
