package com.senioradguard.detector

import com.senioradguard.risk.RiskAssessment
import com.senioradguard.risk.RiskLevel

/**
 * 블랙리스트 히트를 [RiskAssessment]의 **하드 신호(등급 하한)**로 만든다.
 *
 * 원칙: 14만 건 목록에 있는 도메인은 사람이 신고해 확정된 곳이다. 이후의 어떤
 * 판정 — 규칙이든 LLM이든 — 도 이 등급을 **내릴 수 없다.** LLM은 페이지가
 * 멀쩡해 보이면 저위험이라고 답할 수 있지만, 사기 사이트는 원래 멀쩡해 보인다.
 *
 * 하한이 실제로 지켜지는 경로는 두 갈래다.
 *  1. 히트 즉시 서비스가 HIGH 판정을 UrlVerdict 캐시에 upsert한다. phase4의
 *     판정 흐름(resolveFromStore)은 **캐시를 항상 먼저 보고 히트하면 거기서
 *     끝나므로**, 그 URL은 격리 분석·LLM까지 내려가지 않는다.
 *  2. phase4의 LLM 경로(escalateWithLlm)는 설계상 상향 전용이라 HIGH를
 *     덮어쓰는 코드 자체가 없다.
 * 이 두 성질 위에서, 새 코드가 블랙리스트 히트에 항상 [assessment]를 쓰는 한
 * 등급은 HIGH 아래로 내려가지 않는다.
 */
object BlacklistHardSignal {

    /** 블랙리스트 히트의 확정 판정. 항상 HIGH다. */
    fun assessment(): RiskAssessment.Assessed = RiskAssessment.Assessed(
        level = RiskLevel.HIGH,
        reason = "알려진 사기·피싱 사이트 목록에 있는 주소예요"
    )

    /**
     * 다른 경로의 판정에 하한을 적용한다. 블랙리스트에 있으면 어떤 판정이 와도
     * HIGH 밑으로 내려가지 않는다.
     */
    fun floor(
        assessed: RiskAssessment.Assessed,
        blacklisted: Boolean
    ): RiskAssessment.Assessed =
        if (blacklisted && assessed.level != RiskLevel.HIGH) assessment() else assessed
}
