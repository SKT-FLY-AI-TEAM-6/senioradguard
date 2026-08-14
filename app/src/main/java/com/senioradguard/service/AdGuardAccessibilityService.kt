package com.senioradguard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent
import com.senioradguard.BuildConfig
import com.senioradguard.agent.AdClassifier
import com.senioradguard.agent.AgentPipeline
import com.senioradguard.agent.CandidateExtractor
import com.senioradguard.agent.GeminiClassifier
import com.senioradguard.agent.StubClassifier
import com.senioradguard.detector.UrlGuard
import com.senioradguard.detector.db.AppDatabase
import com.senioradguard.guard.AdClickTracker
import com.senioradguard.guard.InstallGuard
import com.senioradguard.guard.InstallSourceGuard
import com.senioradguard.guard.RedirectChainTracker
import com.senioradguard.guard.RedirectRules
import com.senioradguard.logger.AdEventLogger
import com.senioradguard.ocr.OcrScanner
import com.senioradguard.overlay.AdBorderOverlay
import com.senioradguard.overlay.AdMarkStyle
import com.senioradguard.overlay.OverlayManager
import com.senioradguard.region.AdLabelRules
import com.senioradguard.region.AdRegionScanner
import com.senioradguard.risk.ProtectionLevel
import com.senioradguard.risk.RiskLevel
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

    /** 같은 광고를 보호자에게 반복해서 알리지 않도록 막는다. */
    private val sightings = SightingLog()

    private val urlGuard by lazy { UrlGuard(this) }

    private val installSourceGuard by lazy { InstallSourceGuard(this) }

    /**
     * "방금 광고를 눌렀는가"를 기억한다. 기능 1은 이 플래그가 있을 때만 발동한다 —
     * 어르신이 스스로 쿠팡을 켠 경우까지 경고하면 멀쩡한 행동을 방해하게 된다.
     */
    private val adClickTracker = AdClickTracker()

    /**
     * 주소가 바뀐 흔적으로 "광고를 거쳐 왔는가"를 본다.
     *
     * 클릭 이벤트만으로는 웹 광고를 잡을 수 없다 — 크롬은 웹 광고를 눌러도
     * TYPE_VIEW_CLICKED를 보내지 않는다(실기기 확인). 네이티브 앱에서는 클릭
     * 이벤트가 오므로 둘을 함께 쓴다.
     */
    private val chainTracker = RedirectChainTracker()

    /**
     * 광고를 누른 뒤 "어디로 가는지" 지켜보는 창이 열린 시각.
     *
     * 화면이 바뀌는 순간 한 번만 보면 놓친다. 크롬은 WINDOW_STATE_CHANGED를
     * 보낼 때 주소창을 아직 옛 주소로 두고 있고, 광고는 대개 중간 도메인을
     * 한두 번 거쳐 최종 목적지에 닿는다. 클릭 플래그가 유효했던 순간부터
     * 잠시 동안 여러 번 확인한다.
     */
    private var redirectWatchUntil = 0L

    /**
     * 마지막으로 검사한 주소. 브라우저 안에서 주소가 바뀌었는지 판단하는 데 쓴다.
     *
     * **크롬 안에서 페이지가 바뀌는 것은 TYPE_WINDOW_STATE_CHANGED를 만들지 않는다.**
     * 창(액티비티)이 그대로이기 때문이다. 그 이벤트는 앱이 바뀔 때만 온다 — 광고를
     * 눌러 쿠팡 "앱"이 열리면 오지만, 크롬이 쿠팡 "사이트"로 이동하면 오지 않는다.
     * 그래서 스캔 경로에서 주소 변화를 함께 본다.
     */
    @Volatile
    private var lastCheckedHost: String? = null

    /**
     * 글자 없는 광고 이미지를 화면에서 읽는다. API 30부터만 가능하다 —
     * takeScreenshot이 그때 생겼고, 그 아래에서는 OCR 없이 동작한다.
     */
    private val ocrScanner by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) OcrScanner(this) else null
    }

    /** 이미 경고한 도메인. 같은 페이지에서 스크롤할 때마다 경고하면 쓸 수 없다. */
    private val warnedHosts = SightingLog()

    /**
     * 보호 강도. 보호자가 바꾸면 원격에서 내려오지만, 지금은 로컬 설정만 읽는다.
     * (원격 동기화는 Phase 2-A의 가족 계정 구조가 들어온 뒤에 붙인다.)
     */
    private fun protectionLevel(): ProtectionLevel =
        ProtectionLevel.of(
            getSharedPreferences("settings", MODE_PRIVATE)
                .getInt(PREF_PROTECTION_LEVEL, ProtectionLevel.DEFAULT.value)
        )

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
            // 삼성 인터넷은 광고 감지 대상이 아니지만(웹 콘텐츠를 트리에 안 내놓는다)
            // 주소창은 읽히므로 도메인 대조를 위해 이벤트만 받는다.
            // 감지 대상(targetApps) 말고도 "화면이 떴다"만 알면 되는 패키지들이 있다.
            // 쇼핑 앱·설치 화면은 광고를 훑지 않고 전환 사실만 본다.
            packageNames = (
                targetApps + storePackages + UrlGuard.URL_BAR_IDS.keys +
                    RedirectRules.AD_REDIRECT_PACKAGES + InstallSourceGuard.INSTALLER_PACKAGES
                ).toTypedArray()
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
            // 광고 위를 눌렀는지 본다. 접근성 이벤트는 손가락 좌표를 주지 않으므로
            // 눌린 노드의 화면 영역과 광고 영역이 겹치는지로 판단한다.
            val clicked = event.source?.let { node ->
                Rect().also { node.getBoundsInScreen(it) }.toBounds()
            }
            if (adClickTracker.onClick(clicked)) {
                Log.i(TAG, "광고 클릭 감지 — 화면 전환 감시 시작")
            }
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

            // 기능 3 — APK 설치 화면. 출처가 Play 스토어가 아니면 끼어든다.
            if (installSourceGuard.isInstallerScreen(pkg)) {
                warnUnknownInstall()
                return
            }

            // 기능 1 — 광고를 눌렀다면 잠시 목적지를 지켜본다.
            if (adClickTracker.consumePendingClick()) {
                redirectWatchUntil = SystemClock.uptimeMillis() + REDIRECT_WATCH_MS
                Log.i(TAG, "광고 클릭 후 이동 감시 시작")
            }
            checkRedirect(pkg)
            for (d in REDIRECT_RECHECK_MS) {
                handler.postDelayed({ checkRedirect(currentPackage()) }, d)
            }

            rootInActiveWindow?.let { root ->
                // 기능 2 — 악성 도메인 대조. 주소가 바뀌었을 때만 본다.
                val host = urlGuard.hostOf(root)
                if (host != null) scope.launch { checkHost(host) }
            }
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
                scrollBetweenScans += dy
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

        // 트리 접근은 이 백그라운드 경로에서 끝낸다 (apply는 메인 스레드).
        lastSourceKey = runCatching { extractor.sourceKeyOf(root) }.getOrNull()
        onHostSeen(lastSourceKey)

        val ocrRegions = readImageAds()

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
                // OCR로 찾은 것은 Layer 1과 같은 근거다 — 화면에 "광고"라고 적혀
                // 있는 것을 읽었을 뿐, 추정이 아니다. 그래서 실선으로 함께 그린다.
                (result.regions + ocrRegions).distinct()
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

    /**
     * 광고를 표시했다는 사실을 보호자에게 남긴다.
     *
     * Layer 1은 화면에 "광고"라고 적혀 있는 것이라 확실하지만 위험하지는 않다(저위험).
     * Layer 2는 추정이므로 확신도에 따라 중·고위험으로 갈린다. 등급이 개입 강도를
     * 정하므로, 여기서 잘못 매기면 알려주기만 하면 될 것을 막아버리게 된다.
     */
    private fun reportSighting(sourceKey: String?, layer: Int, count: Int, risk: RiskLevel) {
        if (sourceKey == null || count == 0) return
        if (!sightings.shouldReport(sourceKey, layer)) return
        AdEventLogger.logAdMarked(sourceKey, layer, risk, count)
    }

    /**
     * 화면에 그려진 "광고" 글자를 OCR로 찾는다. 노드 트리에 안 실리는 글자가 대상이다.
     *
     * 읽어낸 글자는 **AdLabelRules로만** 보낸다. 화면 픽셀에서 뽑은 내용을 Layer 2로
     * 넘기면 이미지 속 글자가 외부 판별기로 나가게 된다 — 온디바이스로 읽는 의미가
     * 사라진다.
     *
     * 스크롤 중에는 돌리지 않는다. 캡처는 초당 1회가 한계라 스크롤을 따라갈 수 없고,
     * 읽는 사이에 표시할 자리가 이미 사라진다.
     */
    private suspend fun readImageAds(): List<Rect> {
        val ocr = ocrScanner ?: return emptyList()
        if (!ocr.ready() || scrollSinceScanStart != 0) return emptyList()

        val lines = ocr.readScreen()
        if (lines.isEmpty()) return emptyList()

        val hits = lines.filter { (text, _) -> AdLabelRules.isAdLabel(text) }
        Log.i(TAG, "ocr 줄=${lines.size} 광고=${hits.size}")
        if (hits.isEmpty()) return emptyList()

        // "광고" 두 글자를 감싸는 상자는 광고 자체가 아니라 라벨이다. 그 자리를
        // 기준으로 아래쪽 배너만큼 넓혀 광고 영역으로 삼는다. 트리가 없어
        // Layer 1처럼 진짜 컨테이너를 찾을 수는 없으므로 근사치다.
        val screenWidth = resources.displayMetrics.widthPixels
        val bannerHeight = (resources.displayMetrics.heightPixels * OCR_BANNER_RATIO).toInt()
        return hits.map { (_, box) ->
            Rect(0, box.top, screenWidth, box.top + bannerHeight)
        }
    }

    private fun apply(confirmed: List<Rect>, guessed: List<Rect>) {
        handler.removeCallbacks(recheck)
        if (confirmed.isNotEmpty() || guessed.isNotEmpty()) handler.postDelayed(recheck, RECHECK_MS)

        // 스캔이 도는 동안 굴러간 만큼 결과를 되민다 (안 그러면 테두리가 뒤로 튄다)
        val dy = -scrollSinceScanStart
        // 고정 배너는 스캔이 도는 동안에도 안 움직였으므로 되밀면 안 된다.
        // 되밀면 좌표가 원본에서 벗어나 오버레이가 "고정"으로 알아보지 못하고,
        // 결국 스크롤 때마다 함께 밀려 엉뚱한 곳을 가리킨다.
        val pinned = pinnedAmong(confirmed + guessed)
        borderOverlay.pinnedRegions = pinned
        val shifted = confirmed.shiftedBy(dy, pinned)
        borderOverlay.show(AdMarkStyle.CONFIRMED, shifted)
        borderOverlay.show(AdMarkStyle.AI_GUESS, guessed.shiftedBy(dy, pinned))

        confirmedRegions = shifted
        aiRegions = guessed
        adClickTracker.recordAdRegions((shifted + guessed).map { it.toBounds() })
        reportSighting(lastSourceKey, layer = 1, count = shifted.size, risk = RiskLevel.LOW)
        // 캐시로 되살린 표시는 확신도를 모른다. 중위험으로 둔다 —
        // 모르는 것을 고위험으로 올리면 사용자를 근거 없이 막게 된다.
        reportSighting(lastSourceKey, layer = 2, count = guessed.size, risk = RiskLevel.MEDIUM)
        scheduleLayer2()
    }

    private fun Rect.toBounds() = AdClickTracker.Bounds(left, top, right, bottom)

    private fun List<Rect>.shiftedBy(dy: Int, pinned: Set<Rect> = emptySet()): List<Rect> =
        if (dy == 0 || isEmpty()) this
        else map { if (it in pinned) it else Rect(it).apply { offset(0, dy) } }

    /** 직전 스캔이 낸 원본 영역과, 그 사이 화면이 굴러간 양. */
    private var prevScanRegions: List<Rect> = emptyList()
    private var scrollBetweenScans = 0

    /**
     * 스크롤을 따라 움직이지 않는 광고를 골라낸다. 화면에 고정된 상·하단 배너다.
     *
     * 화면이 굴렀는데도 같은 자리에 그대로 있으면 고정된 것이다. "화면 가장자리에
     * 붙어 있으면 고정"으로 판정하지 않는 이유는, 본문과 함께 구르는 광고도 지나가는
     * 길에 가장자리에 걸치기 때문이다. 두 스캔을 비교하면 그 착각이 없다.
     *
     * 판정이 틀려도 손해는 한쪽뿐이다. 고정인데 못 알아보면 지금까지처럼 테두리가
     * 잠시 어긋나고, 고정이 아닌데 고정으로 보면 테두리가 잠시 안 따라온다.
     * 다음 스캔이 어느 쪽이든 바로잡는다.
     */
    private fun pinnedAmong(regions: List<Rect>): Set<Rect> {
        val scrolled = scrollBetweenScans
        val prev = prevScanRegions
        prevScanRegions = regions.map { Rect(it) }
        scrollBetweenScans = 0

        if (kotlin.math.abs(scrolled) < MIN_SCROLL_TO_JUDGE_PIN || prev.isEmpty()) {
            // 화면이 안 굴렀으면 고정인지 아닌지 구분할 근거가 없다. 직전 판정을 유지한다.
            return borderOverlay.pinnedRegions
        }

        return regions.filterTo(mutableSetOf()) { r ->
            prev.any { kotlin.math.abs(it.top - r.top) <= PIN_TOLERANCE_PX }
        }
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

    /** 스캔 코루틴이 읽고 메인 스레드가 쓴다 */
    @Volatile
    private var confirmedRegions: List<Rect> = emptyList()

    /** 지금 점선으로 표시 중인 영역. "광고 모두 닫기"가 대상으로 쓴다. */
    private var aiRegions: List<Rect> = emptyList()

    /** 마지막 스캔에서 읽은 출처(도메인/패키지). 보호자 기록의 중복 제거 키다. */
    @Volatile
    private var lastSourceKey: String? = null

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
                        aiRegions = result.regions
                        // 방금 판별한 결과라 확신도를 안다. 표시된 것 중 가장 높은
                        // 확신도로 등급을 매긴다 — 하나라도 고위험이면 고위험이다.
                        val top = result.traces
                            .filter { it.marked }
                            .maxOfOrNull { it.finalConfidence } ?: 0f
                        reportSighting(
                            candidates.firstOrNull()?.sourceKey,
                            layer = 2,
                            count = result.regions.size,
                            risk = RiskLevel.ofConfidence(top)
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

    /**
     * 기본 OFF 옵트인. 화면 텍스트가 외부로 나가므로 사용자가 켜야만 동작한다.
     * 보호 강도 1단계에서는 토글과 무관하게 Layer 2를 돌리지 않는다 —
     * 보호자가 "라벨만"으로 낮춰뒀는데 어르신 기기가 계속 외부로 보내면 안 된다.
     */
    private fun isAiEnabled(): Boolean =
        protectionLevel().usesAi &&
            getSharedPreferences("settings", MODE_PRIVATE).getBoolean(PREF_AI_CLASSIFY, false)

    private fun currentPackage(): String? = rootInActiveWindow?.packageName?.toString()

    /**
     * 스캔이 읽은 주소가 직전과 다르면 화면이 바뀐 것으로 본다.
     *
     * 브라우저 안의 이동은 창 전환 이벤트를 만들지 않으므로, 주소가 바뀌었다는
     * 사실 자체가 유일한 신호다.
     */
    private fun onHostSeen(host: String?) {
        if (host == null || host == lastCheckedHost) return
        lastCheckedHost = host
        Log.i(TAG, "주소 변경 감지: $host")

        // 광고를 거쳐 왔다는 증거는 둘 중 하나면 된다.
        //   - 광고 노드를 눌렀다 (네이티브 앱에서 오는 신호)
        //   - 중계 도메인을 스쳐 지나왔다 (웹에서 유일하게 남는 흔적)
        val viaChain = chainTracker.onHost(host)

        handler.post {
            if (adClickTracker.consumePendingClick() || viaChain) {
                redirectWatchUntil = SystemClock.uptimeMillis() + REDIRECT_WATCH_MS
                Log.i(TAG, "광고 경유 판단 — 이동 감시 시작 (중계=$viaChain)")
            }
            checkRedirect(currentPackage())
        }
        scope.launch { checkHost(host) }
    }

    /**
     * 감시 창이 열려 있는 동안 목적지가 쇼핑몰인지 본다.
     *
     * 창이 닫혀 있으면(= 광고를 누른 적이 없으면) 아무것도 하지 않는다.
     */
    private fun checkRedirect(pkg: String?) {
        if (SystemClock.uptimeMillis() > redirectWatchUntil) return

        if (RedirectRules.isRedirectPackage(pkg)) {
            redirectWatchUntil = 0
            warnRedirect(pkg!!)
            return
        }
        val host = rootInActiveWindow?.let { urlGuard.hostOf(it) }
        Log.i(TAG, "이동 감시: pkg=$pkg host=$host")
        if (RedirectRules.isRedirectHost(host)) {
            redirectWatchUntil = 0
            warnRedirect(host!!)
        }
    }

    /**
     * 기능 1 — 광고를 눌러 쇼핑몰로 넘어왔다고 알린다.
     *
     * 쿠팡·알리는 정상 쇼핑몰이다. 막을 대상이 아니라, **어르신이 광고를 누른 줄
     * 모르고 끌려왔을 수 있다는 사실**을 알리는 것이 목적이다. 계속 보겠다면 둔다.
     */
    private fun warnRedirect(key: String) {
        val name = RedirectRules.displayName(key)
        Log.i(TAG, "광고 경유 이동 감지: $key")

        overlayManager.showWarning(
            message = "광고를 통해 ${name}(으)로 이동했습니다.\n" +
                "원래 보던 화면으로 돌아갈까요?",
            packageName = rootInActiveWindow?.packageName?.toString().orEmpty(),
            onConfirm = { AdEventLogger.logIgnored(key) },
            onBlock = { performGlobalAction(GLOBAL_ACTION_BACK) },
            currentForegroundPackage = { rootInActiveWindow?.packageName?.toString() },
            onForceHome = { performGlobalAction(GLOBAL_ACTION_HOME) },
            blockLabel = "돌아가기",
            confirmLabel = "그냥 보기"
        )
        AdEventLogger.logStoreRedirect(key)
    }

    /**
     * 기능 3 — Play 스토어가 아닌 곳에서 온 APK 설치 화면에 끼어든다.
     *
     * 시스템 설치 버튼은 누를 수도 가릴 수도 없다. 경고를 덮어 "이게 무슨
     * 화면인지" 알리고 돌아갈 길을 주는 것까지가 우리 몫이다. 완전 차단은
     * Play 정책 위반이라 하지 않는다.
     */
    private fun warnUnknownInstall() {
        if (installSourceGuard.isFromPlayStore()) return
        Log.i(TAG, "알 수 없는 출처의 설치 화면 감지")

        overlayManager.showWarning(
            message = "앱을 설치하려고 합니다.\n" +
                "플레이스토어가 아닌 곳에서 받은 앱입니다.\n" +
                "모르는 앱이면 설치하지 마세요.",
            packageName = rootInActiveWindow?.packageName?.toString().orEmpty(),
            onConfirm = { AdEventLogger.logIgnored("unknown_installer") },
            onBlock = { performGlobalAction(GLOBAL_ACTION_BACK) },
            currentForegroundPackage = { rootInActiveWindow?.packageName?.toString() },
            onForceHome = { performGlobalAction(GLOBAL_ACTION_HOME) },
            // "그래도 설치"를 넣지 않는다. 알 수 없는 출처의 APK에 그 버튼을 두면
            // 경고가 형식이 되고, 어르신은 대개 오른쪽 버튼을 누른다.
            blockLabel = "설치 취소하고 돌아가기",
            confirmLabel = null
        )
        AdEventLogger.logInstallBlocked("unknown_installer")
    }

    /**
     * 지금 페이지의 도메인이 차단 목록에 있는지 본다. 보호 강도 3단계에서만 돈다.
     *
     * 링크를 누르는 순간이 아니라 **도착한 뒤**에 확인한다 — 접근성 트리에는 href가
     * 없어서 누르기 전에 알 방법이 없다([UrlGuard] 주석 참고). 페이지는 이미 열렸지만
     * 개인정보를 넣거나 앱을 설치하기 전에 멈춰 세울 수 있다.
     */
    private suspend fun checkHost(host: String?) {
        if (!protectionLevel().usesUrlBlock) return
        if (host == null || !warnedHosts.shouldReport(host, layer = 3)) return
        if (!urlGuard.isBlocked(host)) return

        Log.i(TAG, "차단 도메인 감지: $host")
        AdEventLogger.logBlockedDomain(host, blocked = true)
        withContext(Dispatchers.Main) {
            overlayManager.showWarning(
                message = "위험한 사이트일 수 있어요!\n[$host]\n" +
                    "광고·사기 사이트 목록에 있습니다.\n뒤로 돌아갈까요?",
                // packageName은 currentForegroundPackage()와 대조하는 값이라
                // 도메인이 아니라 실제 패키지를 넘겨야 한다. 도메인을 넘기면
                // 2단계 홈 이동이 영영 발동하지 않는다.
                packageName = rootInActiveWindow?.packageName?.toString().orEmpty(),
                onConfirm = { AdEventLogger.logIgnored(host) },
                onBlock = { performGlobalAction(GLOBAL_ACTION_BACK) },
                currentForegroundPackage = { rootInActiveWindow?.packageName?.toString() },
                onForceHome = { performGlobalAction(GLOBAL_ACTION_HOME) },
                // 버튼은 하나뿐이다. 알려진 사기 사이트에 "그냥 보기"를 주면
                // 그 선택지가 있다는 사실만으로 사용자가 눌러도 된다고 읽는다.
                blockLabel = "안전하게 돌아가기",
                confirmLabel = null
            )
        }
    }

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

        /**
         * 고정 배너인지 판정하려면 화면이 최소 이만큼은 굴러야 한다.
         * 몇 px 흔들린 것으로 판정하면 스크롤하지 않은 화면을 전부 고정으로 본다.
         */
        private const val MIN_SCROLL_TO_JUDGE_PIN = 80

        /** 두 스캔 사이 위치가 이 이내면 "안 움직였다"로 본다 */
        private const val PIN_TOLERANCE_PX = 12

        /**
         * 광고를 누른 뒤 목적지를 지켜보는 시간. 광고는 중간 도메인을 한두 번
         * 거치고, 느린 회선에서는 최종 페이지까지 몇 초가 걸린다.
         */
        private const val REDIRECT_WATCH_MS = 8_000L

        /** 감시 창 안에서 다시 확인할 시각. 주소창이 늦게 갱신되는 것을 메운다. */
        private val REDIRECT_RECHECK_MS = longArrayOf(500, 1500, 3000, 5000, 7000)

        /** 스크롤 경로에서 도는 Layer 2 후보 추출의 시간 상한 */
        private const val SCROLL_EXTRACT_BUDGET_MS = 150L

        /** 스크롤이 이만큼 멈춰 있어야 Layer 2 판별을 돌린다. */
        private const val LAYER2_IDLE_MS = 600L

        // ── "광고 모두 닫기"가 X 버튼을 찾을 때 쓰는 값들 ──

        /** 광고 영역 안에서 닫기 버튼을 찾아 내려가는 최대 깊이 */
        /** OCR로 찾은 "광고" 라벨 아래로 광고 영역으로 볼 높이 (화면 대비). */
        private const val OCR_BANNER_RATIO = 0.12

        private const val CLOSE_SEARCH_DEPTH = 25

        /** 이보다 큰 것은 닫기 버튼이 아니라 광고 자체일 가능성이 높다 (dp). */
        private const val CLOSE_MAX_DP = 72

        /**
         * 이 비율 이상 화면을 덮으면 전면 광고로 보고 뒤로 가기를 허용한다.
         * 배너에까지 뒤로 가기를 쓰면 광고가 아니라 보던 페이지가 닫힌다.
         */
        private const val FULLSCREEN_RATIO = 0.75

        private val CLOSE_TEXTS = setOf(
            "닫기", "광고 닫기", "close", "dismiss", "skip ad", "광고 건너뛰기", "✕", "×"
        )

        private val CLOSE_ID_TOKENS = setOf(
            "close", "dismiss", "btnclose", "closebutton", "adclose", "cancel", "skip"
        )

        /** "settings" prefs — 보호 강도(1/2/3). 보호자 설정과 공유한다. */
        const val PREF_PROTECTION_LEVEL = "protection_level"

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
