package com.guradian.agent

/** 판별 결과 하나. */
data class Verdict(
    val isAd: Boolean,
    val confidence: Float,
    val reason: String
)

/**
 * Agent2 — 카드 텍스트를 받아 광고인지 판별한다.
 *
 * 이 인터페이스가 교체점이다. 판별기를 바꿀 때 구현체 하나만 갈아끼우면
 * 파이프라인·캐시·오버레이는 손대지 않는다.
 *
 * 프로덕션 배포 시에는 구현체가 API 키를 앱에 두면 안 된다. APK는 누구나 뜯을
 * 수 있어 키가 그대로 노출된다. 반드시 우리 서버를 거치는 구현체로 교체할 것.
 */
interface AdClassifier {

    /** 판정의 출처. 저장된 판정이 어디서 나왔는지 나중에 추적하는 데 쓴다. */
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
