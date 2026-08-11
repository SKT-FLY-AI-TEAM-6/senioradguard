package com.senioradguard.detector

import android.content.Context

/**
 * AdDetector
 *
 * 2가지 탐지 전략을 결합해 0.0~1.0 신뢰도 점수를 반환합니다.
 *
 *   scoreByKeywords()  — 텍스트 키워드 패턴 (< 1ms, 항상 실행)
 *   scoreByBlacklist() — 도메인 DB 조회 (< 5ms, URL 있을 때)
 */
class AdDetector(private val context: Context) {

    // 앱 설치 유도 키워드 → 가중치
    private val keywordWeights = mapOf(
        "설치하기" to 1.0f, "지금 설치" to 1.0f, "무료 설치" to 0.9f,
        "무료 다운로드" to 0.9f, "앱 다운로드" to 0.85f,
        "지금 받기" to 0.8f, "혜택 받기" to 0.7f,
        "이벤트 참여" to 0.6f, "광고" to 0.5f,
        "install now" to 1.0f, "free download" to 0.9f,
        "get the app" to 0.85f, "download now" to 0.9f
    )

    // 도메인 블랙리스트 (Room DB 캐시, WorkManager가 주 1회 원격 목록으로 갱신)
    private val blacklistRepository = BlacklistRepository(context)

    // ──────────────────────────────────────
    // 1. 키워드 패턴 매칭
    // ──────────────────────────────────────

    /**
     * 텍스트에서 광고 키워드를 찾아 0.0~1.0 점수 반환.
     * 여러 키워드 매칭 시 최대값을 반환 (합산 아님).
     */
    fun scoreByKeywords(text: String): Float {
        var maxScore = 0f
        for ((keyword, weight) in keywordWeights) {
            if (text.contains(keyword, ignoreCase = true)) {
                maxScore = maxOf(maxScore, weight)
            }
        }
        return maxScore
    }

    // ──────────────────────────────────────
    // 2. 도메인 블랙리스트
    // ──────────────────────────────────────

    /**
     * URL 목록 중 블랙리스트 도메인 포함 여부 확인.
     * 하나라도 매칭되면 1.0, 없으면 0.0 반환.
     */
    suspend fun scoreByBlacklist(urls: List<String>): Float {
        if (urls.isEmpty()) return 0f
        val domains = BlacklistCache.domains { blacklistRepository.getDomains() }
        for (url in urls) {
            val domain = extractDomain(url) ?: continue
            if (DomainMatcher.isBlocked(domain, domains)) return 1.0f
        }
        return 0f
    }

    private fun extractDomain(url: String): String? = runCatching {
        java.net.URL(url).host.removePrefix("www.")
    }.getOrNull()

}
