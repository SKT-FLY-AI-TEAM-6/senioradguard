package com.senioradguard.risk

/**
 * 위험 등급. 무엇을 감지했는지가 아니라 **사용자에게 어떻게 개입할지**를 정한다.
 *
 * 등급을 나누는 이유는 개입 강도가 다르기 때문이다. 라벨이 붙은 광고는 알려주기만
 * 하면 되지만, 확신이 높은 판별이나 차단 도메인은 진행을 막아야 한다. 하나로
 * 뭉뚱그리면 둘 중 하나가 반드시 틀린 강도로 처리된다.
 */
enum class RiskLevel {
    /** 표시만 한다. 화면에 "광고"라고 적혀 있는 것 — 확실하지만 위험하지는 않다. */
    LOW,

    /** 사용자에게 물어본다. 광고 같지만 확신이 부족한 것. */
    MEDIUM,

    /** 즉시 막는다. 확신이 높은 판별이거나 차단 목록에 있는 도메인. */
    HIGH;

    val wire: String get() = name.lowercase()

    companion object {
        /** Gemini 판정을 등급으로 옮긴다. */
        fun ofConfidence(confidence: Float): RiskLevel =
            if (confidence >= HIGH_CONFIDENCE) HIGH else MEDIUM

        /**
         * 이 이상이면 사용자에게 묻지 않고 막는다.
         *
         * 표시 임계값(0.6)보다 높게 잡는다. 0.6은 "테두리를 그려도 될 만큼"이고
         * 여기는 "사용자를 멈춰 세워도 될 만큼"이라 요구 수준이 다르다.
         */
        const val HIGH_CONFIDENCE = 0.8f
    }
}

/**
 * 보호 강도. 보호자가 정하고 어르신 기기에 반영된다.
 *
 * 숫자가 클수록 더 많이 개입한다. 기본값을 2로 두는 이유는, 1은 라벨 없는 광고를
 * 통째로 놓치고 3은 차단 목록 오탐까지 떠안기 때문이다.
 */
enum class ProtectionLevel(val value: Int) {
    /** Layer 1만 — 화면에 "광고"라고 적힌 것만 표시 */
    LABELS_ONLY(1),

    /** Layer 1 + 2 — LLM 판별까지 */
    WITH_AI(2),

    /** Layer 1 + 2 + URL 차단 목록 */
    WITH_URL_BLOCK(3);

    val usesAi: Boolean get() = value >= WITH_AI.value
    val usesUrlBlock: Boolean get() = value >= WITH_URL_BLOCK.value

    companion object {
        val DEFAULT = WITH_AI

        fun of(value: Int?): ProtectionLevel =
            entries.firstOrNull { it.value == value } ?: DEFAULT
    }
}
