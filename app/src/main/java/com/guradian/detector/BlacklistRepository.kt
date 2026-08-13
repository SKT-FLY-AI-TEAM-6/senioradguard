package com.guradian.detector

import android.content.Context
import com.guradian.detector.db.AppDatabase
import com.guradian.detector.db.BlacklistDomain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** 원격 블랙리스트 소스 하나 (URL + 그 URL의 파일 포맷). */
data class BlacklistSource(val url: String, val format: BlacklistFormat)

enum class BlacklistFormat {
    /** "0.0.0.0 domain.com" / "127.0.0.1 domain.com" 형식 */
    HOSTS,

    /** AdGuard/uBlock 필터 형식 — "||domain.com^" 도메인 차단 규칙 */
    ADGUARD,

    /** 한 줄에 도메인 하나씩, '#' 주석 허용 (레거시 포맷) */
    PLAIN
}

/**
 * 광고 네트워크 도메인 블랙리스트를 Room DB에 로컬 저장하고,
 * WorkManager가 주기적으로 원격 목록을 받아 갱신할 수 있게 해주는 저장소.
 *
 * DB가 비어있으면(최초 실행) 내장 기본 목록으로 시드한다.
 */
class BlacklistRepository(context: Context) {

    private val dao = AppDatabase.getInstance(context).blacklistDao()

    // 최초 실행 시 시드용 기본 목록 (원격 업데이트 실패 시 fallback으로도 사용)
    private val defaultDomains = setOf(
        "doubleclick.net", "googlesyndication.com", "adservice.google.com",
        "moatads.com", "criteo.com", "outbrain.com", "taboola.com",
        "adnetwork.net", "admob.com", "adsystem.amazon.com",
        "naver-analytics.com", "daumcdn-ad.net", "kakao-ad.net"
    )

    /**
     * 현재 블랙리스트 도메인 집합을 반환한다. DB가 비어있으면 기본 목록으로 시드한다.
     */
    suspend fun getDomains(): Set<String> = withContext(Dispatchers.IO) {
        val stored = dao.getAllDomains()
        if (stored.isNotEmpty()) return@withContext stored.toSet()

        dao.insertAll(defaultDomains.map { BlacklistDomain(it, System.currentTimeMillis()) })
        defaultDomains
    }

    /**
     * 여러 원격 소스를 순차적으로 다운로드해 도메인을 하나로 합친 뒤(중복 제거) 로컬 DB를 갱신한다.
     * 소스 하나가 실패해도 나머지는 계속 시도하며, 하나라도 성공하면 그 결과를 반영한다.
     * 전부 실패하거나 합친 결과가 비어있으면 기존 DB 내용은 그대로 유지한다.
     */
    suspend fun refreshFromRemote(sources: List<BlacklistSource>): Boolean = withContext(Dispatchers.IO) {
        val merged = mutableSetOf<String>()
        var anySucceeded = false

        for (source in sources) {
            val domains = runCatching { downloadDomains(source) }.getOrNull()
            if (!domains.isNullOrEmpty()) {
                merged += domains
                anySucceeded = true
            }
        }

        if (!anySucceeded || merged.isEmpty()) return@withContext false

        dao.replaceAll(merged.map { BlacklistDomain(it, System.currentTimeMillis()) })
        true
    }

    /** 단일 URL(레거시 호출부 호환용) — PLAIN 포맷으로 취급한다. */
    suspend fun refreshFromRemote(remoteUrl: String): Boolean =
        refreshFromRemote(listOf(BlacklistSource(remoteUrl, BlacklistFormat.PLAIN)))

    // ──────────────────────────────────────
    // 다운로드 + 포맷별 파싱
    // ──────────────────────────────────────

    private fun downloadDomains(source: BlacklistSource): List<String> {
        val connection = (URL(source.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
        }
        try {
            val lines = connection.inputStream.bufferedReader().readLines()
            return when (source.format) {
                BlacklistFormat.HOSTS -> parseHostsFormat(lines)
                BlacklistFormat.ADGUARD -> parseAdGuardFormat(lines)
                BlacklistFormat.PLAIN -> parsePlainFormat(lines)
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * hosts 형식 파싱: "0.0.0.0 domain.com" / "127.0.0.1 domain.com".
     * '#' 이후는 주석으로 잘라내고, IP 다음 토큰을 도메인으로 추출한다.
     */
    private fun parseHostsFormat(lines: List<String>): List<String> =
        lines.mapNotNull { rawLine ->
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) return@mapNotNull null

            val tokens = line.split(Regex("\\s+"))
            if (tokens.size < 2) return@mapNotNull null

            val ip = tokens[0]
            if (ip != "0.0.0.0" && ip != "127.0.0.1") return@mapNotNull null

            val domain = tokens[1].trim().lowercase()
            if (domain.isEmpty() || domain == "localhost") return@mapNotNull null
            domain
        }

    /**
     * AdGuard/uBlock 필터 형식 파싱. "||domain.com^" 형태의 도메인 차단 규칙만 추출한다.
     * '!' 주석, '[...]' 헤더, '@@' 예외(허용) 규칙, 코스메틱 필터 등은 무시한다.
     */
    private fun parseAdGuardFormat(lines: List<String>): List<String> {
        val domainRule = Regex("""^\|\|([a-zA-Z0-9.-]+)\^""")
        return lines.mapNotNull { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) return@mapNotNull null
            if (line.startsWith("@@")) return@mapNotNull null // 예외(허용) 규칙 제외

            domainRule.find(line)?.groupValues?.get(1)?.lowercase()
        }
    }

    /** 한 줄에 도메인 하나씩, '#' 주석 허용 (레거시 포맷). */
    private fun parsePlainFormat(lines: List<String>): List<String> =
        lines
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.lowercase() }
}
