package com.guradian.rule

/**
 * "여기서 빠져나와야 하는 상황인가"를 판정한다. — task 1 (판정), task 3 (동작)
 *
 * senioradguard `InstallGuard`의 `storePackages` 매칭과 `InstallTriggerRules`를
 * 흡수했다. **판정과 동작을 갈라놓은 것이 재구축의 핵심**이다 — 판정은 룰(task 1),
 * 실제 탈출은 [com.guradian.action.EscapeAction](task 3), 경고 UI는 액션바(task 2).
 * 원본에서는 이 셋이 InstallGuard 하나에 뭉쳐 있었다.
 *
 * 안드로이드 의존이 없는 순수 함수라 단위 테스트로 전부 덮는다. 오탐 여부가
 * 사용자 경험을 직접 좌우한다.
 */
object EscapeRules {

    /** Play Store / 갤럭시 스토어. 광고가 강제로 끌고 가는 대표적인 목적지다. */
    private val storePackages = setOf(
        "com.android.vending",              // Google Play Store
        "com.sec.android.app.samsungapps"   // Samsung Galaxy Store
    )

    private val installKeywords = setOf(
        "설치하기", "지금 설치", "무료 다운로드", "앱 다운로드",
        "install now", "free download", "get app",
        "광고", "이벤트 참여", "지금 받기", "혜택 받기"
    )

    /**
     * 위 키워드를 품고 있지만 설치와 무관한 버튼들.
     *
     * "광고"가 키워드에 있어서 유튜브의 "광고 건너뛰기"를 누를 때마다 경고가 뜨는
     * 문제가 생긴다. 광고를 피하려는 행동을 앱이 가로막는 셈이라 정반대다.
     * 광고 자체를 알리는 일은 테두리가 이미 하고 있으므로, 여기서는 설치로
     * 이어지지 않는 문구를 먼저 걸러낸다.
     */
    private val benignTexts = setOf(
        "광고 건너뛰기", "건너뛰기", "광고 신고", "광고 정보", "광고 숨기기",
        "이 광고가 표시된 이유", "skip ad", "skip ads", "skip"
    )

    fun isStorePackage(pkg: String): Boolean = pkg in storePackages

    fun isInstallTrigger(text: String): Boolean {
        val normalized = text.trim()
        if (normalized.isEmpty()) return false
        if (benignTexts.any { it.equals(normalized, ignoreCase = true) }) return false
        return installKeywords.any { keyword -> normalized.contains(keyword, ignoreCase = true) }
    }
}
