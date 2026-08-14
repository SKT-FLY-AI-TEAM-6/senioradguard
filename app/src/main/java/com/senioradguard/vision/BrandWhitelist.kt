package com.senioradguard.vision

import com.senioradguard.risk.RiskCategory
import com.senioradguard.risk.RiskLevel
import com.senioradguard.risk.RiskVerdict

/**
 * 이름을 아는 사업자 목록. 판별기가 광고에서 읽어낸 상표를 여기에 맞춰 본다.
 *
 * ## 화이트리스트로 쓰되, 만능 면죄부는 아니다
 * 알려진 사업자의 광고는 위험하지 않다. 하지만 **사칭이 정확히 그 이름을 쓴다.**
 * 그래서 상표가 목록에 있어도 판별기가 사칭·불법·성인으로 본 건은 낮추지 않는다
 * ([relax] 참고). 낮추는 것은 "누구인지 알겠고 딱히 이상한 점도 없다"일 때뿐이다.
 *
 * 판별기가 읽는 것은 그림 속 글자·로고라 표기가 흔들린다("삼성전자", "SAMSUNG",
 * "samsung electronics"). 정규화해서 비교하고, 목록에는 한글·영문을 함께 담는다.
 */
object BrandWhitelist {

    /**
     * 상표가 확인됐고 위험 신호가 없을 때 눌러줄 점수 상한.
     * '안전'(초록) 구간 안쪽이라 테두리가 초록으로 나간다.
     */
    const val TRUSTED_SCORE_CAP = 20

    /**
     * 상표 확인만으로 위험도를 낮춰도 되는 성격들.
     *
     * 여기 없는 성격(불법·도박·성인·사칭·악성앱·과장)은 상표를 알아봤다는 사실이
     * 오히려 사칭의 근거일 수 있으므로 손대지 않는다.
     */
    private val RELAXABLE = setOf(
        RiskCategory.TRUSTED_KNOWN_BRAND,
        RiskCategory.OFFICIAL_AD_TRACKER,
        RiskCategory.UNVERIFIED_THIRD_PARTY,
        RiskCategory.UNKNOWN
    )

    private val BRANDS = setOf(
        // 통신·제조
        "삼성", "삼성전자", "samsung", "lg", "엘지", "엘지전자", "현대", "hyundai",
        "기아", "kia", "sk", "skt", "sk텔레콤", "sktelecom", "kt", "lgu", "lg유플러스",
        "uplus", "애플", "apple", "구글", "google", "마이크로소프트", "microsoft",
        // 포털·플랫폼
        "네이버", "naver", "카카오", "kakao", "다음", "daum", "토스", "toss",
        "당근", "당근마켓", "배달의민족", "배민", "야놀자", "여기어때",
        // 유통
        "쿠팡", "coupang", "11번가", "지마켓", "gmarket", "옥션", "auction",
        "롯데", "lotte", "신세계", "이마트", "emart", "홈플러스", "homeplus",
        "ssg", "무신사", "올리브영", "cj", "cj온스타일", "gs", "gs샵",
        // 금융
        "kb", "국민은행", "kb국민은행", "신한", "신한은행", "하나", "하나은행",
        "우리", "우리은행", "농협", "nh", "nh농협", "기업은행", "ibk",
        "삼성카드", "현대카드", "신한카드", "kb카드", "카카오뱅크", "케이뱅크",
        // 미디어·OTT (검색 결과에서 공식 서비스를 알아보는 데 쓴다)
        "넷플릭스", "netflix", "디즈니", "disney", "디즈니플러스", "티빙", "tving",
        "웨이브", "wavve", "왓챠", "watcha", "쿠팡플레이", "유튜브", "youtube",
        "kbs", "mbc", "sbs", "jtbc", "tvn", "ocn",
        // 공공
        "정부24", "국민건강보험", "건강보험공단", "국세청", "홈택스", "우체국",
        "질병관리청", "경찰청", "행정안전부"
    )

    /** 표기 흔들림을 걷어낸다 — 공백·기호·대소문자·"(주)" 따위. */
    fun normalize(brand: String): String =
        brand.lowercase()
            .replace(Regex("""\(주\)|주식회사|㈜"""), "")
            .replace(Regex("""[\s\-_.,'"·]"""), "")
            .trim()

    fun isTrusted(brand: String): Boolean {
        val normalized = normalize(brand)
        if (normalized.isEmpty()) return false
        return normalized in BRANDS
    }

    /**
     * 상표가 확인됐고 성격이 무해한 쪽이면 위험도를 '안전'으로 눌러 준다.
     *
     * 그렇지 않으면 판정을 그대로 돌려준다. 이 함수가 값을 바꿨다는 것은
     * "누구 광고인지 알겠다"는 뜻이므로 근거에도 그 사실을 남긴다.
     */
    fun relax(verdict: RiskVerdict): RiskVerdict {
        if (!isTrusted(verdict.brand)) return verdict
        if (verdict.category !in RELAXABLE) return verdict
        if (verdict.score <= TRUSTED_SCORE_CAP) return verdict

        return verdict.copy(
            score = TRUSTED_SCORE_CAP,
            level = RiskLevel.of(TRUSTED_SCORE_CAP),
            category = RiskCategory.TRUSTED_KNOWN_BRAND,
            reasons = (listOf("알려진 사업자(${verdict.brand}) 광고입니다") + verdict.reasons)
                .distinct()
                .take(RiskVerdict.MAX_REASONS)
        )
    }
}
