package com.senioradguard.risk

/**
 * 광고 하나에 대한 위험 분석의 결과.
 *
 * 등급이 나온 [Assessed]와 분석하지 못한 [Unverified]를 타입으로 가른다.
 * 원칙: **분석하지 못한 화면을 저위험으로 처리하지 않는다.** when 분기가
 * 두 경우를 모두 다루도록 컴파일러가 강제하므로, "URL을 못 얻었으니
 * 일단 허용" 같은 경로는 실수로 만들 수 없다.
 */
sealed class RiskAssessment {

    /**
     * 분석이 끝나 등급이 나온 상태.
     *
     * @param reason 사용자에게 그대로 보여줄 판단 이유. 어르신이 읽는
     *        문장이므로 짧고 쉬운 말로 만들 것 ("주소가 광고에 나온
     *        회사와 달라요" 수준).
     */
    data class Assessed(
        val level: RiskLevel,
        val reason: String
    ) : RiskAssessment()

    /**
     * 목적 URL을 확보하지 못했거나 분석이 불충분한 상태.
     *
     * 캐시([com.senioradguard.detector.db.UrlVerdict])에 저장하지 않는다 —
     * 실패를 저장하면 다음 기회에 분석할 수 있는 URL이 영영 미분석으로 남는다.
     *
     * @param limitation 무엇을 못 했는지. 사용자에게는 분석 한계와 함께
     *        이전 화면 복귀를 권고하는 문구로 보여준다.
     */
    data class Unverified(
        val limitation: String
    ) : RiskAssessment()
}
