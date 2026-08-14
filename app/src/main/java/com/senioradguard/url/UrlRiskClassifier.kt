package com.senioradguard.url

/**
 * Agent5 — 링크 하나의 위험도를 추론한다.
 *
 * 이 인터페이스가 교체점이다. 지금은 Gemini 구현이 기본이고 키가 없으면 규칙 기반
 * 대역([HeuristicUrlRiskClassifier])이 들어간다. 서버를 거치는 구현으로 바꿀 때
 * 구현체 하나만 갈아 끼우면 파이프라인·캐시·경고는 손대지 않는다.
 *
 * [com.senioradguard.agent.AdClassifier]와 나란한 자리다. 그쪽은 "이게 광고인가",
 * 이쪽은 "이 광고가 데려가는 곳이 위험한가"를 본다. 둘을 한 판별기로 합치지 않는
 * 이유는 캐시 단위가 다르기 때문이다 — 광고 판정은 카드 문구 단위로, 위험도는
 * 호스트 단위로 캐시해야 각각의 적중률이 나온다.
 */
interface UrlRiskClassifier {

    /** 판정의 출처. UrlRisk.source에 그대로 저장돼 나중에 원인 추적에 쓰인다. */
    val source: String

    /**
     * @param signals [UrlSignals]가 뽑은 근거. 프롬프트에 함께 넘긴다 — URL 문자열만
     *        던지면 판별기가 "잘 모르겠다"로 수렴한다
     * @return 실패·타임아웃이면 null. 호출부는 캐시에 저장하지 않고 신호만으로 판정한다
     */
    suspend fun classify(link: AdLink, signals: List<Signal>): UrlRiskVerdict?
}

/**
 * 판별기 없이 신호만으로 판정하는 대역.
 *
 * **이건 추론이 아니다.** 이름에 이상한 조각이 있는지 보는 문자열 검사일 뿐이라,
 * 처음 보는 사이트를 문맥으로 판단하지 못한다. 키가 없는 팀원도 Layer 4 전 구간
 * (링크 수집 → 블랙리스트 → 캐시 → 판정 → 경고)을 실기기에서 돌려보게 하고,
 * LLM이 상한에 걸렸을 때 기능이 멈추는 대신 조용히 저하되게 하는 것이 목적이다.
 *
 * 판정이 결정적이라 같은 입력에 항상 같은 결과가 나온다 — 캐시 동작을 검증할 때
 * 이 성질이 필요하다.
 */
class HeuristicUrlRiskClassifier : UrlRiskClassifier {

    override val source = UrlRiskVerdict.SOURCE_HEURISTIC

    override suspend fun classify(link: AdLink, signals: List<Signal>): UrlRiskVerdict =
        RiskAggregator.heuristic(signals)
}
