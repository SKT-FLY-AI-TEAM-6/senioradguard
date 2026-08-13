package com.senioradguard.agent

/** 판별 결과 하나. */
data class Verdict(
    val isAd: Boolean,
    val confidence: Float,
    val reason: String
)

/**
 * Agent2 — 카드 텍스트를 받아 광고인지 판별한다.
 *
 * 이 인터페이스가 교체점이다. 지금은 규칙 기반 StubClassifier가 들어가 있고,
 * LLM을 붙일 때 구현체 하나만 바꾸면 파이프라인·캐시·오버레이는 손대지 않는다.
 *
 * 프로덕션 배포 시에는 구현체가 API 키를 앱에 두면 안 된다. APK는 누구나 뜯을
 * 수 있어 키가 그대로 노출된다. 반드시 우리 서버를 거치는 구현체로 교체할 것.
 */
interface AdClassifier {

    /** 판정의 출처. AdVerdict.source에 그대로 저장돼 나중에 원인 추적에 쓰인다. */
    val source: String

    /**
     * @param sourceKey 카드가 나온 곳 (브라우저면 도메인, 앱이면 패키지명).
     *        판정을 가르는 결정적 신호다 — "70% 할인 무료배송"이 지마켓에서 나오면
     *        그 앱의 본래 기능이고 뉴스 사이트에서 나오면 끼어든 광고다. 스텁은
     *        문맥을 못 읽어 이 구분을 못 했고, 그게 지마켓 "슈퍼딜" 오탐의 원인이었다.
     *        기본값을 둬서 출처를 안 쓰는 구현체(StubClassifier)는 영향받지 않는다.
     *
     * @return 판별 결과. 실패·타임아웃이면 null — 호출부는 캐시에 저장하지 않고
     *         이번 화면은 표시하지 않는다 (모르면 아무 말도 하지 않는 쪽이 안전하다).
     */
    suspend fun classify(text: String, sourceKey: String = ""): Verdict?
}
