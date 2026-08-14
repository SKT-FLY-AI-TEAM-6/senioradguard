package com.guradian.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.guradian.BuildConfig
import com.guradian.action.ActionBar
import com.guradian.action.ActionBarState
import com.guradian.action.CloseAffordanceFinder
import com.guradian.action.EscapeAction
import com.guradian.action.PrimaryAction
import com.guradian.agent.AdClassifier
import com.guradian.agent.AgentPipeline
import com.guradian.agent.CandidateExtractor
import com.guradian.agent.GeminiClassifier
import com.guradian.agent.StubClassifier
import com.guradian.overlay.AdBorderOverlay
import com.guradian.overlay.AdMarkStyle
import com.guradian.overlay.BorderTracker
import com.guradian.rule.RuleEngine
import com.guradian.rule.RuleVerdict
import com.guradian.serp.SerpFeature
import com.guradian.store.DetectionLog
import com.guradian.store.InMemoryVerdictStore
import com.guradian.store.NoopDetectionLog
import com.guradian.store.VerdictStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 단일 진입점. 이벤트를 받아 각 레이어에 배분하기만 한다.
 *
 *   Layer 1 (rule/)   공식 광고 표기 · 탈출 룰 판정   — 항상, 자동
 *   Layer 2 (agent/)  AI 판별                       — [광고 찾기]를 누를 때만
 *   action/           액션바 · 광고 닫기 · 전환 탈출
 *
 * ## 이 파일의 region 주석은 장식이 아니다
 * 세 브랜치(feat/layer1-rule · feat/layer2-agent · feat/action-bar)가 전부 이
 * 파일을 건드린다. 각자 자기 region 안에만 쓰면 병합이 충돌 없이 끝난다.
 * **region 밖을 고쳐야 하면 그 커밋을 멈추고 베이스에 먼저 반영한 뒤 리베이스한다.**
 */
class GuardianAccessibilityService : AccessibilityService() {

    private val targetApps = setOf(
        "com.google.android.youtube",
        "com.instagram.android",
        "com.towneers.www",
        "com.android.chrome"
        // 삼성 인터넷 제외 — 렌더링된 웹 페이지를 접근성 트리에 노출하지 않는다
        // (같은 URL에서 크롬 노드 421개 vs 삼성 20개, 본문 텍스트 0개)
    )

    private val storePackages = setOf(
        "com.android.vending",
        "com.sec.android.app.samsungapps"
    )

    // ── region: layer1 ──  (feat/layer1-rule 이 채운다)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** Layer 1·2가 함께 쓰는 룰 진입점. BrowserHost 인스턴스도 여기 하나뿐이다. */
    private val ruleEngine = RuleEngine()

    // TYPE_ACCESSIBILITY_OVERLAY 창은 접근성 서비스 자신의 컨텍스트로 추가해야 한다.
    // applicationContext를 쓰면 창 토큰이 없어 addView가 BadTokenException으로 죽는다
    // ("token null is not valid") — 광고를 감지하는 순간마다 앱이 크래시한다.
    private val borderOverlay by lazy { AdBorderOverlay(this) }

    private val borderTracker by lazy { BorderTracker(borderOverlay, ruleEngine, scope) }

    /** 대상 앱일 때만 루트를 준다. 아니면 null — BorderTracker가 그걸 보고 테두리를 지운다. */
    private fun targetRoot(): android.view.accessibility.AccessibilityNodeInfo? =
        rootInActiveWindow?.takeIf { it.packageName?.toString() in targetApps }

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
    // ── endregion ──

    // ── region: layer2 ──  (feat/layer2-agent 이 채운다)

    /**
     * 캐시 전용 경로를 BorderTracker에 건다.
     *
     * onServiceConnected가 아니라 여기서 거는 이유: 그 함수는 세 브랜치가 공유하는
     * region 밖이라 건드리면 병합이 충돌한다. init 블록은 이 region 안이다.
     */
    init {
        borderTracker.onScanComplete = ::cachedGuesses
    }

    /** 판별 진행 중 플래그. 왕복이 길어 겹쳐 돌면 호출만 늘어난다. */
    private val classifying = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 스크롤 경로에서 도는 후보 추출의 시간 상한. 이건 확정 테두리 스캔에 **이어서**
     * 도는 두 번째 순회라, 상한이 없으면 느린 앱에서 테두리가 손가락을 못 따라온다.
     */
    private val scrollExtractBudgetMs = 150L

