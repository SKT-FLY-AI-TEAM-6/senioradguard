package com.senioradguard.risk

/**
 * 위험 평가에 대해 앱이 취할 행동. 오버레이·차단 로직은 이 값만 보고
 * 움직인다 — 분류(무엇이 위험한가)와 대응(그래서 어떻게 하는가)을 분리해,
 * 분류기(규칙·온디바이스 LLM)를 바꿔도 대응 정책은 그대로 남는다.
 */
enum class UserAction {
    /** 테두리 + 배지 표시 후 허용. 터치 통과 (FLAG_NOT_TOUCHABLE 유지) */
    LABEL_AND_ALLOW,

    /** 판단 이유를 보여주고 진행/복귀를 사용자가 선택 */
    WARN_WITH_CHOICE,

    /** WARN_WITH_CHOICE + 진행하려면 확인을 한 번 더 (투터치) */
    WARN_TWO_TOUCH,

    /**
     * 기본 차단. 진행 경로는 없지만 보호자가 화이트리스트로 풀 수 있다.
     * 강력 보호형에서 중위험에만 나온다 — 사용자가 명시적으로 선택한
     * 설정이라는 점이 심사 소명의 근거다.
     */
    BLOCK_DEFAULT,

    /**
     * 자동 차단, 우회 없음. `계속 진행`을 제공하지 않는다.
     * 클릭 전이면 광고를 비활성화하고, 이미 진입했으면 자동 복귀
     * 또는 `안전하게 돌아가기`를 제공한다.
     */
    BLOCK_ALWAYS,

    /** 분석 한계를 알리고 이전 화면 복귀를 권고. 허용 표시가 아니다 */
    SHOW_LIMITATION
}

/**
 * (보호 수준 × 위험 평가) → 사용자 대응 매트릭스. 기획서 3.2절의 표를
 * 코드로 옮긴 것이며, 순수 함수라 이 표가 곧 명세다.
 *
 * | | 저위험 | 중위험 | 고위험 |
 * |---|---|---|---|
 * | 안내형 | 표시 후 허용 | 이유 + 선택 | 자동 차단 |
 * | 균형형 | 표시 후 허용 | 이유 + 투터치 | 자동 차단 |
 * | 강력 보호형 | 표시 후 허용 | 기본 차단 | 자동 차단 |
 */
object RiskPolicy {

    fun actionFor(protection: ProtectionLevel, assessment: RiskAssessment): UserAction =
        when (assessment) {
            is RiskAssessment.Unverified -> UserAction.SHOW_LIMITATION
            is RiskAssessment.Assessed -> when (assessment.level) {
                RiskLevel.LOW -> UserAction.LABEL_AND_ALLOW
                RiskLevel.MEDIUM -> when (protection) {
                    ProtectionLevel.GUIDE -> UserAction.WARN_WITH_CHOICE
                    ProtectionLevel.BALANCED -> UserAction.WARN_TWO_TOUCH
                    ProtectionLevel.STRICT -> UserAction.BLOCK_DEFAULT
                }
                RiskLevel.HIGH -> UserAction.BLOCK_ALWAYS
            }
        }
}
