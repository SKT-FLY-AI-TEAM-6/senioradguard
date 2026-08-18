package com.senioradguard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import com.senioradguard.agent.CardText
import com.senioradguard.agent.GeminiClassifier
import com.senioradguard.agent.StubClassifier
import com.senioradguard.analysis.AdEntryDetector
import com.senioradguard.analysis.AdvertiserMark
import com.senioradguard.analysis.AnalyzedPage
import com.senioradguard.analysis.ClaudeApiJudge
import com.senioradguard.analysis.LlmRiskJudge
import com.senioradguard.analysis.OnDeviceLlm
import com.senioradguard.analysis.RuleBasedUrlAnalyzer
import com.senioradguard.analysis.ShieldReason
import com.senioradguard.analysis.UrlRiskAnalyzer
import com.senioradguard.analysis.UrlRiskRules
import com.guradian.serp.SerpFeature
import com.senioradguard.detector.BlacklistHardSignal
import com.senioradguard.detector.BlacklistSeeder
import com.senioradguard.detector.UrlGuard
import com.senioradguard.detector.db.AdFingerprintLink
import com.senioradguard.detector.db.AppDatabase
import com.senioradguard.detector.db.UrlVerdict
import com.senioradguard.guard.InstallGuard
import com.senioradguard.guard.InstallSourceGuard
import com.senioradguard.guard.NavigationGuard
import com.senioradguard.guard.RelayTransitTracker
import com.senioradguard.logger.AdEventLogger
import com.senioradguard.logger.GuardianEventLogger
import com.senioradguard.overlay.AdBorderOverlay
import com.senioradguard.overlay.AdCoverOverlay
import com.senioradguard.overlay.BackPromptOverlay
import com.senioradguard.overlay.AdMarkStyle
import com.senioradguard.overlay.GuardAlertOverlay
import com.senioradguard.overlay.OverlayManager
import com.senioradguard.overlay.ShieldOverlay
import com.senioradguard.overlay.TrackedBorderOverlay
import com.senioradguard.region.AdRegionScanner
import com.senioradguard.region.Anchor
import com.senioradguard.risk.RiskAssessment
import com.senioradguard.risk.RiskLevel
import com.senioradguard.risk.UrlNormalizer
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
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

    /** 닫기 버튼이 없는 광고를 가리는 창 — [closeAllAds]가 쓴다 */
    private val adCover by lazy { AdCoverOverlay(this) }

    /** 화면이 꺼졌다 — 커버가 잠금화면·홈 위에 남지 않게 즉시 걷는다 */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            adCover.hide()
        }
    }

    /**
     * Layer 1 확정 광고의 테두리 — 앵커 추적으로 광고에 fit하게 붙는다
     * (ad_claude_fable 엔진 이식). AI 점선(AdMarkStyle.AI_GUESS)은 기존
     * borderOverlay가 계속 담당한다.
     */
    private val trackedBorders by lazy {
        TrackedBorderOverlay(this).apply { onAnchorLost = { requestScan() } }
    }

    /** [confirmedRegions]와 1:1 — 그 좌표를 만들어낸 노드. 스크롤 추적에 쓴다 */
    @Volatile
    private var confirmedAnchors: List<Anchor?> = emptyList()

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

    // ──────────────────────────────────────────────────────────
    // 신규 기능 상태 (merge/guardian-all) — 얹을 것 1·2·3.
    // phase4 로직은 건드리지 않고 이 상태들과 신규 분기·함수만 추가한다.
    // ──────────────────────────────────────────────────────────

    /**
     * 얹을 것 3 — 검색 결과 위험도. 자립 패키지(com.guradian.serp)의 단일 입구.
     * 최고 위험(HIGH) 판정이 새 호스트에서 나올 때만 원격 승격한다 —
     * 호스트·검색어는 기기 밖으로 나가지 않는다.
     */
    private val serp by lazy {
        SerpFeature(
            this, scope, BuildConfig.GEMINI_API_KEY,
            onHighRisk = { GuardianEventLogger.logSearchRiskDetected() }
        )
    }

    /** 얹을 것 1 — 주소창 도메인을 14만 건 차단 목록과 대조한다. */
    private val urlGuard by lazy { UrlGuard(this) }

    /**
     * 얹을 것 2 — DBD 설치 차단. phase4 [installGuard](광고→Play스토어 흐름)와
     * 트리거가 완전히 다르고 상태를 공유하지 않는다.
     */
    private val installSourceGuard = InstallSourceGuard()

    /**
     * 중계 경유 추적 (phase6 이식). 클릭 이벤트가 없는 웹 광고 이동이 남기는
     * 유일한 흔적(스쳐 지나간 제3의 도메인)을 본다. 결말 UX는 phase4의
     * 가림막·복귀 바가 담당하므로 이쪽은 판단 재료만 만든다.
     */
    private val relayTracker = RelayTransitTracker()

    /** 버튼 하나짜리 전체화면 경고 — 고위험 확정(차단 도메인·DBD 설치)에만 쓴다. */
    private val guardAlert by lazy { GuardAlertOverlay(this) }

    /**
     * 설치 확인 화면 직전에 떠 있던 앱. 신규 분기 ①이 갱신하고 ③이 설치 출처
     * 판정에 쓴다. 인스톨러 자신은 갱신에서 제외해야 "직전" 값이 살아남는다.
     */
    @Volatile
    private var foregroundBeforeInstaller: String? = null

    /** 이미 경고한 차단 도메인 — 같은 페이지에 머무는 동안 반복 경고를 막는다. */
    private val warnedBlockedHosts = SightingLog()

    /** 주소창 확인 스로틀 — 이벤트마다 루트 IPC를 부르면 부담이 된다. */
    private var lastHostWatchAt = 0L

    /** 마지막으로 대조한 브라우저 호스트 — 같은 호스트는 다시 보지 않는다. */
    private var lastWatchedHost: String? = null

    // ──────────────────────────────────────────────────────────
    // 광고발 진입 감지 + 가림막 (4b-①②)
    // ──────────────────────────────────────────────────────────

    private val entryDetector = AdEntryDetector(SystemClock::uptimeMillis)

    // 테두리 오버레이와 같은 이유로 서비스 자신의 컨텍스트여야 한다
    private val shieldOverlay by lazy { ShieldOverlay(this) }

    private val urlVerdictDao by lazy { AppDatabase.getInstance(this).urlVerdictDao() }

    private val fingerprintDao by lazy { AppDatabase.getInstance(this).adFingerprintLinkDao() }

    /** 격리 분석기 — 규칙 기반 1차 판정 (빠르고, LLM이 있어도 안전핀으로 남는다) */
    private val analyzer: UrlRiskAnalyzer = RuleBasedUrlAnalyzer()

    /**
     * 온디바이스 LLM(4c). 모델 파일이 있을 때만 동작하며, 규칙이 저위험이라
     * 한 페이지의 **상향 판단**만 맡는다 — 내리는 경로는 없다.
     */
    private val onDeviceLlm by lazy { OnDeviceLlm(this) }

    // ── 지문 연계 (4b-④) — 재등장 광고를 클릭 전에 알아본다 ──

    /**
     * [confirmedRegions]와 1:1 — 각 광고 카드의 지문 키 목록.
     * [0]은 카드 전체 문구 지문, 있다면 [1]은 광고주 표기줄 지문(거친 지문).
     * 같은 캠페인이 문구를 바꿔 돌려도(실측: 굿리치 변형 4종이 지문 4개가 됨)
     * 광고주 표기줄은 같아서 거친 지문이 캠페인 전체를 이어준다.
     * 빈 목록 = 수집 실패. 스캔 스레드에서 계산.
     */
    @Volatile
    private var confirmedFpKeys: List<List<String>> = emptyList()

    /** 지문을 마지막으로 모은 출처. 출처·개수가 그대로면 다시 모으지 않는다 (IPC 절약) */
    private var fpSourceKey: String? = null

    /**
     * 지문을 모을 당시 각 영역의 높이. 높이가 크게 달라졌으면(화면 가장자리에
     * 일부만 걸쳤다가 전부 드러난 경우) 그때의 지문은 잘린 텍스트로 만든
     * 불완전한 것이므로 다시 모은다 — 스크롤 후 주의 표시가 광고로 되돌아가던
     * 원인이다 (실측).
     */
    private var fpHeights: List<Int> = emptyList()

    /** 직전 스캔에서 모은 지문 후보. 두 번 연속 같아야 [fpFrozen]으로 확정한다 */
    private var fpCandidate: List<List<String>> = emptyList()

    /**
     * 지문이 확정됐는가. 페이지 로드 중 첫 수집은 텍스트가 일부만 담기기도
     * 해서, 그대로 굳히면 같은 광고가 될 때도 안 될 때도 있는 간헐 실패가
     * 된다 (사용자 실사용 리포트). 두 번 연속 같은 결과가 나올 때까지
     * 스캔마다 다시 모은다.
     */
    private var fpFrozen = false

    /** 마지막으로 광고가 떠 있던 화면의 지문 키들. 이동 후에는 이게 "출발 페이지의 광고"다 */
    @Volatile
    private var lastAdFpKeys: List<List<String>> = emptyList()
    @Volatile
    private var lastAdFingerprintsAt = 0L

    /** 클릭 좌표로 특정된 광고의 지문 키들 */
    private var pendingFpKeys: List<String> = emptyList()

    /** 이번 가림막이 다루는 광고의 지문 키들 — 판정이 나오면 전부 연계 저장한다 */
    private var shieldFpKeys: List<String> = emptyList()

    /** 광고 클릭 직전에 주소창이 보여주던 URL. 이동 감지의 비교 기준이다. */
    private var preClickUrl: String? = null

    /** "광고 모두 닫기"가 광고 안의 X를 대신 누르는 동안은 클릭 판별을 멈춘다 */
    private var suppressAdClickUntil = 0L

    private var shieldActive = false

    /**
     * 이번 가림막의 결말이 이미 정해졌는가. 즉시 판정 패스트패스와 안정화 후
     * 경로가 경합할 수 있어(둘 다 finishShield를 부른다) 먼저 온 쪽만 이긴다.
     */
    private var shieldResolved = false

    /** 가림막을 띄운 진입 URL — 앱 딥링크로 주소창을 잃었을 때의 분석 대상 */
    private var shieldEntryUrl: String? = null

    /** 가림막을 띄운 이동의 출발 호스트 — 주소창이 여기로 돌아오면 딥링크 이탈이다 */
    private var shieldCameFrom: String? = null

    /**
     * 복귀 바의 "뒤로 가기"가 되돌아가야 할 곳 — 광고를 누르기 전에 읽던 사이트.
     * 전체 호스트(m.news.nate.com)일 수도, 중계 추적이 준 eTLD+1(nate.com)일 수도
     * 있어 비교는 접미사로 한다 ([atReturnTarget]).
     */
    private var returnTargetSite: String? = null

    /** 복귀 반복의 마감 시각. 0이면 복귀 중이 아니다 */
    private var returnDeadline = 0L

    /** 사전 표시 로그 중복 억제용 — 직전에 기록한 건수 */
    private var lastPreWarnedLogged = -1

    /**
     * 이번 가림막의 광고가 사용자를 다른 앱·화면으로 끌고 갔는가.
     * 참이면 저위험이어도 "이전 화면으로 돌아가기" 선택지를 남긴다 —
     * 팀원 ad_claude_fable NavigationGuard의 강제 이동 복귀 안내를 이식한 것.
     * 그쪽은 이동 자체를 별도 추적하지만, 우리는 딥링크 이탈 감지가 이미
     * 같은 순간을 더 정확히 알므로 안내 UX만 가져온다.
     */
    private var shieldDeepLinked = false

    /** 선택 대기의 기준 화면(패키지) — 여기를 떠나면 가림막을 걷는다 */
    private var choiceScreenPkg: String? = null

    /**
     * 강제 이동 감시 (ad_claude_fable NavigationGuard 이식). 광고 신호가 없는
     * 사이트 강제 이동(팝업·리다이렉트)에도 "뒤로 가기" 안내를 준다 — 가림막은
     * 광고 확신이 있을 때만 뜨므로 이쪽이 나머지 구멍을 메운다. 가림막이 뜬
     * 이동은 가림막이 우선이고, 끝날 때 안내도 함께 걷는다(dismissShieldNow).
     */
    private val navGuard = NavigationGuard()
    private val backPrompt by lazy { BackPromptOverlay(this) }

    /** 스캔 스레드가 쓰고 apply(메인)가 읽는다 */
    @Volatile
    private var navShowBack = false

    private val navRecheck = Runnable { requestScan() }

    /**
     * 딥링크 복귀 바가 떠 있는 동안 참 — apply()가 스캔마다 바를 걷지 않게 한다.
     * (NavigationGuard발 바는 navShowBack이 관리하고, 이 바는 시간·버튼이 관리)
     */
    private var backPromptSticky = false

    private val backPromptTimeout = Runnable {
        backPromptSticky = false
        backPrompt.hide()
    }

    /**
     * 선택 버튼(안전하게 돌아가기 / 그냥 볼게요)을 띄우고 사용자 결정을
     * 기다리는 중인가. 이 상태는 시간이 지나도 걷지 않는다 — 위험 안내가
     * 사용자 결정 없이 사라지면 안내의 의미가 없다. 대신 사용자가 뒤로가기·
     * 홈으로 스스로 화면을 떠나면 함께 걷는다 (가림막이 폰을 잠그면 안 된다).
     */
    private var shieldAwaitingChoice = false

    private var navPollsLeft = 0
    private var urlSettleReadsLeft = 0
    private var lastSettleUrl: String? = null

    /** 직전 스캔에서 본 크롬 웹 호스트. 바뀌면 페이지 이동이다. */
    private var prevChromeHost: String? = null

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
            // 신규 기능(merge/guardian-all)이 "화면이 떴다"를 알아야 하는
            // 패키지들을 더한다 — 광고 스캔 대상이 늘어나는 것은 아니다
            // (scanWork의 targetApps 검사는 그대로다).
            packageNames = (
                targetApps + storePackages +
                    SerpFeature.PACKAGES +                  // 구글 앱 — 검색 결과 위험도
                    UrlGuard.URL_BAR_IDS.keys +             // 삼성 인터넷 — 주소창 대조만
                    InstallSourceGuard.INSTALLER_PACKAGES   // 시스템 인스톨러 — DBD 감지
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

        // 화면이 꺼지면 커버를 즉시 걷는다.
        //
        // **접근성 이벤트만으로는 이 순간을 알 수 없다.** 화면이 꺼지고 잠금화면·
        // 홈으로 나가는 경로는 우리 packageNames에 없어서 이벤트가 아예 오지
        // 않는다. 그래서 커버가 홈 화면 위에 그대로 남았다(실사용 리포트) —
        // 그냥 남는 것도 아니고 터치를 삼키는 창이라 더 나쁘다. SerpFeature가
        // 배지에서 같은 구멍을 같은 방식으로 메운다.
        runCatching {
            registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        }

        // 블랙리스트 첫 시드 (14만 건). 최초 1회만 실제로 돌고 이후에는
        // 플래그만 읽고 바로 돌아온다 — BlacklistSeeder 주석 참고.
        scope.launch { BlacklistSeeder.seedIfNeeded(this@AdGuardAccessibilityService) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return

        // ──────────────────────────────────────────────────────
        // 신규 분기 ①~③·⑦ (merge/guardian-all). 기존 phase4 로직(아래 ④~⑧)은
        // 내용을 바꾸지 않고 순서 그대로 둔다.
        // ──────────────────────────────────────────────────────

        // ① 포그라운드 갱신 — 설치 확인 화면 "직전"에 떠 있던 앱이 곧 설치
        //    출처다. 인스톨러 자신은 제외해야 직전 값이 덮이지 않는다.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            !installSourceGuard.isInstallerScreen(pkg)
        ) {
            foregroundBeforeInstaller = pkg
        }

        // ② 검색 결과 위험도 — targetApps 필터보다 앞이어야 구글 앱 이벤트가
        //    닿는다. HIGH 위험 오버레이(가림막·전체화면 경고)가 떠 있는 동안은
        //    배지를 억제한다 — 경고 위에 배지까지 겹치면 어느 것도 읽히지 않는다.
        if (shieldActive || guardAlert.isShowing) serp.hide() else serp.onEvent(event, pkg)

        // ③ DBD 설치 차단 — 설치 확인 화면이 스토어를 거치지 않고 떴다.
        //    광고→Play스토어 정상 흐름(아래 ④ InstallGuard)과 완전히 별개다.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            installSourceGuard.isInstallerScreen(pkg)
        ) {
            warnDirectDownloadInstall()
            return
        }

        // ⑦ 악성 도메인 대조 + 중계 경유 추적 — 삼성 인터넷은 targetApps에
        //    없어 아래 ⑤ 필터보다 앞에서 처리해야 한다 (주소창은 읽힌다).
        //    크롬의 같은 탭 이동은 창 전환 이벤트를 만들지 않으므로
        //    CONTENT_CHANGED에서도 (스로틀을 걸고) 주소창을 본다.
        if (pkg in UrlGuard.URL_BAR_IDS.keys &&
            (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        ) {
            watchBrowserHost()
        }

        // ④ Play스토어 화면 → 기존 phase4 InstallGuard 그대로 [무변경]
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
            trackClickForAdEntry(event)
            // 클릭만으로는 화면이 바뀌지 않아 Layer 1이 다시 스캔할 게 없다.
            // (화면이 바뀌면 CONTENT_CHANGED가 따로 온다)
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // 화면이 바뀌었다 — 이전 화면의 광고를 덮던 커버는 여기서 무의미하다
            adCover.hide()
            // 페이지가 새로 떴다. 광고를 나중에 끼워 넣는 사이트를 위해 재스캔을 예약해 둔다.
            handler.removeCallbacks(lazyRescan)
            for (d in LAZY_RESCAN_MS) handler.postDelayed(lazyRescan, d)
            if (pkg == CHROME && !shieldActive) {
                val url = readUrlBar()
                val host = url?.let { UrlNormalizer.hostOf(it) }
                Log.i(TAG, "창 전환: 클릭대기=${entryDetector.hasFreshPending()} url=${url ?: "-"}")
                when {
                    // 광고 클릭 대기 중의 창 전환(앱→브라우저 포함)은 광고발 진입이다
                    entryDetector.hasFreshPending() -> onNavigationDetected(url)
                    // 스캔 주기를 기다리지 않는 이동 감지 — 주소창이 읽히고 호스트가
                    // 바뀌었으면 스캔 경로와 같은 규칙으로 판정한다. 페이지가 뜨는
                    // 순간은 접혔던 주소창이 다시 펴지는 순간이기도 하다.
                    host != null && prevChromeHost != null && host != prevChromeHost -> {
                        Log.i(TAG, "페이지 이동(창 전환): $prevChromeHost → $host")
                        onNavigationDetected(url, cameFromHost = prevChromeHost)
                        prevChromeHost = host
                    }
                }
            }
        }

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            // 커버는 좌표를 추적하지 않는다. 화면이 구르면 광고도 함께 밀려 올라가므로
            // 붙잡을 이유가 없고, 그대로 두면 엉뚱한 본문을 덮는다.
            //
            // **스크롤 양을 보고 판단하면 안 된다.** 크롬 WebView가 보내는 스크롤
            // 이벤트는 deltaY가 전부 -1(UNDEFINED)이라 scrollDeltaY가 0으로 버린다.
            // 처음엔 dy != 0일 때만 걷게 했더니 웹에서는 아무리 스크롤해도 커버가
            // 남았다 — 스크롤이 일어났다는 사실 자체로 충분하다.
            if (adCover.isShowing) adCover.hide()

            val dy = scrollDeltaY(event)
            if (dy != 0) {
                // AI 점선은 스캔을 기다리지 않고 바로 민다 (확정 테두리는 아래
                // 앵커 추적이 실측 좌표로 따라가므로 여기서 건드리지 않는다).
                borderOverlay.offsetBy(-dy)
                // 지금 도는 스캔은 이만큼 구르기 *전* 화면을 읽고 있다. 결과가
                // 돌아왔을 때 그대로 그리면 테두리가 뒤로 튄다 — 보정에 쓴다.
                scrollSinceScanStart += dy
            }
        }

        // 확정 테두리는 이벤트가 올 때마다 앵커 좌표를 8ms 주기로 재측정해 따라간다
        trackedBorders.requestTracking()
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
            navGuard.reset()
            navShowBack = false
            // 커버는 그 앱의 광고 위에 있던 것이다. 다른 앱·홈으로 나갔으면
            // 덮을 대상이 없고, 남으면 엉뚱한 화면에서 터치를 삼킨다.
            withContext(Dispatchers.Main) { adCover.hide() }
            withContext(Dispatchers.Main) { apply(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), false) }
            return
        }

        // 트리 접근은 이 백그라운드 경로에서 끝낸다 (apply는 메인 스레드).
        lastSourceKey = runCatching { extractor.sourceKeyOf(root) }.getOrNull()

        val result = scanner.scan(root)
        if (result.truncated) {
            Log.d(TAG, "scan truncated: visited=${result.visited} ${result.elapsedMs}ms")
        }

        // 강제 이동 감시 — 벽시계가 아니라 부팅 이후 경과 시간(원본 주석 참고)
        navShowBack = navGuard.update(
            root.packageName?.toString() ?: "?",
            result.pageHost,
            result.sawWeb,
            SystemClock.uptimeMillis()
        )

        // 예산이 모자라 도중에 끊긴 결과로는 영역을 갱신하지 않고 직전 영역을 유지한다.
        // 부분적으로만 훑은 화면에서 영역을 갱신하면 그 자체가 오탐이 되기 때문이다.
        //
        // 다만 무한정 유지하면 안 된다. 무거운 페이지에서 스캔이 계속 잘리면 이미 사라진
        // 광고의 테두리가 영영 남는다. 몇 번까지만 붙잡고 그 뒤에는 부분 결과를 받아들인다.
        val shown = confirmedRegions
        val shownAnchors = confirmedAnchors
        val holding =
            result.truncated && shown.isNotEmpty() && truncatedHolds < MAX_TRUNCATED_HOLDS
        if (holding) truncatedHolds++ else truncatedHolds = 0
        val regions = if (holding) shown else result.regions
        val anchors = if (holding) shownAnchors else result.anchors

        // 히스테리시스 — 나타날 때는 즉시, 사라질 때는 잠깐 기다린다.
        var stableAnchors = anchors
        val stable = when {
            regions.isNotEmpty() -> {
                emptySince = 0L
                regions
            }
            shown.isEmpty() -> regions              // 원래 없었으면 그대로 없음
            else -> {
                val now = SystemClock.uptimeMillis()
                if (emptySince == 0L) emptySince = now
                if (now - emptySince < CLEAR_DELAY_MS) {
                    stableAnchors = shownAnchors    // 유지하는 동안에도 추적은 계속돼야 한다
                    shown                           // 아직 기다린다
                } else {
                    emptySince = 0L
                    regions
                }
            }
        }

        // 광고가 보이면 닫기 막대를 띄운다.
        //
        // 예전에는 스캔마다 트리를 한 번 더 훑어 X가 실재하는지 확인하고, 없으면
        // 막대를 감췄다 — 눌러도 "닫기 버튼이 없어요"만 나오는 막대가 소음이라서다.
        // 이제 X가 없으면 가리기로 처리하므로 누르면 **항상** 광고가 사라진다.
        // 그 확인 순회는 존재 이유가 사라졌고, 없애면 스캔이 그만큼 빨라진다.
        val closable = stable.isNotEmpty()

        // ── 지문 (4b-④) — 출처·개수가 그대로면 다시 모으지 않는다.
        // 같은 화면에서 광고가 같은 수로 교체되는 드문 경우는 지문이 한 스캔
        // 늦게 갱신될 수 있는데, 지문은 표시·연계 보조라 치명적이지 않다.
        val src = lastSourceKey ?: "unknown"
        // 주소창이 접혀 출처가 패키지명으로 떨어진 스캔에서는 지문을 만들지 않는다.
        // 같은 광고가 m.news.nate.com|해시 와 com.android.chrome|해시 두 지문을
        // 오가면 연계가 될 때도 안 될 때도 있는 간헐 실패가 된다 (실사용 리포트).
        val srcReliable = '.' in src && src !in targetApps
        // 높이가 20% 넘게 달라진 영역이 있으면 그때 모은 지문은 못 믿는다
        val heightsStable = stable.size == fpHeights.size && stable.indices.all { i ->
            val h = stable[i].height()
            fpHeights[i] > 0 && kotlin.math.abs(h - fpHeights[i]) <= h / 5
        }
        val fpKeys: List<List<String>> = when {
            !srcReliable ->
                if (stable.size == confirmedFpKeys.size) confirmedFpKeys
                else stable.map { emptyList() }

            fpFrozen && src == fpSourceKey && stable.size == confirmedFpKeys.size &&
                confirmedFpKeys.isNotEmpty() && heightsStable &&
                confirmedFpKeys.none { it.isEmpty() } -> confirmedFpKeys

            else -> {
                if (src != fpSourceKey || stable.size != confirmedFpKeys.size || !heightsStable) {
                    // 페이지·광고 구성이 바뀌었다 — 확정을 풀고 처음부터 다시 모은다
                    fpFrozen = false
                    fpCandidate = emptyList()
                }
                fpSourceKey = src
                fpHeights = stable.map { it.height() }
                val gathered = stableAnchors.map { a ->
                    val texts = runCatching { a?.gatherText() }.getOrNull()
                    if (texts == null) {
                        emptyList()
                    } else {
                        val keys = mutableListOf(CardText.cacheKey(src, texts))
                        // 광고주 표기줄(등록번호·심의필)이 있으면 거친 지문도 만든다 —
                        // 문구 변형이 계속 바뀌는 캠페인을 하나로 잇는 열쇠다
                        val adv = AdvertiserMark.advertiserLines(texts)
                        if (adv.isNotEmpty()) keys += CardText.cacheKey("adv|$src", adv)
                        keys
                    }
                }
                // 두 번 연속 같은 결과가 나와야 확정한다 — 로드 중 일부만 담긴
                // 수집이 굳는 것을 막는다
                fpFrozen = gathered.isNotEmpty() && gathered == fpCandidate &&
                    gathered.none { it.isEmpty() }
                fpCandidate = gathered
                val misses = gathered.count { it.isEmpty() }
                if (gathered.isNotEmpty() && misses > 0) {
                    // 진단: 첫 실패 앵커의 상태를 함께 남긴다 (원인 특정용)
                    val firstMiss = gathered.indexOfFirst { it.isEmpty() }
                    val probe = stableAnchors.getOrNull(firstMiss)
                        ?.let { runCatching { it.probe() }.getOrNull() } ?: "anchor=null"
                    Log.i(TAG, "지문 수집 미완 — $misses/${gathered.size} (출처=$src, $probe)")
                }
                gathered
            }
        }

        // 연계된 판정이 있으면 클릭 전에 최종 등급(주의/위험/확인)으로 표시한다.
        // 카드 전체 지문 → 광고주 지문 순서로 조회한다.
        val styles = fpKeys.map { keys ->
            val linked = keys.firstNotNullOfOrNull { k ->
                runCatching { fingerprintDao.findLinkedVerdict(k, System.currentTimeMillis()) }
                    .getOrNull()
            }
            styleFor(linked?.toAssessment()?.level)
        }
        val preWarned = styles.count { it != TrackedBorderOverlay.BorderStyle.AD }
        // 같은 화면에 머무는 동안 스캔마다 반복 출력되면 로그가 홍수가 된다 —
        // 값이 바뀔 때만 남긴다 (실측: 초당 5줄씩 수 분간 반복)
        if (preWarned != lastPreWarnedLogged) {
            if (preWarned > 0) Log.i(TAG, "지문 연계 사전 표시 — ${preWarned}건 (출처=$src)")
            lastPreWarnedLogged = preWarned
        }

        // 캐시만 보는 Layer 2. 판별기를 부르지 않으므로 화면이 바뀔 때마다 돌려도 되고,
        // 그래야 점선이 카드를 따라다닌다. 다만 이건 이번 프레임의 **두 번째** 트리
        // 순회라 상한을 걸어 확정 테두리의 추종을 방해하지 않게 한다. 새 판별은 유휴 때만.
        // 검색 결과 화면에서는 돌리지 않는다 — serp 배지가 결과 칸 단위로 이미
        // 표시 중인데 그 위에 "광고의심" 점선까지 겹치면 어느 것도 안 읽힌다.
        val guessed = if (isAiEnabled() && !serp.isSerpScreen) {
            runCatching {
                pipeline.run(
                    extractor.extract(root, stable, budgetMs = SCROLL_EXTRACT_BUDGET_MS),
                    allowClassify = false
                ).regions
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        withContext(Dispatchers.Main) {
            apply(stable, stableAnchors, fpKeys, styles, guessed, closable)
        }
    }

    private fun styleFor(level: RiskLevel?): TrackedBorderOverlay.BorderStyle = when (level) {
        RiskLevel.HIGH -> TrackedBorderOverlay.BorderStyle.DANGER
        RiskLevel.MEDIUM -> TrackedBorderOverlay.BorderStyle.CAUTION
        RiskLevel.LOW -> TrackedBorderOverlay.BorderStyle.SAFE
        null -> TrackedBorderOverlay.BorderStyle.AD
    }

    /**
     * X 탐색 범위 — 감지 영역보다 약간 넓게 잡는다. 광고의 X는 모서리에 딱 붙어
     * 있어 감지 영역이 몇 px만 좁게 잡혀도 경계 밖으로 밀려나 못 찾는다.
     */
    private fun closeSearchArea(region: Rect): Rect = Rect(region).apply {
        val slop = (CLOSE_REGION_SLOP_DP * resources.displayMetrics.density).toInt()
        inset(-slop, -slop)
    }

    /**
     * "광고 모두 닫기" — 닫을 수 있으면 X를 대신 누르고, 없으면 **가린다.**
     *
     * 사용자가 직접 누른 버튼에 대한 응답이다. 앱이 알아서 광고를 없애는 게 아니라,
     * 작은 X를 찾아 누르기 어려운 사람을 대신해 그 동작을 수행한다.
     *
     * **뒤로 가기는 쓰지 않는다.** 처음엔 X를 못 찾으면 뒤로 갔는데, 실기기에서
     * 접근성 트리를 떠 보니 웹 배너 광고에는 닫기 노드가 아예 없었다(네이트 뉴스에서
     * 닫기 후보 0개). 그래서 사실상 매번 폴백이 걸렸고 광고가 아니라 사용자가 보던
     * 페이지가 닫혔다 — "광고 모두 닫기를 했더니 웹 자체가 꺼졌다"는 실사용 리포트의
     * 정체다. 전면 광고 판정으로 범위를 좁혀 봐도 오판 한 번의 대가가 읽던 것을
     * 통째로 잃는 것이라 균형이 맞지 않는다.
     *
     * 대신 [AdCoverOverlay]로 그 자리를 덮는다. 광고는 눈앞에서 사라지고 페이지는
     * 그대로 남으며, 커버를 탭하면 되돌릴 수 있다.
     */
    private fun closeAllAds() {
        // 우리가 대신 누르는 X는 광고 영역 안에 있어 클릭 이벤트가 광고 클릭처럼
        // 보인다. 잠깐 판별을 멈춰 가림막이 헛뜨는 것을 막는다.
        suppressAdClickUntil = SystemClock.uptimeMillis() + CLOSE_SUPPRESS_MS
        val root = rootInActiveWindow ?: return
        val targets = confirmedRegions + aiRegions

        var closed = 0
        val uncovered = mutableListOf<Rect>()

        for (region in targets) {
            val x = findCloseButton(root, closeSearchArea(region), 0)
            if (x != null && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) closed++
            else uncovered.add(region)
        }

        Log.i(TAG, "광고 모두 닫기: 영역 ${targets.size}개 중 $closed 개 닫고 ${uncovered.size}개 가림")

        if (uncovered.isNotEmpty()) adCover.cover(uncovered)
        // 광고를 다 처리했으니 막대는 걷는다 — 남아 있으면 눌러도 할 일이 없다
        borderOverlay.hideCloseBar()
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
        // 이 가지가 광고 영역과 아예 겹치지 않으면 더 볼 필요가 없다.
        // 단 좌표가 **빈** 노드에서는 가지를 치지 않는다 — 크롬 웹 트리의 중간
        // 컨테이너는 좌표를 안 채우는 일이 흔한데, 여기서 잘라내면 그 안의 X를
        // 영영 못 찾는다 (광고 하나짜리 화면에서 닫기가 실패하던 원인).
        if (!bounds.isEmpty && !Rect.intersects(bounds, region)) return null

        if (node.isClickable && node.isVisibleToUser && isCloseSized(bounds) &&
            !isBrowserChrome(node) && looksLikeClose(node)
        ) {
            return node
        }
        for (i in 0 until node.childCount) {
            findCloseButton(node.getChild(i) ?: continue, region, depth + 1)?.let { return it }
        }
        return null
    }

    /**
     * 브라우저 자신의 UI인가. 탐색 범위를 광고 영역보다 넓게 잡기 때문에(모서리에
     * 붙은 X를 놓치지 않으려고) 화면 위쪽 광고에서는 크롬 툴바가 사정거리에 든다.
     * 거기엔 탭 닫기처럼 이름이 "close"인 버튼이 있어, 그걸 누르면 광고가 아니라
     * 탭이 닫힌다 — 절대 후보가 되면 안 되는 노드들이다.
     */
    private fun isBrowserChrome(node: AccessibilityNodeInfo): Boolean =
        node.viewIdResourceName?.startsWith("$CHROME:id/") == true

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
     * 광고 문구 자체는 올리지 않는다. 어르신이 무엇을 읽고 있었는지까지 보호자에게
     * 넘길 이유가 없고, 보호자가 알아야 할 것은 "어디서 광고가 몇 건 떴는가"다.
     * 출처는 도메인 또는 패키지명까지만 남는다.
     */
    private fun reportSighting(sourceKey: String?, layer: Int, count: Int) {
        if (sourceKey == null || count == 0) return
        if (!sightings.shouldReport(sourceKey, layer)) return
        AdEventLogger.logAdMarked(sourceKey, "광고 ${count}건 표시", layer)
    }

    private fun apply(
        confirmed: List<Rect>,
        anchors: List<Anchor?>,
        fpKeys: List<List<String>>,
        styles: List<TrackedBorderOverlay.BorderStyle>,
        guessed: List<Rect>,
        closable: Boolean
    ) {
        handler.removeCallbacks(recheck)
        if (confirmed.isNotEmpty() || guessed.isNotEmpty()) handler.postDelayed(recheck, RECHECK_MS)

        // 스캔이 도는 동안 굴러간 만큼 결과를 되민다 (안 그러면 테두리가 뒤로 튄다).
        // 확정 테두리는 다음 추적 표본(8ms 뒤)이 실측 좌표로 곧바로 보정한다.
        val dy = -scrollSinceScanStart
        val shifted = confirmed.shiftedBy(dy)
        trackedBorders.set(shifted, anchors, styles)
        borderOverlay.show(AdMarkStyle.AI_GUESS, guessed.shiftedBy(dy))
        // 닫기 막대는 닫을 수 있는 광고가 있을 때만. 복귀 바(그냥 두기/뒤로 가기)가
        // 떠 있는 동안은 띄우지 않는다 — 안내 두 개가 동시에 뜨면 어느 쪽도 안 읽힌다.
        // 이미 가린 광고 위에서도 띄우지 않는다 — 눌러도 할 일이 없다.
        val backPromptUp = backPromptSticky || (navShowBack && !shieldActive)
        if (closable && !backPromptUp && !adCover.isShowing) borderOverlay.showCloseBar()
        else borderOverlay.hideCloseBar()

        confirmedRegions = shifted
        confirmedAnchors = anchors
        confirmedFpKeys = fpKeys
        // 이동 후에도 "출발 페이지의 광고"를 기억해야 클릭 이벤트 없는 웹 광고를
        // 지문에 연결할 수 있다 (이동을 감지했을 땐 이미 화면이 랜딩으로 바뀌어 있다)
        if (fpKeys.any { it.isNotEmpty() }) {
            lastAdFpKeys = fpKeys
            lastAdFingerprintsAt = SystemClock.uptimeMillis()
        }
        aiRegions = guessed
        reportSighting(lastSourceKey, layer = 1, count = shifted.size)
        reportSighting(lastSourceKey, layer = 2, count = guessed.size)
        scheduleLayer2()

        // 스캔마다 이미 읽는 주소창 도메인으로 페이지 이동을 감지한다.
        // 실제 웹 광고는 클릭 이벤트를 만들지 않아(실기기 확인) 클릭 좌표
        // 판별이 안 통한다 — 이동 후 URL의 광고 지문(리다이렉터 도메인,
        // gclid류 클릭 추적 파라미터)이 웹 광고의 주 판별 경로다.
        val host = lastSourceKey
        if (host != null && host !in targetApps) {          // 크롬의 웹 호스트일 때만
            if (prevChromeHost != null && host != prevChromeHost) {
                // 선택 대기 중에 페이지가 바뀌었다 = 사용자가 뒤로가기 등으로
                // 스스로 떠났다. 가림막을 걷고 새 이동을 평소대로 다룬다.
                if (shieldAwaitingChoice) {
                    Log.i(TAG, "가림막 해제 — 선택 대기 중 페이지 이동")
                    dismissShieldNow()
                }
                if (!shieldActive) {
                    Log.i(TAG, "페이지 이동: $prevChromeHost → $host")
                    // 직전 호스트도 넘긴다 — 리다이렉터를 스쳐 지나간 것을 봤다면
                    // 최종 랜딩에서 확정적으로 잡는다
                    onNavigationDetected(readUrlBar(), cameFromHost = prevChromeHost)
                }
            }
            prevChromeHost = host
        } else if (host != null && host != CHROME) {
            // 유튜브 등 다른 대상 앱 화면 — 웹 출발 호스트 추적을 끝낸다
            prevChromeHost = null
        }
        // host == CHROME(주소창이 접혀 출처가 패키지명으로 떨어진 스캔)이나 null이면
        // prevChromeHost를 유지한다. 스크롤하면 크롬이 주소창을 접는데, 그때 끊어
        // 버리면 기사 중간·하단 광고(대부분 스크롤 후 클릭)의 랜딩 이동을 통째로
        // 놓친다 — "특정 사이트에서만 된다"는 리포트의 실제 원인.

        // ── 강제 이동 복귀 안내 (ad_claude_fable 이식) ──
        // 가림막이 떠 있으면 그쪽이 우선이다 (판정 + 자체 복귀 버튼 보유).
        if (navShowBack && !shieldActive) {
            backPrompt.show(
                onStay = {
                    navGuard.dismiss()
                    backPrompt.hide()
                },
                onBack = {
                    val leaves = navGuard.leavesApp
                    navGuard.dismiss()
                    backPrompt.hide()
                    if (leaves) leaveApp(3) else performGlobalAction(GLOBAL_ACTION_BACK)
                }
            )
            // 화면이 멈춰 있으면 이벤트가 없어 다음 스캔이 안 돈다 — 안내가 떠 있는
            // 동안만 스스로 깨워 12초 만료를 확인한다 (원본과 같은 장치).
            handler.removeCallbacks(navRecheck)
            handler.postDelayed(navRecheck, 1_000)
        } else if (!backPromptSticky) {
            backPrompt.hide()
        }
    }

    /**
     * 앱에서 벗어날 때까지 뒤로 가기를 누른다 — "한 번 더 누르면 종료"인 앱은
     * 한 번으로 안 나가진다. 화면의 앱이 그대로일 때만 다시 누른다 (원본 이식).
     *
     * **브라우저 안에서는 패키지가 안 바뀐다.** 랜딩 → 기사 복귀도 패키지는 그대로
     * 크롬이라, 패키지만 보고 다시 누르면 기사를 지나쳐 크롬 자체가 꺼진다 —
     * "뒤로 가기를 눌렀더니 완전히 꺼졌다"는 실사용 리포트의 원인. 크롬에서는
     * 호스트가 바뀌었으면(이미 빠져나왔으면) 멈추고, 호스트를 못 읽으면(애매하면)
     * 더 누르지 않는다 — 광고 페이지에 남는 것보다 보던 화면을 잃는 쪽이 나쁘다.
     */
    private fun leaveApp(tries: Int) {
        val beforePkg = rootInActiveWindow?.packageName?.toString()
        val beforeHost =
            if (beforePkg == CHROME) readUrlBar()?.let { UrlNormalizer.hostOf(it) } else null
        performGlobalAction(GLOBAL_ACTION_BACK)
        if (tries <= 1) return
        handler.postDelayed({
            if (beforePkg == null ||
                rootInActiveWindow?.packageName?.toString() != beforePkg
            ) return@postDelayed
            if (beforePkg == CHROME) {
                val nowHost = readUrlBar()?.let { UrlNormalizer.hostOf(it) }
                if (nowHost == null || nowHost != beforeHost) return@postDelayed
            }
            leaveApp(tries - 1)
        }, 700)
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

    /** 지금 점선으로 표시 중인 영역. "광고 모두 닫기"가 대상으로 쓴다. */
    private var aiRegions: List<Rect> = emptyList()

    /** 마지막 스캔에서 읽은 출처(도메인/패키지). 보호자 기록의 중복 제거 키다. */
    @Volatile
    private var lastSourceKey: String? = null

    private val runLayer2 = Runnable {
        if (!isAiEnabled()) return@Runnable
        // 검색 결과 화면은 serp 배지 담당이다 — 점선까지 겹치면 안 읽힌다 (스캔 경로와 동일)
        if (serp.isSerpScreen) return@Runnable
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

    // ──────────────────────────────────────────────────────────
    // 가림막 흐름 — 광고 클릭 → 이동 감시 → URL 확보 → 판정 → 해제/복귀
    //
    // 브라우저 전환만으로는 광고 클릭인지 기사 클릭인지 알 수 없다. 판별은
    // AdEntryDetector가 하고(클릭 좌표 + 시간 창 + 리다이렉터), 확실할 때만
    // 가림막을 띄운다. 판별 불가면 아무것도 하지 않는다 — 기사 클릭마다
    // 가림막이 뜨는 순간 서비스는 못 쓰는 물건이 된다.
    // ──────────────────────────────────────────────────────────

    /** 클릭이 표시 중인 광고 영역 안이었는지 대조한다. */
    private fun trackClickForAdEntry(event: AccessibilityEvent) {
        if (SystemClock.uptimeMillis() < suppressAdClickUntil) return
        val ads = confirmedRegions + aiRegions
        // 실전 진단용 — 실제 광고 위젯에서 클릭 이벤트가 어떤 모양으로 오는지 확인 (4b 개발 중 유지)
        Log.i(
            TAG,
            "클릭 이벤트: pkg=${event.packageName} source=${event.source != null} " +
                "광고영역=${ads.size} text=${(event.contentDescription ?: event.text.joinToString(" ")).take(40)}"
        )
        if (ads.isEmpty()) return
        // 클릭 노드의 좌표를 모르면 판별하지 않는다 — 모르면 개입하지 않는 쪽이 안전하다
        val src = event.source
        if (src == null) {
            Log.i(TAG, "클릭 source=null — 좌표 판별 불가")
            return
        }
        val bounds = Rect().also { src.getBoundsInScreen(it) }
        if (bounds.width() <= 0 || bounds.height() <= 0) return

        if (ads.any { Rect.intersects(it, bounds) }) {
            entryDetector.recordAdClick()
            // 클릭된 광고가 확정 영역 중 어느 것인지 알면 지문을 정확히 특정할 수 있다.
            // 스캔이 아직 지문을 못 모은 광고라면(페이지가 뜨자마자 누른 경우) 지금
            // 이 자리에서라도 모은다 — 지문이 없으면 판정이 광고에 연계되지 않아
            // 다음에 초록 체크(광고 ✓)가 영영 안 뜬다 (실사용 리포트).
            val idx = confirmedRegions.indexOfFirst { Rect.intersects(it, bounds) }
            pendingFpKeys =
                if (idx >= 0) confirmedFpKeys.getOrNull(idx).orEmpty().ifEmpty { gatherFpNow(idx) }
                else emptyList()
            preClickUrl = readUrlBar()
            navPollsLeft = NAV_POLL_COUNT
            handler.removeCallbacks(navPoll)
            handler.postDelayed(navPoll, NAV_POLL_MS)
            Log.i(TAG, "광고 영역 클릭 — 진입 감시 시작 (사전 URL=${preClickUrl ?: "-"})")
        } else {
            entryDetector.recordNonAdClick()
        }
    }

    /**
     * 클릭 순간의 지문 보강 수집 — 스캔의 지문이 아직 비어 있는 광고를 눌렀을 때.
     * 노드 몇 개 읽기(IPC)라 메인 스레드여도 부담이 없다 (readUrlBar와 같은 급).
     * 출처가 패키지명으로 떨어진 상태(주소창 접힘)면 스캔 경로와 같은 이유로
     * 만들지 않는다 — 출처가 다른 지문은 연계가 간헐적으로 깨진다.
     */
    private fun gatherFpNow(idx: Int): List<String> {
        val src = lastSourceKey ?: return emptyList()
        if ('.' !in src || src in targetApps) return emptyList()
        val texts = runCatching { confirmedAnchors.getOrNull(idx)?.gatherText() }.getOrNull()
            ?: return emptyList()
        val keys = mutableListOf(CardText.cacheKey(src, texts))
        val adv = AdvertiserMark.advertiserLines(texts)
        if (adv.isNotEmpty()) keys += CardText.cacheKey("adv|$src", adv)
        Log.i(TAG, "클릭 시점 지문 보강 수집 — ${keys.size}건 (출처=$src)")
        return keys
    }

    /**
     * 크롬 주소창이 지금 보여주는 URL. 크롬이 아니거나 주소창이 접혀 있으면 null.
     * 노드 한 개 조회라 메인 스레드에서 불러도 부담이 없다 (sourceKeyOf와 같은 방식).
     */
    private fun readUrlBar(): String? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() != CHROME) return null
        return root.findAccessibilityNodeInfosByViewId("$CHROME:id/url_bar")
            ?.firstOrNull()?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * 크롬 안의 같은 탭 이동은 창 전환 이벤트가 안 올 수 있어, 광고 클릭 후
     * 잠깐 동안 주소창이 바뀌는지 직접 살핀다.
     */
    private val navPoll = object : Runnable {
        override fun run() {
            if (shieldActive) return
            val url = readUrlBar()
            if (url != null && url != preClickUrl) {
                onNavigationDetected(url)
                return
            }
            if (--navPollsLeft > 0) handler.postDelayed(this, NAV_POLL_MS)
        }
    }

    /**
     * 페이지 이동이 감지됐다. 광고발이면 가림막을 띄우고 판정을 시작하고,
     * 아니면 아무것도 하지 않는다.
     */
    private fun onNavigationDetected(
        urlShown: String?,
        cameFromHost: String? = null,
        /**
         * 이미 다른 경로가 광고발이라고 확정한 사유. 중계 경유 추적처럼
         * [entryDetector]가 보지 못하는 증거를 가진 호출자가 넘긴다 —
         * 그 경우 재판정하지 않고 이 사유를 그대로 쓴다.
         */
        forcedReason: ShieldReason? = null
    ) {
        if (shieldActive) return
        val host = urlShown?.let { UrlNormalizer.hostOf(it) }
        // 경유지에서 광고 출발 페이지로 "돌아온" 이동은 광고 진입이 아니다 —
        // 직전-리다이렉터 규칙이 복귀까지 잡아 기사를 분석해버린다 (실측:
        // 쿠팡 앱 딥링크 후 크롬 복귀). 광고 파라미터가 붙어 있으면 예외.
        if (host != null && host == shieldCameFrom &&
            urlShown != null && !AdEntryDetector.isAdLanding(urlShown)
        ) {
            return
        }
        // rawUrl은 정규화 전 원문이어야 한다 — 광고 클릭 파라미터가 판별 근거다
        val reason = forcedReason
            ?: entryDetector.reasonForNavigation(host, urlShown, cameFromHost)
            ?: return

        handler.removeCallbacks(navPoll)
        shieldActive = true
        shieldResolved = false
        // 진입 URL과 출발 호스트를 기억한다 — 광고가 앱 딥링크로 빠져나가면
        // 주소창이 출발지로 돌아가 버려, 이 진입 URL의 리다이렉트 체인을
        // 격리 분석기로 직접 따라가는 것이 최종 목적지를 보는 유일한 길이다.
        shieldEntryUrl = urlShown
        shieldCameFrom = cameFromHost
        shieldDeepLinked = false
        // 복귀 목표 — 광고를 누르기 전에 읽던 곳. 중계 경유로 잡은 이동은 출발
        // 호스트를 넘겨받지 못하므로(중계 추적이 eTLD+1로 기억한다) 그 값을 쓴다.
        returnTargetSite = cameFromHost ?: relayTracker.originBeforeRelay

        // 이번 진입의 광고 지문 — 판정이 나오면 연계 저장한다.
        // 클릭 좌표로 특정된 것이 최우선이고, 없으면(웹 광고는 클릭 이벤트가 없다)
        // 출발 페이지에 광고가 **하나뿐이었을 때만** 그것으로 본다. 여러 개면
        // 어느 것을 눌렀는지 알 수 없으므로 연계하지 않는다 — 틀린 연계는
        // 엉뚱한 광고에 위험 표시를 붙인다.
        val fresh = SystemClock.uptimeMillis() - lastAdFingerprintsAt < FP_FRESH_MS
        shieldFpKeys = when {
            pendingFpKeys.isNotEmpty() -> pendingFpKeys
            fresh -> lastAdFpKeys.filter { it.isNotEmpty() }.singleOrNull().orEmpty()
            else -> emptyList()
        }
        pendingFpKeys = emptyList()

        shieldOverlay.show("🔍", "잠깐만요", "안전한 곳인지 확인하고 있어요")
        handler.postDelayed(shieldTimeout, SHIELD_MAX_MS)
        Log.i(TAG, "가림막 표시 — 사유=$reason 지문=${shieldFpKeys.firstOrNull()?.take(24) ?: "-"} url=${urlShown ?: "-"}")

        // 추적 리다이렉트가 끝나 주소창이 안정된 뒤에 판정한다.
        // 리다이렉터 초기 URL은 기준값으로 넣지 않는다 — 느린 리다이렉터에서 첫
        // 재확인이 초기값과 같으면 정거장을 "안정"으로 착각해 분석해 버린다 (실측).
        // 단 광고 파라미터를 단 직행 랜딩은 그 자체가 목적지라 기준값으로 삼아
        // 첫 재확인(250ms) 한 번으로 안정을 확정한다 — 속도 개선.
        urlSettleReadsLeft = URL_SETTLE_MAX_READS
        lastSettleUrl = urlShown?.takeIf {
            val h = UrlNormalizer.hostOf(it)
            h != null && !AdEntryDetector.isAdRedirector(h) && AdEntryDetector.isAdLanding(it)
        }
        handler.postDelayed(urlSettle, URL_SETTLE_MS)

        // 즉시 판정 패스트패스 — 진입 시점 URL이 이미 캐시·지문에 있으면
        // 안정화를 기다리지 않고 바로 결말을 낸다 (재등장 광고 ~0.2초 목표).
        // 리다이렉터 경유 URL은 저장된 적이 없어 자연히 미스 → 안정화 경로로.
        urlShown?.let { initial ->
            val fpForFast = shieldFpKeys
            // 리다이렉터 주소로 히트하면 보여주되 저장은 안 한다 — 정거장은 일회용
            val transit = UrlNormalizer.hostOf(initial)
                ?.let { AdEntryDetector.isAdRedirector(it) } == true
            scope.launch {
                val normalized = UrlNormalizer.normalize(initial) ?: return@launch
                val stored = resolveFromStore(normalized, fpForFast, persist = !transit)
                    ?: return@launch
                withContext(Dispatchers.Main) { finishShield(normalized, stored) }
            }
        }
    }

    private val urlSettle = object : Runnable {
        override fun run() {
            if (!shieldActive) return
            val url = readUrlBar()
            val host = url?.let { UrlNormalizer.hostOf(it) }
            // 과금 리다이렉터는 목적지가 아니라 지나가는 정거장이다. 그걸 분석하면
            // 빈 경유 페이지에 저위험이 나온다 — 진짜 도착지가 뜰 때까지 기다린다.
            val transit = host != null && AdEntryDetector.isAdRedirector(host)
            val settled = !transit && url != null && url == lastSettleUrl
            if (url != null && !transit) lastSettleUrl = url
            if (settled || --urlSettleReadsLeft <= 0) {
                resolveShield(lastSettleUrl)
            } else {
                handler.postDelayed(this, URL_SETTLE_MS)
            }
        }
    }

    /** 확보한 URL로 캐시를 조회하고, 미스면 격리 분석을 돌려 가림막의 결말을 정한다. */
    private fun resolveShield(urlShown: String?) {
        // 광고가 앱 딥링크로 빠져나가면 주소창은 못 읽히거나(앱 화면) 출발지로
        // 되돌아간다. 그때는 진입 URL(경유지)의 리다이렉트 체인을 격리 분석기로
        // 직접 따라가 최종 목적지를 판정한다 — 실측: 연합뉴스TV의 쿠팡 배너
        // (기사 → clickads.co.kr → 쿠팡 앱 딥링크, 크롬은 기사로 복귀).
        val backAtOrigin = urlShown != null && shieldCameFrom != null &&
            UrlNormalizer.hostOf(urlShown) == shieldCameFrom
        var target = if (urlShown == null || backAtOrigin) shieldEntryUrl ?: urlShown else urlShown
        if (target !== urlShown) {
            Log.i(TAG, "딥링크 이탈 감지 — 진입 URL 체인을 직접 분석: ${target?.take(80)}")
            shieldDeepLinked = true
        }
        // JS로만 넘어가는 경유지는 서버 체인으로 안 풀린다 — 쿼리에 내장된
        // 목적지(origUrl= 등)를 꺼내 이어간다 (실측: mjbiz → link.coupang.com).
        var unwraps = 0
        while (unwraps < 3) {
            val h = target?.let { UrlNormalizer.hostOf(it) } ?: break
            if (!AdEntryDetector.isAdRedirector(h)) break
            val inner = AdEntryDetector.embeddedDestination(target!!) ?: break
            Log.i(TAG, "경유지 내장 목적지 추출 — ${UrlNormalizer.hostOf(inner) ?: "?"}")
            target = inner
            unwraps++
        }
        val normalized = target?.let { UrlNormalizer.normalize(it) }
        if (normalized == null) {
            // 주소창 읽기가 끝까지 실패한 드문 케이스 (실측: 프루지오 진입 1회).
            // 로그 없이 죽으면 디버깅이 불가능해 흔적을 남긴다.
            Log.i(TAG, "주소 확보 실패 — 가림막 미확인 종료")
            finishShieldUnverified("주소를 확인하지 못했어요")
            return
        }
        val fpKeys = shieldFpKeys
        scope.launch {
            val stored = resolveFromStore(normalized, fpKeys)
            if (stored != null) {
                withContext(Dispatchers.Main) { finishShield(normalized, stored) }
                return@launch
            }

            // 격리 분석 — 사용자 브라우저와 분리된 수집(JS·쿠키·다운로드 없음) + 규칙 판정
            val page = runCatching {
                withTimeoutOrNull(ANALYZE_TIMEOUT_MS) { analyzer.analyze(target) }
            }.getOrNull()
            if (page == null) {
                Log.i(TAG, "분석 실패 — $normalized (추가 확인 필요)")
                withContext(Dispatchers.Main) { finishShieldUnverified("이 곳을 살펴보지 못했어요") }
                return@launch
            }

            // 최종 도착지가 여전히 광고망 리다이렉터라면(JS로만 넘어가는 체인)
            // 진짜 목적지를 본 것이 아니다 — 저위험으로 캐시하면 안 된다.
            val finalHost = UrlNormalizer.hostOf(page.finalUrl)
            if (finalHost != null && AdEntryDetector.isAdRedirector(finalHost)) {
                // 신뢰 플랫폼의 공식 관문에서 끝났다면 목적지는 그 플랫폼 안이다 —
                // 앱 딥링크 광고(실측: 쿠팡 파트너스)의 정직한 결말.
                val trusted = AdEntryDetector.trustedTerminalName(finalHost)
                if (trusted != null) {
                    val a = RiskAssessment.Assessed(
                        RiskLevel.LOW, "$trusted 공식 페이지로 연결되는 광고예요"
                    )
                    runCatching {
                        urlVerdictDao.upsert(
                            UrlVerdict(
                                normalizedUrl = normalized,
                                riskLevel = a.level.name,
                                reason = a.reason,
                                finalUrl = page.finalUrl,
                                analyzedAt = System.currentTimeMillis(),
                                validUntil = System.currentTimeMillis() +
                                    UrlRiskRules.validityMs(a.level)
                            )
                        )
                    }
                    linkFingerprints(fpKeys, normalized)
                    Log.i(TAG, "신뢰 종착지 — $trusted (${page.finalUrl.take(60)})")
                    withContext(Dispatchers.Main) { finishShield(normalized, a) }
                    return@launch
                }
                Log.i(TAG, "분석 중단 — 최종지가 리다이렉터 (${page.finalUrl})")
                withContext(Dispatchers.Main) {
                    finishShieldUnverified("연결되는 곳을 끝까지 확인하지 못했어요")
                }
                return@launch
            }

            val a = page.assessment
            // Unverified는 저장하지 않는다는 원칙 그대로 — 여기 오는 것은 항상 Assessed다
            runCatching {
                urlVerdictDao.upsert(
                    UrlVerdict(
                        normalizedUrl = normalized,
                        riskLevel = a.level.name,
                        reason = a.reason,
                        finalUrl = page.finalUrl,
                        analyzedAt = System.currentTimeMillis(),
                        validUntil = System.currentTimeMillis() + UrlRiskRules.validityMs(a.level)
                    )
                )
            }
            linkFingerprints(fpKeys, normalized)
            Log.i(TAG, "분석 완료 — $normalized ${a.level} : ${a.reason} (최종=${page.finalUrl})")
            withContext(Dispatchers.Main) { finishShield(normalized, a) }

            // 규칙이 저위험이라 한 페이지만 온디바이스 LLM이 2차로 본다 (상향 전용).
            // 가림막은 이미 규칙 결과로 끝났고, 이건 백그라운드에서 돌아
            // 다음 만남(캐시·지문)과 지각 개입에 반영된다.
            if (a.level == RiskLevel.LOW && page.html != null && onDeviceLlm.isAvailable()) {
                scope.launch { escalateWithLlm(normalized, page, fpKeys) }
            }
        }
    }

    /**
     * 온디바이스 LLM 2차 판단 — 규칙이 못 잡는 내용 기반 사기(가짜 후기·과장
     * 치료 효과)를 잡는다. 중위험·고위험으로 판단될 때만 판정을 덮어쓰고,
     * 고위험인데 사용자가 아직 그 페이지에 있으면 늦게라도 복귀시킨다.
     */
    private suspend fun escalateWithLlm(
        normalized: String,
        page: AnalyzedPage,
        fpKeys: List<String>
    ) {
        val text = LlmRiskJudge.sanitize(page.html ?: return)
        if (text.length < 40) return   // 판단할 내용 자체가 없다

        // 엔진 선택 — 키가 있으면 Claude(Haiku, 실측 2.1초)가 주 엔진이고,
        // 없으면 온디바이스(실측 15~19초)로 폴백한다. 어느 쪽이든 역할은 같다:
        // 규칙-저위험 페이지의 2차 검토, 상향 전용.
        val raised: RiskAssessment.Assessed?
        val agreedLow: Boolean
        if (ClaudeApiJudge.isAvailable()) {
            val claude = withTimeoutOrNull(LLM_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { ClaudeApiJudge.judge(page.finalUrl, text) }
            } ?: return
            raised = claude.assessment
            agreedLow = "저위험" in claude.raw
        } else {
            val response = withTimeoutOrNull(LLM_TIMEOUT_MS) {
                onDeviceLlm.generate(LlmRiskJudge.buildPrompt(page.finalUrl, text))
            } ?: return
            raised = LlmRiskJudge.parse(response)
            agreedLow = "저위험" in response
        }

        if (raised == null) {
            // 상향 없음. 명시적 저위험 동의는 흔적을 남긴다 — AI가 재검토했다는
            // 사실이 사용자(와 DB 뷰어)에게 보여야 신뢰가 생긴다.
            if (agreedLow) {
                Log.i(TAG, "LLM 동의 — $normalized 저위험 유지")
                runCatching {
                    urlVerdictDao.upsert(
                        UrlVerdict(
                            normalizedUrl = normalized,
                            riskLevel = RiskLevel.LOW.name,
                            reason = "${page.assessment.reason} · AI도 확인했어요",
                            finalUrl = page.finalUrl,
                            analyzedAt = System.currentTimeMillis(),
                            validUntil = System.currentTimeMillis() +
                                UrlRiskRules.validityMs(RiskLevel.LOW)
                        )
                    )
                }
            }
            return
        }

        Log.i(TAG, "LLM 상향 — $normalized ${raised.level} : ${raised.reason}")
        runCatching {
            urlVerdictDao.upsert(
                UrlVerdict(
                    normalizedUrl = normalized,
                    riskLevel = raised.level.name,
                    reason = "AI 판단: ${raised.reason}",
                    finalUrl = page.finalUrl,
                    analyzedAt = System.currentTimeMillis(),
                    validUntil = System.currentTimeMillis() + UrlRiskRules.validityMs(raised.level)
                )
            )
        }
        linkFingerprints(fpKeys, normalized)

        // 지각 개입 — 판단이 끝났을 때 사용자가 아직 그 페이지를 보고 있으면
        // 고위험은 늦게라도 빼낸다. 피해는 입력·승인 순간에 나므로 아직 유효하다.
        if (raised.level == RiskLevel.HIGH) {
            withContext(Dispatchers.Main) {
                val hereHost = readUrlBar()?.let { UrlNormalizer.hostOf(it) }
                if (hereHost != null && hereHost == UrlNormalizer.hostOf(page.finalUrl) &&
                    !shieldActive
                ) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    shieldActive = true
                    shieldOverlay.show(
                        "⚠️", "위험한 곳이었어요",
                        "${raised.reason}\n안전한 곳으로 돌려보냈어요"
                    )
                    handler.postDelayed(dismissShieldRunnable, RESULT_SHOW_MS)
                }
            }
        }
    }

    /**
     * 저장된 판정 조회: URL 캐시 → 지문 연계 순. 히트하면 지문 연계를 갱신하고
     * 판정을 돌려준다 — 즉시 판정 패스트패스와 안정화 후 경로가 함께 쓴다.
     *
     * @param persist false면 조회만 하고 저장(판정 복사·지문 연계)은 하지 않는다.
     *   패스트패스가 리다이렉터 주소로 히트했을 때다 — 정거장 주소는 일회용이라
     *   거기에 판정을 옮겨 적으면 쓰레기 행만 쌓인다 (실측: googleadservices).
     */
    private suspend fun resolveFromStore(
        normalized: String,
        fpKeys: List<String>,
        persist: Boolean = true
    ): RiskAssessment.Assessed? {
        val now = System.currentTimeMillis()
        val cached = runCatching {
            urlVerdictDao.findValid(normalized, now)
        }.getOrNull()?.toAssessment()
        if (cached != null) {
            Log.i(TAG, "URL 판정 캐시 히트 — $normalized ${cached.level}")
            if (persist) linkFingerprints(fpKeys, normalized)
            return cached
        }

        // URL은 처음이지만 같은 광고(지문)를 전에 판정한 적이 있다면 그걸 쓴다 —
        // 소재 로테이션으로 랜딩 주소만 바뀐 경우다 (실측: page_cd 57→67).
        val linked = fpKeys.firstNotNullOfOrNull { k ->
            runCatching { fingerprintDao.findLinkedVerdict(k, now) }.getOrNull()
        }
        val linkedAssessment = linked?.toAssessment()
        if (linked != null && linkedAssessment != null) {
            Log.i(TAG, "지문 판정 히트 — ${linkedAssessment.level} (새 URL: $normalized)")
            if (persist) {
                // 새 변형 URL에도 판정을 옮겨 적어 다음엔 URL 캐시로도 잡히게 한다
                runCatching {
                    urlVerdictDao.upsert(linked.copy(normalizedUrl = normalized, analyzedAt = now))
                }
                linkFingerprints(fpKeys, normalized)
            }
            return linkedAssessment
        }
        return null
    }

    /**
     * 광고 지문들 → URL 판정 연계 저장. 카드 전체 지문과 광고주 표기줄 지문을
     * 모두 저장한다 — 후자가 문구 변형을 하나의 캠페인으로 이어준다.
     * 지문을 못 특정했으면 아무것도 하지 않는다.
     */
    private suspend fun linkFingerprints(keys: List<String>, normalizedUrl: String) {
        if (keys.isEmpty()) return
        val now = System.currentTimeMillis()
        runCatching {
            keys.forEach { fingerprintDao.upsert(AdFingerprintLink(it, normalizedUrl, now)) }
        }.onSuccess {
            Log.i(TAG, "지문 연계 저장 ${keys.size}건 — ${keys.first().take(24)}… → $normalizedUrl")
        }
    }

    /** 등급이 나왔다. 기획 3.1절의 대응대로 가림막을 끝맺는다. */
    private fun finishShield(url: String, assessment: RiskAssessment.Assessed) {
        if (!shieldActive || shieldResolved) return
        shieldResolved = true
        Log.i(TAG, "가림막 결말 — ${assessment.level} 딥링크=$shieldDeepLinked url=$url")
        handler.removeCallbacks(shieldTimeout)
        handler.removeCallbacks(urlSettle)
        when (assessment.level) {
            RiskLevel.HIGH -> {
                // 계속 진행 없음 — 즉시 복귀
                performGlobalAction(GLOBAL_ACTION_BACK)
                shieldOverlay.update(
                    "⚠️", "위험한 곳이에요",
                    "${assessment.reason}\n이전 화면으로 안전하게 돌려보냈어요"
                )
                handler.postDelayed(dismissShieldRunnable, RESULT_SHOW_MS)
                Log.i(TAG, "고위험 차단 — $url")
            }
            RiskLevel.MEDIUM -> {
                // 이유를 보여주고 사용자가 선택한다 (안내형 동작 — 균형형 투터치 정책은 4e).
                // 사용자가 결정할 때까지 걷지 않는다.
                shieldOverlay.showChoice(
                    "⚠️", "조심하세요", assessment.reason,
                    "안전하게 돌아가기", {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        dismissShieldNow()
                    },
                    "그냥 볼게요", { dismissShieldNow() }
                )
                startAwaitingChoice()
            }
            RiskLevel.LOW -> {
                if (shieldDeepLinked) {
                    // 광고가 다른 앱·화면을 열었지만 목적지는 안전하다 — 화면 전체를
                    // 덮는 대신 팀원(ad_claude_fable) 스타일 하단 바로 돌아갈 길만
                    // 12초간 준다. 갑자기 옮겨진 어르신에게 필요한 건 판정 화면이
                    // 아니라 복귀 버튼이다.
                    dismissShieldNow()
                    showEscapePrompt { returnToOrigin() }
                } else {
                    shieldOverlay.update("✅", "확인했어요", assessment.reason)
                    handler.postDelayed(dismissShieldRunnable, OK_SHOW_MS)
                    // 안전해도 광고가 데려온 화면이다 — 쿠팡 같은 쇼핑몰에 뚝 떨어진
                    // 어르신에게 돌아갈 길이 없다는 리포트가 있었다. 확인 표시가
                    // 걷힌 직후 12초간 복귀 바를 남긴다. 같은 탭 이동이므로 뒤로
                    // 가기 한 번이면 출발 페이지다.
                    handler.postDelayed({ showEscapePrompt { returnToOrigin() } }, OK_SHOW_MS)
                }
            }
        }
    }

    /**
     * 광고발 이동이 끝난 뒤 12초간 "그냥 두기 / 뒤로 가기" 복귀 바를 남긴다.
     * 닫기 막대는 함께 걷는다 — 두 안내가 동시에 뜨면 어느 쪽도 안 읽힌다
     * (실사용 리포트: 복귀 바와 "광고 모두 닫기"가 같이 떠 있었다).
     */
    /**
     * 광고를 누르기 전에 읽던 곳으로 되돌린다 — **횟수가 아니라 시간으로.**
     *
     * 예전에는 뒤로 가기를 한 번(또는 앱을 벗어날 때까지 세 번) 눌렀다. 그런데
     * 광고 이동은 경로 길이가 제각각이다. 실측(쿠팡 배너)은
     * `m.news.nate.com → api.ootoo.co.kr → login.coupang.com`으로 중계가 하나
     * 끼어, 한 번 누르면 사용자가 본 적도 없는 중계 페이지에 떨어진다. 경로가
     * 몇 단계인지는 미리 알 수 없고 광고마다 다르므로 **횟수로는 맞출 수 없다.**
     *
     * 그래서 목적지로 판정한다: 출발 사이트에 닿을 때까지 [RETURN_STEP_MS]마다
     * 한 번씩 누르고, [RETURN_MAX_MS] 안에 못 닿으면 포기한다. 두 가지 안전장치를
     * 함께 둔다 — 주소가 더 이상 바뀌지 않으면(히스토리 끝) 즉시 멈추고, 시간
     * 예산이 있어 어떤 경우에도 무한히 누르지 않는다. 뒤로 가기를 과하게 누르면
     * 브라우저 자체가 꺼지므로 이 두 장치가 없으면 안 된다.
     *
     * 출발지를 모르면(target null) 예전처럼 한 번만 누른다 — 모르면서 반복하는
     * 것이 가장 위험하다.
     */
    private fun returnToOrigin() {
        val target = returnTargetSite
        performGlobalAction(GLOBAL_ACTION_BACK)
        if (target == null) {
            Log.i(TAG, "복귀 — 출발지 미상, 뒤로 가기 1회")
            return
        }
        returnDeadline = SystemClock.uptimeMillis() + RETURN_MAX_MS
        Log.i(TAG, "복귀 시작 — 목표=$target (최대 ${RETURN_MAX_MS}ms)")
        handler.postDelayed({ returnStep(target, hostNow()) }, RETURN_STEP_MS)
    }

    private fun returnStep(target: String, lastHost: String?) {
        if (atReturnTarget(target)) {
            Log.i(TAG, "복귀 완료 — $target")
            return
        }
        if (SystemClock.uptimeMillis() > returnDeadline) {
            Log.i(TAG, "복귀 중단 — 시간 초과 (현재=${hostNow() ?: "-"})")
            return
        }
        val here = hostNow()
        // 눌렀는데 주소가 그대로다 = 뒤로 갈 곳이 없다. 더 누르면 브라우저가 꺼진다.
        if (here != null && here == lastHost) {
            Log.i(TAG, "복귀 중단 — 더 돌아갈 곳 없음 ($here)")
            return
        }
        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({ returnStep(target, here) }, RETURN_STEP_MS)
    }

    private fun hostNow(): String? = readUrlBar()?.let { UrlNormalizer.hostOf(it) }

    /**
     * 지금 출발 사이트에 있는가. 목표가 전체 호스트일 수도(m.news.nate.com)
     * eTLD+1일 수도(nate.com) 있어 양방향 접미사로 본다.
     */
    private fun atReturnTarget(target: String): Boolean {
        val host = hostNow() ?: return false
        return host == target || host.endsWith(".$target") || target.endsWith(".$host")
    }

    private fun showEscapePrompt(onBackAction: () -> Unit) {
        Log.i(TAG, "복귀 바 표시 — 12초")
        borderOverlay.hideCloseBar()
        backPromptSticky = true
        backPrompt.show(
            onStay = {
                handler.removeCallbacks(backPromptTimeout)
                backPromptSticky = false
                backPrompt.hide()
            },
            onBack = {
                handler.removeCallbacks(backPromptTimeout)
                backPromptSticky = false
                backPrompt.hide()
                onBackAction()
            }
        )
        handler.removeCallbacks(backPromptTimeout)
        handler.postDelayed(backPromptTimeout, BACK_PROMPT_MS)
    }

    /**
     * 분석하지 못했다 — **말없이 걷지 않는다.** 분석 못 한 화면을 저위험처럼
     * 취급하지 않는 원칙(추가 확인 필요)대로, 확인하지 못했음을 알리고
     * 복귀를 권한다. 사용자가 고르지 않으면 잠시 후 스스로 걷힌다.
     */
    private fun finishShieldUnverified(limitation: String) {
        if (!shieldActive || shieldResolved) return
        shieldResolved = true
        handler.removeCallbacks(shieldTimeout)
        handler.removeCallbacks(urlSettle)
        shieldOverlay.showChoice(
            "❓", "확인하지 못했어요",
            "$limitation\n개인정보나 돈을 요구하면 나가세요",
            "안전하게 돌아가기", {
                performGlobalAction(GLOBAL_ACTION_BACK)
                dismissShieldNow()
            },
            "그냥 볼게요", { dismissShieldNow() }
        )
        startAwaitingChoice()
    }

    /** 선택 대기 시작. 사용자가 화면을 떠났는지만 주기적으로 살핀다. */
    private fun startAwaitingChoice() {
        shieldAwaitingChoice = true
        // 기준 화면을 지금 떠 있는 앱으로 기록한다 — 딥링크로 끌려간 앱 위에서도
        // 안내가 유지되고, 그 앱을 떠나는 순간(스스로 복귀) 걷힌다.
        choiceScreenPkg = rootInActiveWindow?.packageName?.toString() ?: CHROME
        handler.postDelayed(choiceWatch, CHOICE_WATCH_MS)
    }

    /**
     * 선택 대기 중 사용자가 뒤로가기·홈 등으로 크롬을 떠났는지 확인한다.
     * 떠났으면 가림막도 걷는다 — 위험한 페이지를 이미 벗어났고, 런처 위에
     * 가림막이 남으면 폰을 잠근 꼴이 된다.
     */
    private val choiceWatch = object : Runnable {
        override fun run() {
            if (!shieldActive || !shieldAwaitingChoice) return
            val pkg = rootInActiveWindow?.packageName?.toString()
            if (pkg != null && pkg != choiceScreenPkg) {
                Log.i(TAG, "가림막 해제 — 사용자가 화면을 떠남 (현재=$pkg)")
                dismissShieldNow()
            } else {
                handler.postDelayed(this, CHOICE_WATCH_MS)
            }
        }
    }

    /** 어떤 경로로든 가림막이 이 시간 이상 "확인 중"이면 미확인으로 끝맺는다. */
    private val shieldTimeout = Runnable {
        Log.w(TAG, "가림막 시간 초과")
        finishShieldUnverified("확인이 너무 오래 걸려요")
    }

    private val dismissShieldRunnable = Runnable { dismissShieldNow() }

    private fun dismissShieldNow() {
        handler.removeCallbacks(shieldTimeout)
        handler.removeCallbacks(urlSettle)
        handler.removeCallbacks(dismissShieldRunnable)
        handler.removeCallbacks(choiceWatch)
        shieldOverlay.dismiss()
        shieldActive = false
        shieldAwaitingChoice = false
        // 가림막이 다룬 이동에 강제 이동 안내가 겹으로 뜨지 않게 한다 —
        // 판정과 복귀 선택지는 가림막이 이미 줬다.
        navGuard.dismiss()
    }

    // ──────────────────────────────────────────────────────────
    // 신규 기능 구현 (merge/guardian-all) — 아래 함수는 전부 추가 코드다
    // ──────────────────────────────────────────────────────────

    /**
     * ⑦ 브라우저 주소창을 읽어 (a) 중계 경유를 추적하고 (b) 차단 목록과 대조한다.
     *
     * **한 번의 이동은 한 번만 소모한다(락).** 같은 이동을 phase4의
     * AdEntryDetector 경로(클릭 대기·가림막)가 이미 다루고 있으면 —
     * 가림막이 떠 있거나 클릭 대기가 유효하면 — 중계 추적의 반응은 버린다.
     * 반대 방향은 저절로 성립한다: 이쪽은 가림막 경로의 상태를 일절 건드리지
     * 않으므로 phase4 판정이 이중으로 발동할 일이 없다.
     */
    private fun watchBrowserHost() {
        val now = SystemClock.uptimeMillis()
        if (now - lastHostWatchAt < HOST_WATCH_INTERVAL_MS) return
        lastHostWatchAt = now

        val root = rootInActiveWindow ?: return
        val host = urlGuard.hostOf(root) ?: return
        if (host == lastWatchedHost) return
        lastWatchedHost = host

        val viaRelay = relayTracker.onHost(host)
        val consumedByEntryDetector = shieldActive || entryDetector.hasFreshPending()
        if (viaRelay && !consumedByEntryDetector) {
            Log.i(TAG, "중계 경유 도착: $host")
            // 이 신호를 가림막으로 넘긴다.
            //
            // 예전에는 로그만 남겼다 — "결말 UX는 phase4의 가림막이 담당한다"는
            // 분담이었는데, 정작 그 가림막이 이 경우엔 뜨지 않았다. phase4의 세
            // 근거(클릭 좌표·리다이렉터 목록·URL 파라미터)가 모두 빗나가는 광고가
            // 있기 때문이다. 실측(쿠팡 배너): `m.news.nate.com → api.ootoo.co.kr
            // (0.2초) → login.coupang.com` — 중계 도메인이 목록에 없고, 도착 URL에
            // 광고 파라미터가 없으며, 크롬이 웹 광고에 클릭 이벤트를 안 만든다.
            // 그래서 쿠팡으로 끌려가도 복귀 바가 안 떴다.
            //
            // 중계 추적은 도메인 목록이 아니라 "0.2초만 머문 제3의 도메인"이라는
            // 흔적으로 판단하므로 처음 보는 광고망도 잡는다. 출발 호스트는 넘기지
            // 않는다 — 중계 추적이 기억하는 값은 eTLD+1(nate.com)이라 주소창에서
            // 읽는 호스트(m.news.nate.com)와 형태가 달라 비교가 어긋난다.
            onNavigationDetected(readUrlBar(), forcedReason = ShieldReason.AD_RELAY)
        }

        // 검색 결과 화면이면 대조를 건너뛴다 — serp 배지가 결과 칸 단위로
        // 같은 위험을 이미 알리고 있어, 전체화면 경고까지 겹치면 소음이 된다.
        if (serp.isSerpScreen) return
        scope.launch { checkBlockedHost(host) }
    }

    /**
     * 얹을 것 1 — 지금 페이지의 도메인이 차단 목록에 있는지 본다.
     *
     * 링크를 누르는 순간이 아니라 **도착한 뒤**에 확인한다 — 접근성 트리에는
     * href가 없어 누르기 전에 알 방법이 없다 ([UrlGuard] 주석). 페이지는 이미
     * 열렸지만 개인정보를 넣거나 앱을 받기 전에 멈춰 세울 수 있다.
     */
    private suspend fun checkBlockedHost(host: String) {
        if (!urlGuard.isBlocked(host)) return
        if (!warnedBlockedHosts.shouldReport(host, layer = 3)) return

        // 스쳐 지나가는 중계는 사용자가 "보고 있는" 페이지가 아니다. 광고망
        // 도메인은 대개 차단 목록에도 있어, 그대로 두면 광고를 누를 때마다
        // 경고가 뜬다 (phase6 실측: appier.net을 0.4초 지나가며 경고).
        // 잠깐 기다렸다가 아직도 그 주소에 있을 때만 알린다.
        delay(SETTLE_BEFORE_WARN_MS)
        val stillThere = withContext(Dispatchers.Main) {
            rootInActiveWindow?.let { urlGuard.hostOf(it) }
        }
        if (stillThere != host) {
            Log.i(TAG, "차단 도메인이었으나 지나감: $host → $stillThere")
            return
        }

        // 하드 신호(등급 하한) 등록 — HIGH 판정을 URL 캐시에 미리 적어 둔다.
        // phase4의 판정 흐름(resolveFromStore)은 캐시를 항상 먼저 보고 히트하면
        // 거기서 끝나므로 이 URL은 격리 분석·LLM까지 내려가지 않고, LLM 경로는
        // 설계상 상향 전용이라 어떤 판정도 이 등급을 내리지 못한다
        // ([BlacklistHardSignal] 참고).
        val hard = BlacklistHardSignal.assessment()
        val entry = "https://$host"
        UrlNormalizer.normalize(entry)?.let { normalized ->
            val now = System.currentTimeMillis()
            runCatching {
                urlVerdictDao.upsert(
                    UrlVerdict(
                        normalizedUrl = normalized,
                        riskLevel = hard.level.name,
                        reason = hard.reason,
                        finalUrl = entry,
                        analyzedAt = now,
                        validUntil = now + UrlRiskRules.validityMs(hard.level)
                    )
                )
            }
        }

        Log.i(TAG, "차단 도메인 감지: $host")
        // 원격에는 사실만 남긴다 — 도메인명·URL은 싣지 않는다.
        GuardianEventLogger.logDomainBlocked()

        withContext(Dispatchers.Main) {
            // HIGH 위험 오버레이가 항상 우선 — 배지·가림막은 걷는다.
            serp.hide()
            if (shieldActive) dismissShieldNow()
            guardAlert.show(
                "⚠️", "위험한 사이트예요",
                "알려진 사기·피싱 사이트 목록에 있는 곳이에요.\n" +
                    "개인정보나 돈을 입력하지 마세요.",
                "안전하게 돌아가기"
            ) { performGlobalAction(GLOBAL_ACTION_BACK) }
        }
    }

    /**
     * 얹을 것 2 — 스토어를 거치지 않은 APK 설치 확인 화면(DBD)에 끼어든다.
     *
     * 시스템 설치 버튼은 누를 수도 가릴 수도 없다(FLAG_SECURE, Play 정책).
     * 전체화면 경고를 덮어 "이게 무슨 화면인지" 알리고 돌아갈 길 하나를 주는
     * 것까지가 우리 몫이다. "그래도 설치" 버튼은 두지 않는다.
     */
    private fun warnDirectDownloadInstall() {
        val source = foregroundBeforeInstaller
        if (!installSourceGuard.isDirectDownloadInstall(source)) return
        if (guardAlert.isShowing) return
        Log.i(TAG, "DBD 설치 화면 감지 — 직전 화면=${source ?: "-"}")

        // 원격에는 사실만 남긴다 — 무엇을 설치하려 했는지는 싣지 않는다.
        GuardianEventLogger.logApkInstallWarning()

        // HIGH 위험 오버레이가 항상 우선 — 배지·가림막은 걷는다.
        serp.hide()
        if (shieldActive) dismissShieldNow()
        guardAlert.show(
            "⚠️", "앱을 설치하려고 해요",
            "플레이스토어가 아닌 곳에서 받은 앱이에요.\n" +
                "모르는 앱이면 설치하지 마세요.",
            "설치 취소하고 돌아가기"
        ) { performGlobalAction(GLOBAL_ACTION_BACK) }
    }

    override fun onInterrupt() {
        serp.stop()
        adCover.hide()
        guardAlert.dismiss()
        apply(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), false)
    }

    override fun onDestroy() {
        isConnected = false
        runCatching { unregisterReceiver(screenOffReceiver) }
        serp.stop()
        guardAlert.dismiss()
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        borderOverlay.dismissAll()
        adCover.hide()
        trackedBorders.destroy()
        overlayManager.dismiss()
        shieldOverlay.dismiss()
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

        // ── "광고 모두 닫기"가 X 버튼을 찾을 때 쓰는 값들 ──

        /** 광고 영역 안에서 닫기 버튼을 찾아 내려가는 최대 깊이 */
        private const val CLOSE_SEARCH_DEPTH = 25

        /** 이보다 큰 것은 닫기 버튼이 아니라 광고 자체일 가능성이 높다 (dp). */
        private const val CLOSE_MAX_DP = 72

        /** X 탐색 시 감지 영역을 사방으로 넓혀 주는 여유 (dp) — closeSearchArea 참고 */
        private const val CLOSE_REGION_SLOP_DP = 24

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

        // ── 가림막(광고발 진입 확인)이 쓰는 값들 ──

        private const val CHROME = "com.android.chrome"

        /** 광고 클릭 후 같은 탭 이동을 살피는 주기와 횟수 (합쳐서 클릭 시간 창과 비슷하게) */
        private const val NAV_POLL_MS = 300L
        private const val NAV_POLL_COUNT = 10

        /**
         * "확인 중" 상태의 최대 시간. 주소창 안정 대기(최대 2.4초) + 격리 분석
         * (최대 3초)을 담고, 넘기면 미확인 안내로 끝맺는다 — 가림막이 결말 없이
         * 눌러붙는 일은 어떤 경로로도 있어서는 안 된다.
         */
        private const val SHIELD_MAX_MS = 7_000L

        /** 격리 분석(네트워크 수집 + 규칙 판정)의 시간 상한 */
        private const val ANALYZE_TIMEOUT_MS = 3_000L

        /**
         * LLM 2차 판단의 시간 상한. 첫 호출은 모델 로드(수 초~수십 초)를
         * 포함하므로 넉넉히 둔다 — 백그라운드라 사용자를 기다리게 하지 않는다.
         */
        private const val LLM_TIMEOUT_MS = 60_000L

        /** 딥링크 복귀 바(그냥 두기/뒤로 가기)의 표시 시간 — 원본 PROMPT_MS와 동일 */
        private const val BACK_PROMPT_MS = 12_000L

        /**
         * 복귀(뒤로 가기 반복)의 시간 예산과 간격 — [returnToOrigin] 참고.
         * 간격은 페이지가 실제로 넘어갈 시간을 줘야 한다. 너무 짧으면 아직 옛
         * 주소를 읽고 "아직 안 왔다"며 한 번 더 눌러 목적지를 지나쳐 버린다.
         */
        private const val RETURN_MAX_MS = 4_000L
        private const val RETURN_STEP_MS = 700L

        /**
         * 선택 대기 중 사용자가 화면을 떠났는지 확인하는 주기. 선택 화면은
         * 시간으로 걷지 않는다 — 사용자 결정 또는 화면 이탈로만 끝난다.
         */
        private const val CHOICE_WATCH_MS = 1_500L

        /** 저위험 확인 표시를 보여주는 시간 */
        private const val OK_SHOW_MS = 900L

        /** "출발 페이지의 광고" 지문을 이동 후에도 신뢰하는 시간 창 */
        private const val FP_FRESH_MS = 15_000L

        /** 주소창이 안정될 때까지의 재확인 주기와 횟수 (리다이렉트 체인 대기) */
        private const val URL_SETTLE_MS = 250L
        private const val URL_SETTLE_MAX_READS = 10

        /** 고위험 복귀 후 안내 문구를 보여주는 시간 */
        private const val RESULT_SHOW_MS = 2_500L

        /** "광고 모두 닫기" 동안 광고 클릭 판별을 멈추는 시간 */
        private const val CLOSE_SUPPRESS_MS = 1_500L

        // ── 신규 기능(merge/guardian-all)이 쓰는 값들 ──

        /**
         * 주소창 대조(⑦)의 최소 간격. CONTENT_CHANGED는 스크롤·로딩 중 초당
         * 수십 번 오는데, 매번 루트를 읽으면 그 자체가 IPC 부담이다.
         */
        private const val HOST_WATCH_INTERVAL_MS = 500L

        /**
         * 차단 도메인을 발견해도 이만큼 기다렸다 아직 그 주소일 때만 경고한다.
         * 스쳐 지나가는 광고망 중계에 경고하지 않기 위해서다 (phase6 값 그대로).
         */
        private const val SETTLE_BEFORE_WARN_MS = 1_500L

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
