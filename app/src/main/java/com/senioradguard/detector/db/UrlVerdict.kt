package com.senioradguard.detector.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.senioradguard.risk.RiskAssessment
import com.senioradguard.risk.RiskLevel

/**
 * URL 위험 분석 결과 캐시 1건. 같은 URL이 다시 나타나면 분석 없이 즉시
 * 위험도를 표시하기 위한 것이다.
 *
 * `ad_verdict`와 별도인 이유: 그쪽은 "이 카드가 광고인가"(화면 텍스트 기준),
 * 여기는 "이 링크가 위험한가"(목적지 기준)로 키도 수명도 다르다.
 *
 * [RiskAssessment.Unverified]는 저장하지 않는다 — 분석 실패를 캐시하면
 * 다음 기회에 분석할 수 있는 URL이 영영 미분석으로 남는다.
 *
 * 만료를 읽기 시점 TTL이 아니라 행마다 [validUntil]로 두는 이유: 피싱
 * 사이트는 수명이 짧아 고위험 판정일수록 유효기간을 짧게 줄 수 있어야 한다
 * (안전한 도메인 30일, 고위험 며칠 등 — 값은 분석 모듈이 정한다).
 */
@Entity(tableName = "url_verdict")
data class UrlVerdict(
    /** [com.senioradguard.risk.UrlNormalizer.normalize]를 거친 URL */
    @PrimaryKey val normalizedUrl: String,
    /** [RiskLevel.name] — LOW / MEDIUM / HIGH */
    val riskLevel: String,
    /** 사용자에게 그대로 보여줄 판단 이유 */
    val reason: String,
    /** 리다이렉트를 따라간 최종 도착 URL */
    val finalUrl: String,
    val analyzedAt: Long,
    val validUntil: Long
) {
    /**
     * 캐시 행을 도메인 타입으로 되돌린다. 알 수 없는 등급 문자열이면 null —
     * 앱 업데이트로 등급 체계가 바뀐 옛 행일 수 있으므로 미확보로 취급해
     * 재분석하게 한다.
     */
    fun toAssessment(): RiskAssessment.Assessed? {
        val level = RiskLevel.entries.find { it.name == riskLevel } ?: return null
        return RiskAssessment.Assessed(level, reason)
    }
}