    private val extractor by lazy { CandidateExtractor(ruleEngine.browserHost) }

    private val verdictStore: VerdictStore = InMemoryVerdictStore()

    private val pipeline by lazy {
        // 키가 없으면 규칙 기반 대역으로 물러난다 — 키를 아직 안 받은 팀원도 앱을
        // 빌드해 파이프라인 전 구간을 돌려볼 수 있어야 한다.
        val key = BuildConfig.GEMINI_API_KEY
        val classifier: AdClassifier =
            if (key.isNotBlank()) GeminiClassifier(key) else StubClassifier()
        Log.i(TAG, "layer2 판별기=${classifier.source}")

        AgentPipeline(store = verdictStore, classifier = classifier)
    }

    /**
     * 스크롤해도 점선이 카드를 따라오게 하는 **캐시 전용** 경로.
     *
     * 판별기를 부르지 않으므로 외부로 나가는 것이 없다. 그래서 자동으로 돌아도
     * 프라이버시 논거를 해치지 않는다 — 나가는 시점은 [findAdsNow] 하나뿐이다.
     * 다만 이건 이번 프레임의 **두 번째** 트리 순회라 상한을 걸어 확정 테두리의
     * 추종을 방해하지 않게 한다.
     */
    private suspend fun cachedGuesses(
        root: android.view.accessibility.AccessibilityNodeInfo,
        confirmed: List<android.graphics.Rect>
    ): List<android.graphics.Rect> {
        if (!isAiEnabled()) return emptyList()
        return runCatching {
            pipeline.run(
                extractor.extract(root, confirmed, budgetMs = scrollExtractBudgetMs),
                allowClassify = false
            ).regions
        }.getOrDefault(emptyList())
    }

    /**
     * 사용자가 [광고 찾기]를 눌렀을 때만 호출된다.
     *
     * **자동 실행 경로를 만들지 말 것** — 화면 텍스트가 외부로 나가는 유일한
     * 지점이고, 그 시점이 사용자의 명시적 동작과 1:1로 대응해야 한다. 원본에 있던
     * `scheduleLayer2()` / `runLayer2` / `LAYER2_IDLE_MS`(유휴 600ms 자동 실행)를
     * 이 함수 하나로 대체했다.
     *
     * @param onResult 표시할 점선 영역과 진행 상황. 액션바가 "찾는 중…"과
     *        "0건" 피드백에 쓴다 (task 2). 메인 스레드에서 불린다.
     */
    fun findAdsNow(onResult: (FindResult) -> Unit = {}) {
        if (!isAiEnabled()) {
            onResult(FindResult(emptyList(), busy = false, aiDisabled = true))
            return
        }
        if (!classifying.compareAndSet(false, true)) return

        onResult(FindResult(emptyList(), busy = true))

        // 결과가 늦게 왔는데 그 사이 화면이 바뀌었으면 표시하지 않는다.
        val generation = borderTracker.generation
        val excluded = borderTracker.confirmedRegions

        scope.launch {
            try {
                val root = targetRoot() ?: run {
                    withContext(Dispatchers.Main) { onResult(FindResult(emptyList(), busy = false)) }
                    return@launch
                }
                // 버튼을 눌러 도는 경로라 상한을 걸지 않는다. 후보를 빠짐없이 봐야
                // 판별이 의미가 있고, 사용자는 이미 기다리기로 한 상태다.
                val candidates = extractor.extract(root, excluded)
                val result = pipeline.run(candidates)
                Log.i(
                    TAG,
                    "layer2 출처=${candidates.firstOrNull()?.sourceKey ?: "-"} " +
                        "후보=${candidates.size} 캐시=${result.cacheHits} " +
                        "판별=${result.classified} 보류=${result.skippedByLimit} " +
                        "표시=${result.regions.size}"
                )
                withContext(Dispatchers.Main) {
                    if (generation != borderTracker.generation) {
                        // 화면이 넘어갔다. 판정은 캐시에 남아 다음에 같은 카드가
                        // 나오면 즉시 뜬다.
                        onResult(FindResult(emptyList(), busy = false, staleScreen = true))
                        return@withContext
                    }
                    borderOverlay.show(AdMarkStyle.AI_GUESS, result.regions)
                    onResult(FindResult(result.regions, busy = false))
                }
            } finally {
                classifying.set(false)
            }
        }
    }

