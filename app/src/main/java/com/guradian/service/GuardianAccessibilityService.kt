package com.guradian.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.guradian.overlay.AdBorderOverlay
import com.guradian.overlay.BorderTracker
import com.guradian.rule.RuleEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

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
    // ── endregion ──

    // ── region: action ──  (feat/action-bar 가 채운다)
    // ── endregion ──

    override fun onServiceConnected() {
        isConnected = true
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_VIEW_CLICKED
            packageNames = (targetApps + storePackages).toTypedArray()
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            // 병합하지 않고 전부 받는다. 150이면 스크롤 이벤트도 초당 6~7개로 묶여
            // 테두리가 끊겨 보인다 — 스무스함의 상한이 여기서 정해진다.
            notificationTimeout = 0
        }
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

        borderTracker.onEvent(event) { targetRoot() }
        // ── endregion ──
    }

    /**
     * 탈출 상황을 감지했다. 실제 대응(액션바 빨강 확장 + BACK/HOME)은 task 3의
     * 몫이라 feat/action-bar가 이 자리를 채운다. 지금은 판정만 도착한다.
     */
    private fun onEscapeDetected(escape: com.guradian.rule.RuleVerdict.Escape) {
        android.util.Log.i(TAG, "escape=${escape.reason} ${escape.detail}")
    }

    override fun onInterrupt() {
        borderTracker.clear()
    }

    override fun onDestroy() {
        isConnected = false
        borderTracker.clear()
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
