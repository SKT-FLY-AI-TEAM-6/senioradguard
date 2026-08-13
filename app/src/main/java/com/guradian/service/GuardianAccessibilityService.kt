package com.guradian.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent

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
        @Suppress("UNUSED_VARIABLE")
        val pkg = event.packageName?.toString() ?: return
        // ── region: dispatch ──  (각 브랜치가 자기 분기만 추가)
        // ── endregion ──
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        isConnected = false
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
