package com.guradian.agent

/**
 * Agent4 — viewIdResourceName의 약한 신호로 판별 결과를 보정한다.
 *
 * 원래 요구사항은 HTML 클래스명(ad/sponsored/advertisement) 교차검증이었으나
 * 안드로이드 접근성 API로는 불가능하다. 크롬이 노출하는 것은 HTML id뿐이고
 * (viewIdResourceName), CSS class는 접근성 트리에 실리지 않는다.
 * AccessibilityNodeInfo.className은 안드로이드 위젯 이름이라 무관하다.
 * 그래서 가능한 범위인 id의 약한 신호로 대체한다.
 *
 * 확정 신호(div-gpt-ad, adsbygoogle 등)는 Layer 1의 AdLabelRules가 이미
 * 판별기 없이 바로 잡는다. 여기서 보는 것은 그보다 약한 힌트다.
 */
object CrossValidator {

    private const val ADJUSTMENT = 0.15f

    /** 표시 임계값. 이 값 미만이면 점선을 그리지 않는다. */
    const val THRESHOLD = 0.6f

    private val weakSignalTokens = setOf(
        "ad", "ads", "sponsor", "sponsored", "banner", "promo", "pr"
    )

    /**
     * id를 토큰으로 쪼개 정확히 비교한다. 단순 contains로 보면 "header",
     * "download", "shadow", "gradient" 같은 평범한 id가 전부 "ad"에 걸린다.
     */
    fun hasWeakSignal(viewIds: List<String>): Boolean =
        viewIds.any { id ->
            id.lowercase()
                .split(Regex("""[^a-z0-9]+"""))
                .any { it in weakSignalTokens }
        }

    /**
     * 약한 신호가 판별 결과와 같은 방향이면 올리고, 반대 방향이면 내린다.
     *
     * 신호가 아예 없을 때는 건드리지 않는다. 네이티브 앱의 id는 대개 광고와
     * 무관한 이름이라, 없다는 이유로 깎으면 앱 안의 진짜 광고가 전부 임계값
     * 아래로 밀려난다 — 없음은 반대 증거가 아니다.
     */
    fun adjust(verdict: Verdict, viewIds: List<String>): Verdict {
        if (!hasWeakSignal(viewIds)) return verdict

        val delta = if (verdict.isAd) ADJUSTMENT else -ADJUSTMENT
        val adjusted = (verdict.confidence + delta).coerceIn(0f, 1f)
        return verdict.copy(
            confidence = adjusted,
            reason = "${verdict.reason} / id 약한 신호 ${if (delta > 0) "+" else ""}$delta"
        )
    }

    /** 점선 테두리를 그릴지. 광고로 판정됐고 임계값을 넘을 때만. */
    fun shouldMark(verdict: Verdict): Boolean =
        verdict.isAd && verdict.confidence >= THRESHOLD
}
