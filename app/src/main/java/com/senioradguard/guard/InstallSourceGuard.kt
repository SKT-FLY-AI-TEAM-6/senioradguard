package com.senioradguard.guard

import android.content.Context
import android.util.Log

/**
 * APK 설치 화면이 떴을 때, **Play 스토어가 아닌 경로**면 개입한다.
 *
 * ## 왜 출처를 보는가
 * 설치 화면 자체는 잘못이 아니다. Play 스토어에서 앱을 받는 것은 정상이다.
 * 위험한 건 문자로 받은 링크나 광고에서 내려받은 APK를 그대로 설치하는 경우다.
 * 그 둘을 가르는 유일한 단서가 **설치를 요청한 앱**이다.
 *
 * ## 막지 않는다, 끼어들 뿐이다
 * 시스템 설치 버튼은 우리가 누를 수도 가릴 수도 없다 —
 * `FLAG_SECURE`가 걸려 있고, 억지로 막으면 Play 정책 위반이다. 전체 화면 경고를
 * 덮어 "이게 무슨 화면인지" 알려주고 돌아갈 길을 주는 것까지가 우리 몫이다.
 * "그래도 설치"를 고르면 경고만 걷고 사용자의 선택에 맡긴다.
 */
class InstallSourceGuard(private val context: Context) {

    /**
     * 지금 뜬 설치 화면이 Play 스토어에서 온 것인가.
     *
     * `PackageInstaller`의 진행 중 세션을 훑어 요청자를 본다. 세션이 없으면
     * 판단할 근거가 없는데, 그때는 **경고하지 않는다** — 정상 설치를 막는 오탐이
     * 사용자에게 더 해롭다.
     *
     * @return true면 안전한 출처(또는 판단 불가), false면 알 수 없는 출처
     */
    fun isFromPlayStore(): Boolean {
        val sessions = runCatching { context.packageManager.packageInstaller.allSessions }
            .getOrElse {
                Log.w(TAG, "설치 세션을 읽지 못함: ${it.message}")
                return true
            }
        if (sessions.isEmpty()) return true

        // 가장 최근 세션의 요청자를 본다. 동시에 여러 개가 도는 경우는 드물다.
        val installer = sessions.lastOrNull()?.installerPackageName
        Log.i(TAG, "설치 세션 요청자=$installer")
        return installer == null || installer in TRUSTED_INSTALLERS
    }

    fun isInstallerScreen(packageName: String?): Boolean =
        packageName != null && packageName in INSTALLER_PACKAGES

    companion object {
        private const val TAG = "AdGuardInstall"

        /** 기기마다 설치 화면을 담당하는 패키지가 다르다. */
        val INSTALLER_PACKAGES = setOf(
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            // 삼성 기기는 자체 인스톨러를 쓴다
            "com.samsung.android.packageinstaller"
        )

        private val TRUSTED_INSTALLERS = setOf(
            "com.android.vending",              // Play 스토어
            "com.sec.android.app.samsungapps"   // 갤럭시 스토어
        )
    }
}
