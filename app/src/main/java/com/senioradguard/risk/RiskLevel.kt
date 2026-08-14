package com.senioradguard.risk

/**
 * 위험도 3단계. 어르신에게 보여줄 문구와 테두리 색이 함께 붙는다 —
 * "HIGH"는 화면에 그대로 띄울 수 없는 말이고, 색은 글자를 못 읽는 사람에게도 전달된다.
 *
 * 색은 신호등을 그대로 따른다. 배우지 않아도 아는 유일한 색 규약이다.
 */
enum class RiskLevel(val label: String, val minScore: Int, val color: Int) {
    LOW("안전", 0, 0xFF43A047.toInt()),        // 초록
    MEDIUM("주의", 40, 0xFFFB8C00.toInt()),    // 주황
    HIGH("위험", 70, 0xFFE53935.toInt());      // 빨강

    companion object {
        /** 점수 → 등급. 경계값은 위험도 판정 전체의 유일한 기준이다. */
        fun of(score: Int): RiskLevel = when {
            score >= HIGH.minScore -> HIGH
            score >= MEDIUM.minScore -> MEDIUM
            else -> LOW
        }
    }
}

/**
 * 판정 대상의 성격. 점수만으로는 "왜 위험한가"가 안 남아 보호자도 우리도 원인을 못 찾는다.
 *
 * 판별기가 이 이름 중 하나를 고르도록 강제한다. 모르는 이름이 오면 [UNKNOWN]으로
 * 떨어뜨린다 — 자유 문자열을 그대로 받으면 집계가 불가능해진다.
 */
enum class RiskCategory(val label: String) {
    /** 불법 다시보기·토렌트·웹툰 불법유통 */
    ILLEGAL_STREAMING_OR_COPYRIGHT("불법 복제·다시보기"),

    /** 사설 도박·토토·카지노 */
    ILLEGAL_GAMBLING("불법 도박"),

    /** 성인물·선정적 광고 */
    ADULT_CONTENT("성인·선정적 내용"),

    /** 피싱·사칭·가짜 당첨 */
    PHISHING_OR_SCAM("사기·피싱 의심"),

    /** APK 직접 배포, 악성 앱 설치 유도 */
    MALWARE_OR_UNWANTED_APP("악성 앱 설치 유도"),

    /**
     * 효과를 부풀린 광고. 불법은 아니지만 어르신이 가장 많이 속는 유형이다 —
     * "운동 없이 한 달 10kg", "이것만 먹으면 무릎 통증 끝".
     */
    EXAGGERATED_CLAIM("과장 광고"),

    /** 정상 서비스로 보이나 확인되지 않은 제3자 */
    UNVERIFIED_THIRD_PARTY("확인되지 않은 곳"),

    /** 언론사·플랫폼이 쓰는 공식 광고 서버 — 광고지만 위험하지는 않다 */
    OFFICIAL_AD_TRACKER("정상 광고 서버"),

    /** 널리 알려진 사업자 */
    TRUSTED_KNOWN_BRAND("알려진 사업자"),

    UNKNOWN("판단 보류");

    companion object {
        fun parse(raw: String?): RiskCategory =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

/**
 * 판정 하나. URL을 봤든 이미지를 봤든 결과 형태는 같다.
 *
 * @param score   0~100. 등급의 근거이자 정렬 기준
 * @param reasons 사람이 읽는 근거. 보호자 화면과 경고창에 그대로 나가므로 짧은 문장이어야 한다
 * @param source  판정의 출처 — BLACKLIST / VISION / LLM / HEURISTIC. 원인 추적에 쓴다
 * @param brand   판별기가 알아본 상표. 없으면 빈 문자열
 */
data class RiskVerdict(
    val category: RiskCategory,
    val level: RiskLevel,
    val score: Int,
    val reasons: List<String>,
    val source: String,
    val brand: String = ""
) {
    companion object {
        /** 근거를 몇 개까지 보여줄지. 경고창이 길어지면 어르신이 읽지 않는다. */
        const val MAX_REASONS = 4

        const val SOURCE_BLACKLIST = "BLACKLIST"
        const val SOURCE_VISION = "VISION"
        const val SOURCE_LLM = "LLM"
        const val SOURCE_HEURISTIC = "HEURISTIC"
    }
}
