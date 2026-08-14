package com.senioradguard.guard

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
class InstallSourceGuard {

    /**
     * 지금 뜬 설치 화면이 믿을 만한 출처에서 온 것인가.
     *
     * ## 두 번 갈아엎은 판단 근거
     * 1. `PackageInstaller` 세션의 요청자 → **세션은 사용자가 "설치"를 누른 뒤에
     *    생긴다.** 확인 화면에서는 목록이 비어 있어 늘 "판단 불가"였다.
     * 2. 설치 화면 직전에 떠 있던 앱 → **파일 관리자는 우리 이벤트 필터에 없어서
     *    볼 수 없다.** 실기기에서 직전 앱이 계속 null로 나왔다.
     *
     * ## 지금 쓰는 근거 — "최근에 스토어를 봤는가"
     * 스토어(Play·갤럭시)는 필터에 있으므로 사용자가 거기서 앱을 받으면 우리가
     * 그 화면 전환을 **반드시 본다.** 반대로 문자·브라우저에서 받은 APK를 파일
     * 관리자로 열면 스토어를 거치지 않는다.
     *
     * 그래서 "설치 화면이 떴는데 최근에 스토어를 본 적이 없다"를 알 수 없는 출처로
     * 본다. 스토어에서 설치하다 서비스가 재시작되면 잘못 경고할 수 있는데, 그
     * 경고는 버튼 하나짜리 안내이고 설치를 막지도 않는다.
     *
     * @param msSinceStoreSeen 스토어 화면을 마지막으로 본 뒤 지난 시간. 본 적이
     *        없으면 null.
     */
    fun isTrustedSource(msSinceStoreSeen: Long?): Boolean {
        Log.i(TAG, "설치 화면 — 스토어를 본 지 ${msSinceStoreSeen ?: "-"}ms")
        return msSinceStoreSeen != null && msSinceStoreSeen <= STORE_RECENCY_MS
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

        /**
         * 스토어를 본 뒤 이 시간 안에 설치 화면이 뜨면 스토어에서 온 것으로 본다.
         * 스토어에서 "설치"를 누르고 확인 화면이 뜨기까지는 몇 초면 충분하다.
         */
        const val STORE_RECENCY_MS = 30_000L

        val TRUSTED_INSTALLERS = setOf(
            "com.android.vending",              // Play 스토어
            "com.sec.android.app.samsungapps"   // 갤럭시 스토어
        )
    }
}
