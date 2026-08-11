package com.senioradguard.guard

/**
 * 눌린 버튼 문구가 앱 설치로 이어질 위험이 있는지 판정한다.
 *
 * 안드로이드 의존이 없는 순수 함수로 분리해 단위 테스트로 덮는다
 * (AdLabelRules와 같은 이유 — 오탐 여부가 사용자 경험을 직접 좌우한다).
 */
object InstallTriggerRules {

    private val adKeywords = setOf(
        "설치하기", "지금 설치", "무료 다운로드", "앱 다운로드",
        "install now", "free download", "get app",
        "광고", "이벤트 참여", "지금 받기", "혜택 받기"
    )

    /**
     * 위 키워드를 품고 있지만 설치와 무관한 버튼들.
     *
     * "광고"가 키워드에 있어서 유튜브의 "광고 건너뛰기"를 누를 때마다 차단 경고가
     * 뜨는 문제가 생긴다. 광고를 피하려는 행동을 앱이 가로막는 셈이라 정반대다.
     * 광고 자체를 알리는 일은 Layer 1 테두리가 이미 하고 있으므로, 여기서는
     * 설치로 이어지지 않는 문구를 먼저 걸러낸다.
     */
    private val benignTexts = setOf(
        "광고 건너뛰기", "건너뛰기", "광고 신고", "광고 정보", "광고 숨기기",
        "이 광고가 표시된 이유", "skip ad", "skip ads", "skip"
    )

    fun isInstallTrigger(text: String): Boolean {
        val normalized = text.trim()
        if (normalized.isEmpty()) return false
        if (benignTexts.any { it.equals(normalized, ignoreCase = true) }) return false
        return adKeywords.any { keyword -> normalized.contains(keyword, ignoreCase = true) }
    }
}