    /** 기본 OFF 옵트인. 화면 텍스트가 외부로 나가므로 사용자가 켜야만 동작한다. */
    fun isAiEnabled(): Boolean =
        getSharedPreferences("settings", MODE_PRIVATE).getBoolean(PREF_AI_CLASSIFY, false)

    /**
     * [findAdsNow]의 진행 상황.
     *
     * **0건일 때도 반드시 알려야 한다.** 무반응은 고장으로 읽힌다 — 어르신은
     * 버튼이 안 먹었다고 생각하고 계속 누른다.
     */
    data class FindResult(
        val regions: List<android.graphics.Rect>,
        val busy: Boolean,
        /** 토글이 꺼져 있어 아예 돌지 않았다 */
        val aiDisabled: Boolean = false,
        /** 결과가 늦게 와서 버렸다 */
        val staleScreen: Boolean = false
    )
    // ── endregion ──

    // ── region: serp ──  (feat/serp-risk 가 채운다 — 검색 결과 위험도)

    /**
     * 구글·크롬 검색 결과의 사이트별 위험도. 기능 전체가 `com.guradian.serp`에 있고
     * 이 파일에는 **입구 하나만** 둔다 ([SerpFeature] 참고).
     *
     * 광고 감지와 공유하는 것이 하나도 없다 — 오버레이 창도, 룰 엔진도, 판정 캐시도
     * 따로다. 병합할 때 이 region과 dispatch 한 줄, 정리 두 줄만 보면 된다.
     */
    private val serp by lazy { SerpFeature(this, scope, BuildConfig.GEMINI_API_KEY) }
    // ── endregion ──

    // ── region: action ──  (feat/action-bar 가 채운다)

    /**
     * 액션바는 **서비스 컨텍스트(this)** 로 만든다. applicationContext를 쓰면
     * 창 토큰이 없어 BadTokenException으로 죽는다.
     */
    private val actionBar by lazy { ActionBar(this, ::onPrimaryClick) }

    /**
     * 표시가 갱신될 때마다 액션바를 다시 계산한다. 스캔이 비동기라 이벤트 처리
     * 시점에는 아직 옛 값이어서, 여기 걸지 않으면 버튼이 한 박자 늦게 바뀐다.
     */
    init {
        borderTracker.onApplied = { refreshActionBar() }
    }

    private val closeFinder = CloseAffordanceFinder()

    private val detectionLog: DetectionLog = NoopDetectionLog

    private val escapeAction by lazy {
        EscapeAction(
            onBack = { performGlobalAction(GLOBAL_ACTION_BACK) },
            onForceHome = { performGlobalAction(GLOBAL_ACTION_HOME) },
            currentForegroundPackage = { rootInActiveWindow?.packageName?.toString() }
        )
    }

    /** 지금 탈출해야 하는 상황인가. 사용자가 [돌아가기]를 누르면 해제된다. */
    private var pendingEscape: RuleVerdict.Escape? = null

    /** 어느 패키지에 갇혔는가. BACK이 먹었는지 판단하는 기준. */
    private var trappedIn: String? = null

    /**
     * 액션바 상태를 다시 계산해 반영한다. 확정 테두리 수와 탈출 상태가 바뀔 때마다.
     * 메인 스레드에서만 부른다.
     */
    private fun refreshActionBar(busy: Boolean = false) {
        val confirmed = borderTracker.confirmedRegions
        actionBar.setState(
            ActionBarState(
                escape = pendingEscape?.reason,
                adRegionCount = confirmed.size + borderOverlay.regionsOf(AdMarkStyle.AI_GUESS).size,
                busy = busy,
                aiEnabled = isAiEnabled()
            )
        )
        // 광고를 가리면 구글 정책 위반이다. 하단이 겹치면 위로 옮긴다.
        actionBar.avoid(confirmed + borderOverlay.regionsOf(AdMarkStyle.AI_GUESS))
    }

