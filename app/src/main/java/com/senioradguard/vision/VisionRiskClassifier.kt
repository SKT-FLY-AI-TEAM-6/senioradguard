package com.senioradguard.vision

import com.senioradguard.risk.RiskCategory
import com.senioradguard.risk.RiskLevel
import com.senioradguard.risk.RiskVerdict
import com.senioradguard.url.RiskAggregator
import com.senioradguard.url.UrlParser
import com.senioradguard.url.UrlSignals

/**
 * 판별기에 넘길 한 건.
 *
 * @param jpegBase64 **잘라낸 영역만** 담은 이미지. 화면 전체를 넣으면 안 된다
 * @param shownText  접근성 트리에서 읽은 글자. 없을 수 있다(이미지 전용 배너)
 * @param shownUrl   화면·extras에서 얻은 주소. 없을 수 있다(네이티브 앱 광고)
 */
data class VisionRequest(
    val kind: RoiKind,
    val sourceKey: String,
    val shownText: String,
    val shownUrl: String,
    val jpegBase64: String
)

/**
 * Agent6 — 잘라낸 화면 조각을 보고 위험도를 매긴다.
 *
 * 이 인터페이스가 교체점이다. 서버를 거치는 구현으로 바꿀 때 이것 하나만 갈아
 * 끼우면 캡처·캐시·테두리는 손대지 않는다.
 *
 * 앞선 판별기들과 결정적으로 다른 점: **접근성 트리에 아무것도 없어도 동작한다.**
 * 유튜브에서 광고를 19번 표시하는 동안 URL은 0건이었다(실기기 확인). 그 구간을
 * 메울 수 있는 것은 픽셀뿐이다.
 */
interface VisionRiskClassifier {

    /** 판정의 출처. RoiRisk.source에 그대로 저장돼 원인 추적에 쓰인다. */
    val source: String

    /** 실패·타임아웃이면 null. 호출부는 캐시에 저장하지 않는다. */
    suspend fun classify(request: VisionRequest): RiskVerdict?
}

/**
 * 이미지를 못 보는 대역. API 키가 없을 때 들어간다.
 *
 * **이건 비전이 아니다.** 화면에서 읽어낸 글자와 주소만 본다. 그래서 이 구현이
 * 커버하지 못하는 것이 정확히 이 레이어를 만든 이유다 — 글자가 없는 이미지 배너는
 * 여기서 항상 '판단 보류'가 된다. 키 없는 팀원도 캡처 → 지문 → 캐시 → 테두리 색까지
 * 전 구간을 실기기에서 돌려보게 하는 것이 목적이다.
 *
 * 판정이 결정적이라 같은 입력에 항상 같은 결과가 나온다 — 캐시 검증에 그 성질이 필요하다.
 */
class TextOnlyVisionClassifier : VisionRiskClassifier {

    override val source = RiskVerdict.SOURCE_HEURISTIC

    private companion object {
        /** 그림 속 글자 없이도 문구만으로 잡히는 것들. 가중치는 거친 근사치다. */
        val ADULT_TERMS = setOf("성인", "19금", "야동", "은밀한", "만남", "채팅앱", "미팅")
        val GAMBLING_TERMS = setOf("토토", "카지노", "바카라", "슬롯", "먹튀", "꽁머니", "배팅")
        val PIRACY_TERMS = setOf("다시보기", "무료보기", "전편", "토렌트", "링크모음", "무료영화")
        val EXAGGERATION_TERMS = setOf(
            "운동 없이", "먹기만 하면", "한 달 만에", "단 하루", "즉시 효과",
            "100% 보장", "부작용 없이", "기적", "완치"
        )
    }

    override suspend fun classify(request: VisionRequest): RiskVerdict {
        // 주소가 읽혔으면 Layer 4의 규칙을 그대로 재사용한다. 같은 판단을 두 벌
        // 유지하면 반드시 어긋난다.
        UrlParser.parse(request.shownUrl, request.sourceKey, request.shownText)?.let { link ->
            val fromUrl = RiskAggregator.heuristic(UrlSignals.of(link))
            if (fromUrl.score > 0) return fromUrl
        }

        val text = request.shownText.lowercase()
        val hit: Pair<RiskCategory, Int>? = when {
            GAMBLING_TERMS.any { it in text } -> RiskCategory.ILLEGAL_GAMBLING to 80
            ADULT_TERMS.any { it in text } -> RiskCategory.ADULT_CONTENT to 75
            PIRACY_TERMS.any { it in text } -> RiskCategory.ILLEGAL_STREAMING_OR_COPYRIGHT to 70
            EXAGGERATION_TERMS.any { it in text } -> RiskCategory.EXAGGERATED_CLAIM to 50
            else -> null
        }

        if (hit == null) {
            return RiskVerdict(
                category = RiskCategory.UNKNOWN,
                level = RiskLevel.LOW,
                score = 0,
                // 여기가 이 대역의 한계다. "안전하다"가 아니라 "볼 수단이 없다"이므로
                // 근거 문구도 그렇게 적는다.
                reasons = listOf("글자가 없어 규칙으로는 판단할 수 없습니다"),
                source = source
            )
        }

        val (category, score) = hit
        return RiskVerdict(
            category = category,
            level = RiskLevel.of(score),
            score = score,
            reasons = listOf("화면 문구에서 ${category.label} 표현이 보입니다"),
            source = source
        )
    }
}
