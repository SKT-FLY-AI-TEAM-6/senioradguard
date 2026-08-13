package com.guradian.region

/**
 * 공식 광고 표기 판정 규칙.
 *
 * 팀원 AdDetectService(com.flyai.adalert)에서 이식. 로직과 상수는 변경하지 않았다 —
 * 아래 처리는 모두 실제 앱에서 부딪혀 나온 대응이라 임의로 손대면 회귀한다.
 */
object AdLabelRules {

    /**
     * 모바일 웹은 라벨 없는 이미지 배너가 많은데, 크롬은 HTML의 id 속성을 노드의
     * viewIdResourceName으로 노출한다(서비스 설정의 flagReportViewIds 필요).
     * 광고 네트워크가 쓰는 id로 배너 컨테이너를 직접 찾는다.
     * 단 추천 위젯(Dable·Taboola 등)은 광고와 진짜 기사가 섞여 있어 컨테이너로 잡으면 안 되고,
     * 그 안의 개별 광고에 붙는 "AD" 라벨로만 잡는다.
     */
    private val adContainerIds = listOf(
        "div-gpt-ad", "adsbygoogle", "google_ads",   // 구글 (표준 광고 슬롯 id)
        "aceplanet", "mobondivbanner", "adfit", "clickads", "innorame", "criteo"
    )

    fun isAdContainer(id: String?): Boolean {
        val s = id?.lowercase() ?: return false
        return adContainerIds.any { it in s }
    }

    /**
     * 광고 카드 전체가 한 노드로 합쳐져 설명 문구 안에 라벨이 섞이는 경우(유튜브 Litho)가 있으므로,
     * 구분자(·, 쉼표, " - ")로 쪼갠 토큰이 정확히 광고 표기일 때만 인정한다. (제목 속 단어는 오탐 안 됨)
     * " - "는 양옆 공백이 있을 때만 구분자로 취급해 단어 속 하이픈(non-sponsored 등)은 쪼개지 않는다.
     * 웹 광고는 문구 사이에 폭 0인 문자를 끼워 넣어 차단을 피하기도 해서 먼저 제거한다.
     */
    fun isAdLabel(s: String): Boolean =
        s.lowercase().replace(Regex("[\\u200b-\\u200d\\ufeff]"), "")
            .split(Regex("\\s-\\s|[·,，•∙‧]")).any {
                it.trim() in setOf(
                    "광고", "스폰서", "sponsored", "협찬 광고", "이웃광고",
                    "ad", "advertisement"   // 모바일 웹 광고 표기
                )
            }
}