    /** 주 버튼 하나가 세 동작을 나눠 맡는다. 지금 어느 것인지는 상태 머신이 정한다. */
    private fun onPrimaryClick(action: PrimaryAction) {
        when (action) {
            PrimaryAction.ESCAPE -> doEscape()
            PrimaryAction.CLOSE_AD -> doCloseAd()
            PrimaryAction.FIND_AD -> doFindAd()
            PrimaryAction.BUSY, PrimaryAction.NONE -> Unit
        }
    }

    /** task 3 — BACK, 안 먹으면 HOME. */
    private fun doEscape() {
        val escape = pendingEscape ?: return
        // hostHash는 크롬(악성 URL)에서만 채워진다. 스토어 패키지명은 host가 아니라
        // 여기 넣지 않는다 — DetectionLog 시그니처가 원문을 못 받게 막고 있다.
        detectionLog.onEscape(escape.reason, escape.hostHash)

        escapeAction.perform(trappedIn) {
            pendingEscape = null
            trappedIn = null
            refreshActionBar()
        }
    }

    /**
     * task 2 — 닫기 어포던스를 찾아 대리 클릭.
     *
     * **사용자가 [광고 닫기]를 누른 이 경로에서만 ACTION_CLICK을 실행한다.**
     * 자동 실행 경로를 만들면 그건 광고 차단이고 정책 위반이다.
     */
    private fun doCloseAd() {
        val root = targetRoot() ?: return
        val adRect = (borderTracker.confirmedRegions +
            borderOverlay.regionsOf(AdMarkStyle.AI_GUESS)).firstOrNull() ?: return

        when (val found = closeFinder.find(root, adRect, resources.displayMetrics.density)) {
            is CloseAffordanceFinder.Result.Found -> {
                val ok = found.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                detectionLog.onAdClosed(source = "rule", succeeded = ok)
                // 조용한 실패는 고장으로 읽힌다 — 눌렀는데 아무 일도 없으면
                // 사용자는 버튼이 죽었다고 생각하고 계속 누른다.
                if (!ok) actionBar.say("닫기 버튼을 찾지 못했어요")
            }

            CloseAffordanceFinder.Result.NotYet ->
                actionBar.say("잠시 후에 건너뛸 수 있어요")

            CloseAffordanceFinder.Result.NotFound -> {
                detectionLog.onAdClosed(source = "rule", succeeded = false)
                actionBar.say("닫기 버튼을 찾지 못했어요")
            }
        }
    }

    /**
     * task 1 — Layer 2를 1회 실행. **사용자가 기다린다.**
     *
     * 즉시 "찾는 중…"으로 바꾸고, 0건이어도 반드시 결과를 말한다.
     */
    private fun doFindAd() {
        findAdsNow { result ->
            if (result.busy) {
                refreshActionBar(busy = true)
                return@findAdsNow
            }
            if (result.aiDisabled) {
                actionBar.say("설정에서 AI 광고 판별을 켜주세요")
            } else if (result.staleScreen) {
                actionBar.say("화면이 바뀌어서 다시 눌러주세요")
            } else {
                detectionLog.onAdDetected("agent", result.regions.size, aiGuessed = true)
                actionBar.reportFound(result.regions.size)
            }
            refreshActionBar(busy = false)
        }
    }
    // ── endregion ──

    override fun onServiceConnected() {
        isConnected = true
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_VIEW_CLICKED
            // serpApps가 더해져 있다. 시스템이 여기 적힌 패키지의 이벤트만 넘겨주므로,
            // 구글 앱(검색)이 빠지면 검색 결과 위험도가 그 앱에서 통째로 잠자코 있는다.
            // 다른 레이어의 동작 범위는 그대로다 — 아래 dispatch가 여전히
            // `pkg !in targetApps`로 자기 몫을 걸러낸다.
            packageNames = (targetApps + storePackages + SerpFeature.PACKAGES).toTypedArray()
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            // 병합하지 않고 전부 받는다. 150이면 스크롤 이벤트도 초당 6~7개로 묶여
            // 테두리가 끊겨 보인다 — 스무스함의 상한이 여기서 정해진다.
            notificationTimeout = 0
        }
        actionBar.show()
        refreshActionBar()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        // ── region: dispatch ──  (각 브랜치가 자기 분기만 추가)

