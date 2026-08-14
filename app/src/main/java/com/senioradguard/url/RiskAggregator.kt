package com.senioradguard.url

/**
 * 네 축의 신호와 판별기 결과를 하나의 등급으로 합친다. 순수 함수 모음이다.
 *
 * ## 점수를 어떻게 만드는가
 * 신호를 전부 더하지 않는다. 더하면 사소한 신호 다섯 개가 확정 신호 하나를
 * 이겨버리고, 축을 늘릴 때마다 기존 판정이 통째로 흔들린다.
 *
 *   점수 = 가장 강한 신호 + (나머지 신호 합 ÷ 3) + 신뢰 가산(음수)
 *
 * 가장 강한 신호가 등급을 정하고 나머지는 거들기만 한다. 축을 하나 더 붙여도
 * 이미 맞히던 판정이 어긋나지 않는다.
 *
 * ## 광고 트래킹만 걸렸을 때
 * 언론사 광고 서버(ad.yna.co.kr)는 신호가 여럿 잡히지만 위험하지 않다. 광고 축
 * 신호밖에 없으면 [AD_TRACKING_ONLY_CAP]으로 눌러 '낮음'을 벗어나지 못하게 한다.
 * 이게 없으면 정상 광고마다 경고가 떠서 사용자가 앱을 믿지 않게 된다.
 */
object RiskAggregator {

    /** 광고 축 신호만 있을 때의 점수 상한. 정상 광고에 경고를 띄우지 않기 위한 장치다. */
    const val AD_TRACKING_ONLY_CAP = 25

    /** 보조 신호를 몇 분의 일로 반영할지. 값이 작을수록 "가장 강한 신호" 중심이 된다. */
    private const val SUPPORT_DIVISOR = 3

    fun score(signals: List<Signal>): Int {
        val positives = signals.filter { it.weight > 0 }.sortedByDescending { it.weight }
        if (positives.isEmpty()) return 0

        val trust = signals.filter { it.weight < 0 }.sumOf { it.weight }
        val support = positives.drop(1).sumOf { it.weight } / SUPPORT_DIVISOR
        val raw = positives.first().weight + support + trust

        val capped =
            if (positives.all { it.axis == RiskAxis.AD_TRACKING }) minOf(raw, AD_TRACKING_ONLY_CAP)
            else raw

        return capped.coerceIn(0, 100)
    }

    /**
     * 확정 신호만으로 계산한 하한.
     *
     * 판별기가 "안전하다"고 답해도 이 아래로는 내려가지 않는다. LLM은 URL만 보고
     * 판단하므로 .apk 직접 배포나 @ 속임수 같은 **문자열에 드러난 사실**을 놓치거나
     * 대수롭지 않게 넘기는 일이 있다. 신뢰 가산은 여기에 반영하지 않는다 —
     * 알려진 브랜드라는 사실이 확정 신호를 지워주지는 않기 때문이다.
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

    /** 판별기 없이 신호만으로 내리는 판정. */
    fun heuristic(signals: List<Signal>): UrlRiskVerdict {
        val value = score(signals)
        return UrlRiskVerdict(
            category = category(signals),
            level = RiskLevel.of(value),
            score = value,
            reasons = reasonsOf(signals),
            source = UrlRiskVerdict.SOURCE_HEURISTIC
        )
    }

    /**
     * 판별기 결과를 신호와 합친다.
     *
     * 판별기가 최종 판단을 하고, 신호는 [hardFloor]로 바닥만 받친다. 순서를 반대로
     * 하면(신호가 판단, LLM이 보정) 목록에 없는 새 사이트를 영영 못 잡는다 —
     * 목록에 없는 것을 잡으려고 판별기를 붙인 것이다.
     */
    fun combine(signals: List<Signal>, classified: UrlRiskVerdict?): UrlRiskVerdict {
        if (classified == null) return heuristic(signals)

        val value = maxOf(classified.score, hardFloor(signals)).coerceIn(0, 100)
        val category =
            if (classified.category == RiskCategory.UNKNOWN) category(signals)
            else classified.category

        return UrlRiskVerdict(
            category = category,
            level = RiskLevel.of(value),
            score = value,
            // 판별기 근거를 앞에 둔다. 문맥을 본 설명이 먼저 읽혀야 한다.
            reasons = (classified.reasons + reasonsOf(signals))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(UrlRiskVerdict.MAX_REASONS),
            source = classified.source
        )
    }

    private fun reasonsOf(signals: List<Signal>): List<String> {
        val positives = signals.filter { it.weight > 0 }.sortedByDescending { it.weight }
        if (positives.isNotEmpty()) {
            return positives.take(UrlRiskVerdict.MAX_REASONS).map { it.reason }
        }
        return signals.filter { it.weight < 0 }.map { it.reason }.take(1)
    }
}
