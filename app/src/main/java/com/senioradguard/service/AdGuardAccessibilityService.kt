package com.senioradguard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.senioradguard.overlay.AdBorderOverlay
import com.senioradguard.overlay.AdMarkStyle
import com.senioradguard.region.AdRegionScanner

/**
 * 단일 진입점. 이벤트를 받아 루트 노드를 한 번만 가져오고 각 레이어에 배분한다.
 *
 *   Layer 1  공식 광고 라벨 감지  → 비차단 테두리 (AdBorderOverlay)
 *   Layer 2  LLM 판별            → Phase 2에서 추가
 *   Layer 3  설치 유도 감지       → 차단 경고 (Task 8에서 추가)
 */
class AdGuardAccessibilityService : AccessibilityService() {

    private val targetApps = setOf(
        "com.google.android.youtube",   // 유튜브
        "com.instagram.android",        // 인스타그램
        "com.towneers.www",             // 당근
        "com.android.chrome",           // 크롬 (모바일 웹)
        "com.sec.android.app.sbrowser"  // 삼성 인터넷 (모바일 웹)
    )

    private val scanner = AdRegionScanner()
    // TYPE_ACCESSIBILITY_OVERLAY 창은 접근성 서비스 자신의 컨텍스트로 추가해야 한다.
    // applicationContext를 쓰면 창 토큰이 없어 addView가 BadTokenException으로 죽는다
    // ("token null is not valid") — 광고를 감지하는 순간마다 앱이 크래시한다.
    private val borderOverlay by lazy { AdBorderOverlay(this) }
    private val handler = Handler(Looper.getMainLooper())

    private var lastScan = 0L

    // 이벤트가 끊겨도 광고가 아직 화면에 있는지 직접 재확인하고, 사라졌을 때만 표시를 해제
    private val recheck = Runnable {
        val root = rootInActiveWindow
        if (root != null && root.packageName?.toString() in targetApps) {
            applyLayer1(scanner.scan(root))
        } else {
            applyLayer1(emptyList())
        }
    }

    override fun onServiceConnected() {
        Log.e("SAG_DEBUG", "onServiceConnected")
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        Log.e("SAG_DEBUG", "EVT pkg=$pkg type=${event.eventType} target=${pkg in targetApps} root=${rootInActiveWindow != null}")

        if (pkg !in targetApps) {
            // 다른 앱 화면으로 전환되면 테두리 해제
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                pkg != "com.android.systemui" && pkg != packageName
            ) {
                applyLayer1(emptyList())
            }
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastScan < 200) return
        lastScan = now

        val root = rootInActiveWindow ?: return
        val regions = scanner.scan(root)
        Log.e("SAG_DEBUG", "event scan pkg=$pkg regions=$regions")
        debugDump(root, 0)
        applyLayer1(regions)
    }

    private fun debugDump(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int) {
        if (depth > 60) return
        val t = node.text?.toString() ?: ""
        val cd = node.contentDescription?.toString() ?: ""
        if (t.contains("광고") || cd.contains("광고")) {
            val b = Rect().also { node.getBoundsInScreen(it) }
            Log.e("SAG_DEBUG",
                "MATCH depth=$depth text='$t' cd='$cd' vis=${node.isVisibleToUser} bounds=$b id=${node.viewIdResourceName}"
            )
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { debugDump(it, depth + 1) }
        }
    }

    private fun applyLayer1(regions: List<Rect>) {
        Log.e("SAG_DEBUG", "applyLayer1 regions=$regions")
        handler.removeCallbacks(recheck)
        if (regions.isNotEmpty()) handler.postDelayed(recheck, 1000)
        borderOverlay.show(AdMarkStyle.CONFIRMED, regions)
    }

    override fun onInterrupt() {
        applyLayer1(emptyList())
    }

    override fun onDestroy() {
        handler.removeCallbacks(recheck)
        borderOverlay.dismissAll()
        super.onDestroy()
    }
}
