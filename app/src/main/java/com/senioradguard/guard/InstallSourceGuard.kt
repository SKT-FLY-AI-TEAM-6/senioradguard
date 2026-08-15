package com.senioradguard.guard

/**
 * APK 설치 확인 화면이 떴을 때, **Play 스토어를 거치지 않은 경로(DBD)**면 개입한다.
 *
 * ## 왜 출처를 보는가 (phase6 원문 유지)
 * 설치 화면 자체는 잘못이 아니다. Play 스토어에서 앱을 받는 것은 정상이다.
 * 위험한 건 광고나 문자로 받은 링크에서 내려받은 APK(Drive-by-Download)를
 * 그대로 설치하는 경우다. 그 둘을 가르는 유일한 단서가 **설치를 요청한 앱**이다.
 *
 * ## [InstallGuard]와 완전히 별개다
 * InstallGuard(phase4, 무변경)는 광고 클릭이 **Play 스토어로 이어지는** 흐름을
 * 본다. 이 클래스는 반대로 **스토어를 거치지 않고** 시스템 인스톨러 확인 화면이
 * 바로 뜨는 흐름을 본다. 두 기능은 트리거가 다르고 상태를 공유하지 않는다.
 *
 * ## 판정 — 지금 화면의 출처만 본다
 * phase6은 "최근 30초 안에 스토어를 봤는가"로 갈랐지만 여기서는 그 시간창을
 * 쓰지 않는다. 시간창은 (a) 스토어를 켜둔 채 브라우저에서 APK를 받으면 뚫리고
 * (b) 서비스 재시작 직후 오탐을 낸다. 대신 **설치 확인 화면 직전에 떠 있던
 * 앱**을 출처로 본다 — 스토어(신뢰 출처)에서 넘어온 화면이 아니면 DBD다.
 * 정식 스토어 설치는 애초에 시스템 인스톨러 확인 화면을 띄우지 않으므로,
 * 이 화면이 떴다는 사실 자체가 이미 강한 신호이기도 하다.
 *
 * ## 막지 않는다, 끼어들 뿐이다
 * 시스템 설치 버튼은 우리가 누를 수도 가릴 수도 없다 — FLAG_SECURE가 걸려
 * 있고, 억지로 막으면 Play 정책 위반이다. 전체화면 경고를 덮어 "이게 무슨
 * 화면인지" 알려주고 돌아갈 길 하나를 주는 것까지가 우리 몫이다.
 * "그래도 설치" 버튼은 두지 않는다 — 그 버튼이 있다는 사실만으로 어르신은
 * 눌러도 되는 것으로 읽는다.
 */
class InstallSourceGuard {

    /** 지금 뜬 창이 시스템 APK 설치 화면인가. */
    fun isInstallerScreen(packageName: String?): Boolean =
        packageName != null && packageName in INSTALLER_PACKAGES

    /**
     * 이 설치 화면이 스토어를 거치지 않은 다운로드(DBD)에서 온 것인가.
     *
     * @param previousForeground 설치 화면이 뜨기 직전에 떠 있던 앱.
     *        서비스가 창 전환마다 갱신해 둔 값이며, 인스톨러 자신은 제외하고
     *        넘겨야 한다. 모르면(null) DBD로 본다 — 출처를 모르는 설치를
     *        조용히 통과시키는 쪽이 더 해롭고, 경고는 버튼 하나짜리 안내일
     *        뿐 설치를 막지도 않는다.
     *
     * 로그는 호출부(서비스)가 남긴다 — android.util.Log를 여기서 부르면
     * JVM 단위 테스트가 "not mocked"로 죽는다.
     */
    fun isDirectDownloadInstall(previousForeground: String?): Boolean =
        previousForeground == null || previousForeground !in TRUSTED_INSTALLERS

    companion object {
        /** 기기마다 설치 화면을 담당하는 패키지가 다르다. (phase6 목록 그대로) */
        val INSTALLER_PACKAGES = setOf(
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            // 삼성 기기는 자체 인스톨러를 쓴다
            "com.samsung.android.packageinstaller"
        )

        /** 여기서 넘어온 설치 화면은 정상 흐름으로 본다. */
        val TRUSTED_INSTALLERS = setOf(
            "com.android.vending",              // Play 스토어
            "com.sec.android.app.samsungapps"   // 갤럭시 스토어
        )
    }
}
