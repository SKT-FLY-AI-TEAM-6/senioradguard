package com.guradian.serp

/**
 * 검색 결과 위험도 판별기. **한 화면의 결과를 한 번에 받는다.**
 *
 * 결과당 한 번씩 부르지 않는 것이 이 인터페이스의 핵심이다. 검색 한 화면에는
 * 결과가 6~10개 있고, 하나씩 부르면 검색 한 번에 호출이 그만큼 나간다. 배치로
 * 묶으면 **화면당 1회**로 끝나고, 게다가 모델이 결과들을 **서로 비교해서** 볼 수
 * 있다 — "이 목록에서 tving.com이 정상이고 tvhot2.com이 그 흉내"라는 판단은
 * 하나씩 볼 때보다 함께 볼 때 잘 나온다.
 *
 * 이 인터페이스가 교체점이다. 지금은 Gemini 직접 호출이고, 배포 때는 우리 서버를
 * 거치는 구현으로 갈아끼운다 — 파이프라인·캐시·배지는 손대지 않는다.
 */
interface SerpClassifier {

    /** 판정의 출처. [SerpVerdict.source]에 그대로 들어가 원인 추적에 쓰인다. */
    val source: String

    /**
     * @param query   사용자가 친 검색어. "다시보기"를 검색한 맥락에서 "무료 시청"은
     *                훨씬 강한 신호다 — 문맥이 없으면 모델이 이 차이를 못 본다
     * @param items   판별할 결과들. 규칙으로 결론이 난 것은 이미 빠져 있다
     * @return 입력 순서와 무관하게 [Request.host]로 찾을 수 있는 판정 맵.
     *         실패·타임아웃이면 빈 맵 — 호출부는 캐시에 저장하지 않는다
     */
    suspend fun classify(query: String, items: List<Request>): Map<String, SerpVerdict>

    /**
     * 판별 요청 한 건. **화면에 보이는 것만 담는다.**
     *
     * 페이지에 접속해서 내용을 가져오지 않는다. 접속을 막아 둔 도메인이 절반이고,
     * 어르신 회선으로 불법 사이트에 요청을 보내는 것 자체가 위험을 만든다.
     */
    data class Request(
        val host: String,
        val title: String,
        val snippet: String,
        /** 규칙이 먼저 찾아낸 근거. 프롬프트에 함께 넣으면 판단이 눈에 띄게 안정된다 */
        val signals: List<Signal>
    )
}

/**
 * 판별기 없이 규칙만 쓰는 대역.
 *
 * **이건 추론이 아니다.** 이름에 이상한 조각이 있는지 보는 문자열 검사일 뿐이라
 * 처음 보는 사이트를 문맥으로 판단하지 못한다. 키가 없거나 AI를 꺼둔 사용자도
 * 전 구간(추출 → 판정 → 배지)이 돌게 하고, 상한에 걸렸을 때 기능이 멈추는 대신
 * 조용히 얕아지게 하는 것이 목적이다.
 *
 * 같은 입력에 항상 같은 결과가 나온다 — 캐시 동작을 검증할 때 이 성질이 필요하다.
 */
object RuleOnlyClassifier : SerpClassifier {

    override val source = SerpVerdict.SOURCE_RULE

    override suspend fun classify(
        query: String,
        items: List<SerpClassifier.Request>
    ): Map<String, SerpVerdict> =
        items.associate { it.host to RiskAggregator.ruleVerdict(it.signals) }
}
