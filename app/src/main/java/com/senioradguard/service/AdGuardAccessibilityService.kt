package com.senioradguard.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import com.senioradguard.detector.AdDetector
import com.senioradguard.detector.ScreenCaptureHelper
import com.senioradguard.logger.AdEventLogger
import com.senioradguard.overlay.OverlayManager

/**
 * AdGuardAccessibilityService
 *
 * 항상 백그라운드에서 실행되며 4가지 방식으로 광고를 감지합니다:
 *   1) UI 이벤트 패턴 매칭 (팝업 텍스트, 버튼 위치)
 *   2) Intent 인터셉트 (Play Store 강제 이동)
 *   3) 도메인 블랙리스트 조회
 *   4) AI 이미지 분석 (의심 팝업 발견 시 트리거)
 */
class AdGuardAccessibilityService : AccessibilityService() {

    private val detector by lazy { AdDetector(applicationContext) }
    private val overlayManager by lazy { OverlayManager(applicationContext) }
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 광고성 키워드 (한국어 + 영어)
    private val adKeywords = setOf(
        "설치하기", "지금 설치", "무료 다운로드", "앱 다운로드",
        "install now", "free download", "get app",
        "광고", "이벤트 참여", "지금 받기", "혜택 받기"
    )

    // Play Store / 외부 앱 패키지명 패턴
    private val storePackages = setOf(
        "com.android.vending",       // Google Play Store
        "com.sec.android.app.samsungapps" // Samsung Galaxy Store
    )

    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        when (event.eventType) {
            // 창이 바뀔 때 — Play Store 이동 감지
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (packageName in storePackages) {
                    handleStoreRedirect(packageName)
                }
            }