        // 스토어 판정은 targetApps 필터보다 **먼저** 와야 한다. 스토어는 감지 대상이
        // 아니라 "끌려간 곳"이라서, 아래 필터에 걸리면 탈출을 영영 못 알아챈다.
        ruleEngine.checkPackage(pkg)?.let { escape ->
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                onEscapeDetected(escape)
            }
            return
        }

        // serp — 검색 결과 위험도. targetApps에 없는 구글 앱도 봐야 해서 필터보다 앞이다.
        serp.onEvent(event, pkg)

        if (pkg !in targetApps) return

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val clickedText = event.contentDescription?.toString()
                ?: event.text.joinToString(separator = " ")
            ruleEngine.checkClick(clickedText)?.let { onEscapeDetected(it) }
            // 클릭만으로는 화면이 바뀌지 않아 다시 스캔할 게 없다.
            // (화면이 바뀌면 CONTENT_CHANGED가 따로 온다)
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            borderTracker.onScroll(scrollDeltaY(event))
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // 페이지가 새로 떴다. 악성 URL인지 확인한다 — 지금은 항상 false지만
            // 호출부를 배선해 둬야 task 4에서 구현체만 갈아끼울 수 있다.
            checkUrlNow()
        }

        borderTracker.onEvent(event) { targetRoot() }
        // ── endregion ──
    }

    /**
     * 탈출 상황을 감지했다.
     *
     * 화면을 막지 않는다 — 원본은 전체 화면 경고 팝업으로 터치를 가로챘지만,
     * 여기서는 액션바가 빨강으로 확장되고 진동할 뿐 사용자가 스스로 조작할 자유는
     * 그대로 둔다. 설치를 정말 원했던 사용자를 가두지 않기 위해서다.
     */
    private fun onEscapeDetected(escape: RuleVerdict.Escape) {
        Log.i(TAG, "escape=${escape.reason} ${escape.detail}")
        pendingEscape = escape
        trappedIn = rootInActiveWindow?.packageName?.toString()
        refreshActionBar()
        actionBar.expandForEscape()
        vibrateForEscape()
    }

    /**
     * 지금 보고 있는 페이지가 악성 URL인지 확인한다. **크롬에서만 의미가 있다.**
     *
     * 노드 조회와 조회 자체가 IPC·I/O라 백그라운드로 보낸다.
     */
    private fun checkUrlNow() {
        scope.launch {
            val root = targetRoot() ?: return@launch
            val escape = runCatching { ruleEngine.checkUrl(root) }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main) { onEscapeDetected(escape) }
        }
    }

    /** ESCAPE 진입 진동. 화면을 안 보고 있어도 무언가 일어났음을 알린다. */
    private fun vibrateForEscape() {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator ?: return
        val effect = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            android.os.VibrationEffect.createPredefined(
                android.os.VibrationEffect.EFFECT_HEAVY_CLICK
            )
        } else {
            android.os.VibrationEffect.createOneShot(
                200, android.os.VibrationEffect.DEFAULT_AMPLITUDE
            )
        }
        runCatching { vibrator.vibrate(effect) }
    }

    override fun onInterrupt() {
        // serp — 여기서 안 지우면 시스템이 서비스를 멈춘 뒤에도 배지가 화면에 남는다.
        serp.stop()
        borderTracker.clear()
    }

    override fun onDestroy() {
        isConnected = false
        // serp — 창을 떼지 않으면 서비스가 죽어도 배지가 화면에 남는다.
        serp.stop()
        borderTracker.clear()
        escapeAction.cancel()
        actionBar.dismiss()
        scope.cancel()
        borderOverlay.dismissAll()
        super.onDestroy()
    }

    companion object {
        const val TAG = "GurADian"

        /** "settings" prefs — AI 광고 판별 옵트인 키. MainActivity 토글과 공유한다. */
        const val PREF_AI_CLASSIFY = "ai_classify"


        /**
         * 서비스가 지금 시스템에 연결돼 있는지. 접근성 설정의 "켜짐" 표시와 별개다 —
         * 설정에는 켜짐으로 남은 채 서비스만 죽는 경우를 구분하려고 둔다
         * (ServiceStatus 참고).
         */
        @Volatile
        var isConnected: Boolean = false
            private set
    }
}
