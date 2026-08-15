package com.guradian.serp

/**
 * 검색 결과 한 칸에 붙는 위험도. 상 · 중 · 하 세 단계다.
 *
 * 화면에는 [label]("위험"/"주의"/"안전")을 쓰고 [grade]("상"/"중"/"하")는 로그와
 * 팀 문서에서 쓴다. 어르신에게 "상"이라고만 보여주면 무엇이 상인지 알 수 없다 —
 * 등급 이름과 화면 문구를 분리해 둔 이유다.
 *
 * 색은 신호등을 그대로 따른다. 배우지 않아도 아는 유일한 색 규약이라, 글자를
 * 읽기 어려운 사용자에게도 전달된다.
 *
 * 경계값(40 / 70)은 senioradguard V3에서 검증된 값을 그대로 가져왔다. 프롬프트도
 * 이 경계를 전제로 쓰여 있으므로(“70 이상이면 빨간 표시가 붙는다”) 한쪽만 바꾸면
 * 판정 분포가 통째로 어긋난다.
 */
enum class RiskGrade(val grade: String, val label: String, val minScore: Int, val color: Int) {
    /**
     * 아직 아무도 판단하지 않았다. **'안전'이 아니다.**
     *
     * 이 값이 있어야 하는 이유가 이 기능의 전제와 맞닿아 있다. 우리 규칙 목록은
     * 작고 앞으로도 완결될 수 없다 — 불법 사이트는 차단되면 숫자만 바꿔 되살아난다.
     * 그런 상태에서 "걸린 규칙이 없다"를 '안전'으로 옮기면, **모르는 사이트 전부에
     * 초록 도장을 찍는 셈**이 된다. 어르신이 초록을 믿고 누르는 순간 그 초록은
     * 아무 근거도 없던 것이 된다.
     *
     * 그래서 초록은 근거가 있을 때만 붙는다 — 알려진 곳 목록에 있거나, 판별기가
     * 실제로 보고 안전하다고 했을 때. 그 외에는 아무 표시도 하지 않는다.
     */
    UNKNOWN("?", "확인 안 됨", -1, 0x00000000),

    LOW("하", "안전", 0, 0xFF43A047.toInt()),      // 초록
    MEDIUM("중", "주의", 40, 0xFFFB8C00.toInt()),  // 주황
    HIGH("상", "위험", 70, 0xFFE53935.toInt());    // 빨강

    /** 화면에 무언가 그릴 등급인가. [UNKNOWN]은 그리지 않는다. */
    val isShown: Boolean get() = this != UNKNOWN

    companion object {
        /**
         * 점수 → 등급. **근거가 있는 점수에만 쓴다** — 판별기 판정이나 신호가 만든
         * 점수. "신호가 없어서 0점"에는 쓰면 안 된다 (그건 [UNKNOWN]이다).
         */
        fun of(score: Int): RiskGrade = when {
            score >= HIGH.minScore -> HIGH
            score >= MEDIUM.minScore -> MEDIUM
            else -> LOW
        }
    }
}

/**
 * 왜 위험한가. 점수만 남기면 나중에 원인을 못 찾는다.
 *
 * 판별기가 이 이름 중 하나를 고르도록 스키마로 강제한다. 모르는 이름이 오면
 * [UNKNOWN]으로 떨어뜨린다 — 자유 문자열을 그대로 받으면 집계가 불가능해진다.
 */
enum class RiskCategory(val label: String) {
    /** 불법 다시보기·토렌트·웹툰 불법유통. 이 기능이 노리는 1순위다 */
    ILLEGAL_STREAMING_OR_COPYRIGHT("불법 다시보기"),

    /** 사설 도박·토토·카지노 */
    ILLEGAL_GAMBLING("불법 도박"),

    /** 성인물 */
    ADULT_CONTENT("성인 사이트"),

    /** 피싱·사칭·가짜 당첨 */
    PHISHING_OR_SCAM("사기·피싱 의심"),

    /** APK 직접 배포, 악성 앱 설치 유도 */
    MALWARE_OR_UNWANTED_APP("악성 앱 설치 유도"),

    /** 효과를 부풀린 광고성 페이지 */
    EXAGGERATED_CLAIM("과장 광고"),

    /** 정상으로 보이나 확인되지 않은 곳 */
    UNVERIFIED_THIRD_PARTY("확인되지 않은 곳"),

    /** 널리 알려진 사업자·공식 서비스 */
    TRUSTED_KNOWN_BRAND("알려진 곳"),

    UNKNOWN("판단 보류");

    companion object {
        fun parse(raw: String?): RiskCategory =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

/**
 * 결과 한 칸의 판정.
 *
 * @param score  0~100. 등급의 근거
 * @param reason 어르신이 읽을 한 줄. 배지 아래에 그대로 나가므로 짧아야 한다
 * @param source 어디서 나온 판정인가 — 원인 추적용. [SOURCE_RULE] / [SOURCE_LLM] / [SOURCE_CACHE]
 */
data class SerpVerdict(
    val category: RiskCategory,
    val grade: RiskGrade,
    val score: Int,
    val reason: String,
    val source: String
) {
    companion object {
        const val SOURCE_RULE = "RULE"
        const val SOURCE_LLM = "LLM"
        const val SOURCE_CACHE = "CACHE"

        /** 배지에 넣을 근거 길이 상한. 길면 어르신이 읽지 않는다. */
        const val MAX_REASON_CHARS = 40

        fun of(category: RiskCategory, score: Int, reason: String, source: String): SerpVerdict {
            val bounded = score.coerceIn(0, 100)
            return SerpVerdict(
                category = category,
                grade = RiskGrade.of(bounded),
                score = bounded,
                reason = reason.trim().take(MAX_REASON_CHARS),
                source = source
            )
        }

        /**
         * 아직 판단하지 못했다. 점수를 0으로 두지만 **등급은 [RiskGrade.UNKNOWN]**이라
         * 화면에는 아무것도 그리지 않는다. 0점을 '안전'으로 흘려보내지 않으려고
         * 이 생성자를 따로 둔다.
         */
        fun unknown(source: String) = SerpVerdict(
            category = RiskCategory.UNKNOWN,
            grade = RiskGrade.UNKNOWN,
            score = 0,
            reason = "",
            source = source
        )
    }
}