            // 창 내용이 바뀔 때 — 팝업/광고 배너 감지
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                scope.launch {
                    val rootNode = rootInActiveWindow ?: return@launch
                    if (detector.isAdLabelPackage(packageName)) {
                        // 유튜브/인스타그램: 차단 팝업 대신 정보 배너만
                        checkAdLabel(rootNode, packageName)
                    } else {
                        analyzeWindowContent(rootNode, packageName)
                    }
                }
            }

            // 클릭 이벤트 — 위험 버튼 사전 경고
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val clickedText = event.contentDescription?.toString()
                    ?: event.text.joinToString()
                if (isAdTriggerText(clickedText)) {
                    // 클릭 직후 경고 (설치가 실행되기 전)
                    handler.postDelayed({
                        overlayManager.showWarning(
                            message = "광고일 수 있습니다!\n'$clickedText' 버튼을 눌렀어요.\n앱이 설치될 수 있으니 확인해주세요.",
                            packageName = packageName,
                            onConfirm = { /* 사용자가 허용 */ },
                            onBlock = { performGlobalAction(GLOBAL_ACTION_BACK) },
                            currentForegroundPackage = { currentForegroundPackage() },
                            onForceHome = { performGlobalAction(GLOBAL_ACTION_HOME) }
                        )
                    }, 50)
                }
            }
        }
    }

    /**
     * 화면 전체 노드를 재귀 탐색하여 광고 패턴 검사
     */
    private suspend fun analyzeWindowContent(
        node: AccessibilityNodeInfo,
        packageName: String
    ) {
        val textContents = mutableListOf<String>()
        collectTexts(node, textContents)
        val fullText = textContents.joinToString(" ")

        // 1단계: 키워드 패턴 매칭 (빠름)
        val keywordScore = detector.scoreByKeywords(fullText)

        // 2단계: URL 블랙리스트 (링크 포함 시)
        val urls = extractUrls(fullText)
        val blacklistScore = if (urls.isNotEmpty()) detector.scoreByBlacklist(urls) else 0f

        val combinedScore = keywordScore * 0.4f + blacklistScore * 0.6f

        // combinedScore만으로는 URL 없는 순수 키워드 매칭이 0.5를 절대 못 넘으므로
        // keywordScore 자체가 높은 경우도 AI 재검사 대상에 포함시킨다.
        if (combinedScore > 0.5f || keywordScore >= 0.4f) {
            // 3단계: AI 이미지 분석 — 키워드 신뢰도가 일정 수준 이상일 때만 실제 화면 캡처
            val aiScore = if (keywordScore >= 0.4f) {
                val screenshot = captureScreen()
                if (screenshot != null) {
                    val score = detector.scoreByAI(screenshot)
                    screenshot.recycle()
                    score
                } else 0f
            } else 0f

            val finalScore = combinedScore * 0.6f + aiScore * 0.4f

            if (finalScore >= 0.6f) {
                withContext(Dispatchers.Main) {
                    overlayManager.showWarning(
                        message = buildWarningMessage(finalScore, keywordScore, blacklistScore),
                        packageName = packageName,
                        onConfirm = { /* 사용자가 무시 */ },
                        onBlock = { performGlobalAction(GLOBAL_ACTION_BACK) },
                        currentForegroundPackage = { currentForegroundPackage() },
                        onForceHome = { performGlobalAction(GLOBAL_ACTION_HOME) }
                    )
                }
                AdEventLogger.log(packageName, finalScore)
            }
        }
    }

    /**
     * 유튜브/인스타그램의 네이티브 광고 레이블 텍스트 감지.
     * 차단 팝업이 아니라 화면 상단 정보 배너만 짧게 띄운다 (영상은 계속 재생).
     */
    private suspend fun checkAdLabel(node: AccessibilityNodeInfo, packageName: String) {
        val textContents = mutableListOf<String>()
        collectTexts(node, textContents)

        if (detector.matchesAdLabel(packageName, textContents)) {
            withContext(Dispatchers.Main) {
                overlayManager.showAdInfoBanner()
            }
        }
    }

    /**
     * Play Store 강제 이동 감지 — 즉시 차단 or 경고
     */
    private fun handleStoreRedirect(storePackage: String) {
        overlayManager.showWarning(
            message = "앱 설치 화면으로 이동했어요!\n광고로 인한 이동일 수 있습니다.\n뒤로 돌아갈까요?",
            packageName = storePackage,
            onConfirm = { /* 사용자 선택으로 설치 허용 */ },
            onBlock = { performGlobalAction(GLOBAL_ACTION_BACK) },
            currentForegroundPackage = { currentForegroundPackage() },
            onForceHome = { performGlobalAction(GLOBAL_ACTION_HOME) }
        )
        AdEventLogger.logStoreRedirect(storePackage)
    }

    private fun currentForegroundPackage(): String? =
        rootInActiveWindow?.packageName?.toString()

    private fun isAdTriggerText(text: String): Boolean =
        adKeywords.any { keyword -> text.contains(keyword, ignoreCase = true) }

    private fun collectTexts(node: AccessibilityNodeInfo, result: MutableList<String>) {
        node.text?.let { result.add(it.toString()) }
        node.contentDescription?.let { result.add(it.toString()) }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectTexts(it, result) }
        }
    }

    private fun extractUrls(text: String): List<String> {
        val urlRegex = Regex("""https?://[^\s]+""")
        return urlRegex.findAll(text).map { it.value }.toList()
    }

    private fun captureScreen(): Bitmap? = ScreenCaptureHelper.getLatestFrame()

    private fun buildWarningMessage(
        finalScore: Float,
        keywordScore: Float,
        blacklistScore: Float
    ): String {
        val level = when {
            finalScore > 0.85f -> "거의 확실한"
            finalScore > 0.7f -> "의심스러운"
            else -> "주의가 필요한"
        }
        return "⚠️ $level 광고가 감지됐어요!\n" +
               "광고 같은 문구나 버튼이 있습니다.\n" +
               "뒤로 가거나 가족에게 물어보세요."
    }

    override fun onInterrupt() {
        scope.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        overlayManager.dismiss()
        overlayManager.dismissAdInfoBanner()
    }
}
