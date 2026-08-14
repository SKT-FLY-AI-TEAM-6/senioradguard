package com.guradian.serp

/**
 * **AI를 부를지 말지를 정하는 문지기.** 이 기능의 비용 구조 전체가 여기서 갈린다.
 *
 * 규칙만으로 결론이 나면 [resolve]가 판정을 돌려주고 판별기는 호출되지 않는다.
 * 결론이 안 나면 null을 돌려주고, 그때만 Gemini로 간다.
 *
 * ## 규칙이 단독으로 끝낼 수 있는 두 경우
 *
 * **1. 확정 위험** — 확정 신호([Signal.hard])만으로 '위험'(70) 이상이 나왔다.
 * 불법 다시보기 이름(tvhot·newtoki·누누), 도박 용어, 성인 사이트가 여기다.
 * 전부 주소에 이미 드러난 사실이라 추론할 것이 없고, **네트워크 왕복 없이 즉시
 * 빨강이 붙는다.**
 *
 * **2. 확정 안전** — 잘 알려진 도메인인데 위험 신호가 하나도 없다.
 * tving.com, netflix.com, kbs.co.kr이 여기다. 검색 결과 목록의 절반 가까이가
 * 보통 여기 해당한다.
 *
 * ## 왜 '주의'(40)가 아니라 '위험'(70)에서만 끝내는가
 * 표본 27건으로 두 경계를 다 돌려봤다(2026-08-14). 40으로 잡으면 25/27이 규칙에서
 * 끝나 값싸지만, **APK 직접 배포 사이트와 IP로 직결되는 무료영화 사이트가 '주의'로
 * 확정돼 버렸다.** 둘 다 판별기는 90점대 '위험'으로 본 곳이다.
 *
 * 규칙이 확정하면 판별기는 그 항목을 보지 못하므로 **틀려도 고칠 기회가 없다.**
 * '주의'는 정의상 확신이 없다는 뜻이고, 확신이 없으면 넘기는 것이 이 문지기의
 * 계약이다. 경계를 70으로 올리면 규칙 확정은 15/27로 줄지만 그중 오답은 0이 된다.
 *
 * 비용은 거의 그대로다 — 판별기 호출은 **항목당이 아니라 화면당 1회**라서,
 * 한 화면에서 판별기로 내려가는 항목이 2개든 12개든 호출 수는 같다.
 *
 * ## 왜 "알려진 도메인"만으로 끝내지 않는가
 * 신뢰 도메인이어도 **위험 신호가 하나라도 있으면 판별기로 보낸다.** 티스토리·
 * 네이버 블로그처럼 정상 플랫폼에 "무료 다시보기 사이트 순위 TOP10" 같은 글이
 * 올라오는 경우가 실제로 흔한데, 도메인만 보고 초록을 칠하면 그 글이 안내하는
 * 불법 사이트로 어르신을 그대로 보내게 된다.
 */
object SerpRules {

    /**
     * @return 규칙만으로 결론이 났으면 판정, 판별기가 필요하면 null
     */
    fun resolve(signals: List<Signal>): SerpVerdict? {
        val floor = RiskAggregator.hardFloor(signals)
        if (floor >= RiskGrade.HIGH.minScore) {
            return RiskAggregator.ruleVerdict(signals)
        }

        val trusted = signals.any { it.category == RiskCategory.TRUSTED_KNOWN_BRAND }
        val anyRisk = signals.any { it.weight > 0 }
        if (trusted && !anyRisk) {
            return SerpVerdict.of(
                category = RiskCategory.TRUSTED_KNOWN_BRAND,
                score = 0,
                reason = RiskAggregator.reason(signals),
                source = SerpVerdict.SOURCE_RULE
            )
        }

        return null
    }
}
