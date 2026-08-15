package com.guradian.serp

/**
 * 신호와 판별기 결과를 하나의 등급으로 합친다. 순수 함수 모음이다.
 *
 * ## 점수를 어떻게 만드는가
 * 신호를 전부 더하지 않는다. 더하면 사소한 신호 다섯 개가 확정 신호 하나를
 * 이겨버리고, 규칙을 하나 추가할 때마다 기존 판정이 통째로 흔들린다.
 *
 *   점수 = 가장 강한 신호 + (나머지 신호 합 ÷ 3) + 신뢰 가산(음수)
 *
 * 가장 강한 신호가 등급을 정하고 나머지는 거들기만 한다. 규칙을 더 붙여도 이미
 * 맞히던 판정이 어긋나지 않는다. senioradguard V3에서 검증된 식을 그대로 옮겼다.
 */
object RiskAggregator {

    /** 보조 신호를 몇 분의 일로 반영할지. 작을수록 "가장 강한 신호" 중심이 된다. */
    private const val SUPPORT_DIVISOR = 3

    fun score(signals: List<Signal>): Int {
        val positives = signals.filter { it.weight > 0 }.sortedByDescending { it.weight }
        if (positives.isEmpty()) return 0

        val trust = signals.filter { it.weight < 0 }.sumOf { it.weight }
        val support = positives.drop(1).sumOf { it.weight } / SUPPORT_DIVISOR
        return (positives.first().weight + support + trust).coerceIn(0, 100)
    }

    /**
     * 확정 신호만으로 계산한 **하한**.
     *
     * 판별기가 "안전하다"고 답해도 이 아래로는 내려가지 않는다. 판별기는 글자만
     * 보고 판단하므로 도박 용어나 IP 직결 같은 **주소에 이미 드러난 사실**을
     * 대수롭지 않게 넘기는 일이 있다.
     *
     * 신뢰 가산은 여기에 반영하지 않는다 — 알려진 플랫폼에 올라온 글이라는 사실이
     * 그 글이 불법 사이트를 안내한다는 사실을 지워주지는 않는다.
     */
    fun hardFloor(signals: List<Signal>): Int {
        val hard = signals.filter { it.hard && it.weight > 0 }.sortedByDescending { it.weight }
        if (hard.isEmpty()) return 0
        val support = hard.drop(1).sumOf { it.weight } / SUPPORT_DIVISOR
        return (hard.first().weight + support).coerceIn(0, 100)
    }

    /** 가장 강한 신호가 가리키는 성격. 없으면 신뢰 신호를, 그것도 없으면 보류. */
    fun category(signals: List<Signal>): RiskCategory {
        signals.filter { it.weight > 0 && it.category != RiskCategory.UNKNOWN }
            .maxByOrNull { it.weight }
            ?.let { return it.category }

        if (signals.any { it.category == RiskCategory.TRUSTED_KNOWN_BRAND }) {
            return RiskCategory.TRUSTED_KNOWN_BRAND
        }
        if (signals.any { it.weight > 0 }) return RiskCategory.UNVERIFIED_THIRD_PARTY
        return RiskCategory.UNKNOWN
    }

    /** 배지에 넣을 근거 한 줄. 가장 강한 신호의 문장을 쓴다. */
    fun reason(signals: List<Signal>): String {
        signals.filter { it.weight > 0 }.maxByOrNull { it.weight }?.let { return it.reason }
        signals.firstOrNull { it.weight < 0 }?.let { return it.reason }
        return "확인된 위험은 없습니다"
    }

    /**
     * 판별기 없이 신호만으로 내리는 판정.
     *
     * ## 걸린 규칙이 없다 ≠ 안전하다
     * 위험 신호도 신뢰 신호도 없으면 [SerpVerdict.unknown]을 준다. 0점을 '안전'으로
     * 흘려보내면 **우리가 모르는 사이트 전부에 초록 도장을 찍게 된다.** 우리 목록은
     * 작고 앞으로도 완결되지 않으므로, 그 초록의 대부분은 근거가 없다.
     * 초록은 알려진 곳이거나 판별기가 실제로 보고 안전하다고 했을 때만 붙는다.
     *
     * 위험 신호가 하나라도 있으면 점수대로 판정한다. 종합 점수와 [hardFloor] 중
     * 높은 쪽을 쓰는데, 하한을 빼먹으면 신뢰 가산이 확정 신호를 지워버린다 —
     * "cafe.naver.com에 올라온 도박 권유 글"이 네이버라는 이유로 '안전'이 되는 식이다.
     */
    fun ruleVerdict(signals: List<Signal>): SerpVerdict {
        val trusted = signals.any { it.category == RiskCategory.TRUSTED_KNOWN_BRAND }
        val risky = signals.any { it.weight > 0 }
        if (!trusted && !risky) return SerpVerdict.unknown(SerpVerdict.SOURCE_RULE)

        return SerpVerdict.of(
            category = category(signals),
            score = maxOf(score(signals), hardFloor(signals)),
            reason = reason(signals),
            source = SerpVerdict.SOURCE_RULE
        )
    }

    /**
     * 판별기 결과를 신호와 합친다.
     *
     * **판별기가 최종 판단을 하고, 신호는 [hardFloor]로 바닥만 받친다.** 순서를
     * 반대로 하면(신호가 판단하고 판별기가 보정) 목록에 없는 새 사이트를 영영 못
     * 잡는다 — 목록에 없는 것을 잡으려고 판별기를 붙인 것이다.
     */
    fun combine(signals: List<Signal>, classified: SerpVerdict?): SerpVerdict {
        if (classified == null) return ruleVerdict(signals)

        val floor = hardFloor(signals)
        val score = maxOf(classified.score, floor)
        // 규칙이 판정을 끌어올렸다면 근거도 규칙 쪽 문장이 맞다. 어르신에게는
        // "왜 위험한지"와 "얼마나 위험한지"가 어긋나 보이면 안 된다.
        val reason = if (floor > classified.score) reason(signals) else classified.reason

        return SerpVerdict.of(
            category = if (classified.category == RiskCategory.UNKNOWN) category(signals)
            else classified.category,
            score = score,
            reason = reason,
            source = classified.source
        )
    }
}
