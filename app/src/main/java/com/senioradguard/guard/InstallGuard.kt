package com.senioradguard.guard

import android.os.Handler
import android.os.Looper
import com.senioradguard.logger.AdEventLogger
import com.senioradguard.overlay.OverlayManager

/**
 * Layer 3 — 앱 설치 유도 감지.
 *
 * Layer 1·2와 달리 여기서는 터치를 막는다. 광고 클릭 방해가 아니라 앱이 실제로
 * 설치되기 직전에 개입하는 것이라 성격이 다르다.
 *
 * @param onBack       "뒤로 가기" 선택 시 실행 (performGlobalAction(GLOBAL_ACTION_BACK))
 * @param onForceHome  경고 후에도 화면이 바뀌지 않을 때 홈으로 (GLOBAL_ACTION_HOME)
 * @param currentForegroundPackage 현재 최상단 패키지 조회
 */
class InstallGuard(
    private val overlayManager: OverlayManager,
    private val onBack: () -> Unit,
    private val onForceHome: () -> Unit,
    private val currentForegroundPackage: () -> String?
) {

    private val handler = Handler(Looper.getMainLooper())

    private val storePackages = setOf(
        "com.android.vending",              // Google Play Store
        "com.sec.android.app.samsungapps"   // Samsung Galaxy Store
    )

    fun isStorePackage(pkg: String): Boolean = pkg in storePackages

    /** Play Store / 갤럭시 스토어로 강제 이동했을 때. */
    fun onStoreRedirect(storePackage: String) {
        overlayManager.showWarning(
            message = "앱 설치 화면으로 이동했어요!\n광고로 인한 이동일 수 있습니다.\n뒤로 돌아갈까요?",
            packageName = storePackage,
            onConfirm = { /* 사용자 선택으로 설치 허용 */ },
            onBlock = onBack,
            currentForegroundPackage = currentForegroundPackage,
            onForceHome = onForceHome
        )
        AdEventLogger.logStoreRedirect(storePackage)
    }

    /**
     * "설치하기" 등 위험 버튼을 눌렀을 때. 설치가 실행되기 전에 끼어든다.
     *
     * 50ms 늦추는 이유: 클릭 이벤트는 앱이 화면을 바꾸기 전에 도착한다. 곧바로
     * 경고를 띄우면 뒤이어 열리는 화면에 가려진다.
     */
    fun onClick(text: String, packageName: String) {
        if (!InstallTriggerRules.isInstallTrigger(text)) return
        handler.postDelayed({
            overlayManager.showWarning(
                message = "광고일 수 있습니다!\n'$text' 버튼을 눌렀어요.\n앱이 설치될 수 있으니 확인해주세요.",
                packageName = packageName,
                onConfirm = { /* 사용자가 허용 */ },
                onBlock = onBack,
                currentForegroundPackage = currentForegroundPackage,
                onForceHome = onForceHome
            )
        }, 50)
        AdEventLogger.logInstallBlocked(text)
    }
}
