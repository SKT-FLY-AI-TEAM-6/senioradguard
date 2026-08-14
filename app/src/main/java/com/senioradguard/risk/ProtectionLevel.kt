package com.senioradguard.risk

/**
 * 사용자가 설정에서 고르는 보호 수준. 중위험 광고를 어디까지 간섭할지만
 * 다르다 — 저위험은 항상 표시 후 허용, 고위험은 항상 차단이다.
 */
enum class ProtectionLevel {
    /** 안내형 — 중위험은 이유를 보여주고 사용자가 선택 */
    GUIDE,

    /** 균형형 — 안내형 + 투터치 확인. 기본값 */
    BALANCED,

    /** 강력 보호형 — 중위험도 기본 차단 */
    STRICT;

    companion object {
        /** 편의성과 안전을 함께 고려한 기본 설정 */
        val DEFAULT = BALANCED
    }
}
