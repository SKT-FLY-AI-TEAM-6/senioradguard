package com.guradian.agent

/**
 * 판별기 자리에 임시로 들어가는 규칙 기반 대역.
 *
 * **이건 진짜 판별기가 아니다.** 키 없이 파이프라인(후보 추출 → 캐시 → 판별 →
 * 교차검증 → 저장 → 점선 표시) 전체를 실기기에서 돌려보기 위한 대역이다.
 * 광고 문구를 그럴듯하게 흉내 낼 뿐 문맥을 이해하지 못하므로, 이 구현으로 나온
 * 판정 품질을 LLM의 품질로 오해하면 안 된다.
 *
 * ⚠️ 원본에서 지마켓 "슈퍼딜"(그 쇼핑몰 자체 상품 영역)을 광고로 오탐한 전례가 있다.
 * 버튼을 눌러 부르는 구조에서는 사용자가 결과를 기다리므로 오탐이 그대로 평가받는다.
 *
 * 판정이 결정적(deterministic)이라 같은 입력에 항상 같은 결과가 나온다 —
 * 캐시 동작을 검증할 때 이 성질이 필요하다.
 */
class StubClassifier : AdClassifier {

    override val source = "STUB"

    /** 상업적 유인 문구 → 가중치. 판별기가 볼 문맥 대신 쓰는 거친 근사치다. */
    private val adSignals = mapOf(
        "설치하기" to 0.9f, "지금 설치" to 0.9f, "다운로드" to 0.8f,
        "무료" to 0.6f, "할인" to 0.7f, "특가" to 0.8f, "최저가" to 0.8f,
        "이벤트" to 0.6f, "혜택" to 0.6f, "쿠폰" to 0.7f,
        "구매하기" to 0.8f, "주문" to 0.6f, "배송" to 0.5f,
        "지금 신청" to 0.8f, "무료체험" to 0.85f, "가입하고" to 0.7f,
        "클릭" to 0.6f, "바로가기" to 0.5f,
        "sponsored" to 0.95f, "sale" to 0.7f, "buy now" to 0.9f
    )

    /** 광고가 아닐 가능성을 높이는 문구 — 기사·게시물에 흔한 표현. */
    private val editorialSignals = listOf(
        "기자", "취재", "보도", "인터뷰", "칼럼", "사설", "속보", "댓글"
    )

    /** 출처는 쓰지 않는다 — 문맥을 읽을 수 없는 게 이 구현의 본질적 한계다. */
    override suspend fun classify(text: String, sourceKey: String): Verdict {
        val lower = text.lowercase()

        val matched = adSignals.filterKeys { lower.contains(it) }
        val editorial = editorialSignals.count { lower.contains(it) }

        if (matched.isEmpty()) {
            return Verdict(
                isAd = false,
                confidence = 0.8f,
                reason = "상업적 유인 문구 없음"
            )
        }

        // 가장 강한 신호를 바탕으로, 신호가 여러 개면 조금 올리고 기사 표현이
        // 섞여 있으면 내린다.
        val base = matched.values.max()
        val bonus = (matched.size - 1).coerceAtMost(3) * 0.05f
        val penalty = editorial.coerceAtMost(3) * 0.15f
        val confidence = (base + bonus - penalty).coerceIn(0f, 1f)

        return Verdict(
            isAd = confidence >= 0.5f,
            confidence = confidence,
            reason = "문구 매칭: ${matched.keys.joinToString(", ")}"
        )
    }
}
