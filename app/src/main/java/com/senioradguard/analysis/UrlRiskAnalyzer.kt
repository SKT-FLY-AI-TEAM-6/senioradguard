package com.senioradguard.analysis

import com.senioradguard.risk.RiskAssessment
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * URL 위험 분석기 — 이 인터페이스가 온디바이스 LLM(4c)의 교체점이다.
 *
 * AdClassifier와 같은 원칙: 구현체를 바꿔도 가림막·캐시·정책은 손대지 않는다.
 * 단 LLM 구현체가 오더라도 [UrlRiskRules]의 위험 신호를 무시하고 등급을
 * 내릴 수는 없다 (프롬프트 주입 방어 — 설계 문서 3.3절).
 */
interface UrlRiskAnalyzer {

    /** 판정의 출처. url_verdict 원인 추적용. */
    val source: String

    /**
     * 사용자 브라우저와 분리된 환경에서 페이지를 가져와 판정한다.
     * @return 실패(연결 불가·시간 초과 등)면 null — 호출부는 Unverified로
     *         다루고 **캐시에 저장하지 않는다.**
     */
    suspend fun analyze(rawUrl: String): AnalyzedPage?
}

class AnalyzedPage(
    val finalUrl: String,
    val assessment: RiskAssessment.Assessed,
    /** 최종 페이지 본문. 규칙이 저위험이라 한 경우 온디바이스 LLM의 2차 판단 입력이 된다. */
    val html: String? = null
)

/**
 * 규칙 기반 구현 — 격리 수집([PageFetcher]) + 규칙 판정([UrlRiskRules]).
 *
 * 격리 원칙 (설계 문서 3.3절):
 * - JS 실행 없음, 쿠키 없음 — 브라우저 익스플로잇의 공격 표면이 없다
 * - 파일 다운로드 없음 — Content-Type이 HTML이 아니면 본문을 읽지 않는다
 * - 추적 리다이렉트는 최종 도착지 확인에 필요한 만큼만 따라간다 (과금 클릭
 *   반복 발생 금지)
 */
class RuleBasedUrlAnalyzer : UrlRiskAnalyzer {

    override val source = "RULES"

    override suspend fun analyze(rawUrl: String): AnalyzedPage? = withContext(Dispatchers.IO) {
        val page = PageFetcher.fetch(rawUrl) ?: return@withContext null
        AnalyzedPage(
            finalUrl = page.finalUrl,
            assessment = UrlRiskRules.assess(page.finalUrl, page.hops, page.contentType, page.html),
            html = page.html
        )
    }
}

/**
 * 리다이렉트 체인을 따라가 최종 페이지의 HTML만 걷어 온다.
 *
 * HttpURLConnection에 CookieHandler를 설치하지 않으므로 쿠키는 오가지 않고,
 * 사용자 브라우저 세션과도 완전히 분리된다.
 */
object PageFetcher {

    private const val MAX_HOPS = 6
    private const val CONNECT_TIMEOUT_MS = 1500
    private const val READ_TIMEOUT_MS = 2000
    /** 본문 상한. 판정에 필요한 폼·링크는 앞부분에 있고, 무한 스트림 방어다. */
    private const val MAX_BODY_CHARS = 200_000

    // 일부 사이트는 기본 Java UA를 차단한다. 모바일 페이지를 받아야 어르신이
    // 보는 것과 같은 화면을 판정한다.
    private const val UA = "Mozilla/5.0 (Linux; Android 14; SM-S921N) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    class Page(
        val finalUrl: String,
        val hops: Int,
        val contentType: String?,
        /** HTML이 아니면 null — 본문을 아예 읽지 않았다는 뜻이다 */
        val html: String?
    )

    fun fetch(rawUrl: String): Page? {
        var url = runCatching {
            URL(if ("://" in rawUrl) rawUrl else "https://$rawUrl")
        }.getOrNull() ?: return null
        if (url.protocol != "http" && url.protocol != "https") return null

        var hops = 0
        while (true) {
            val conn = runCatching { url.openConnection() as HttpURLConnection }.getOrNull()
                ?: return null
            try {
                conn.instanceFollowRedirects = false   // 체인을 직접 세고 기록한다
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.setRequestProperty("User-Agent", UA)
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                conn.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9")

                val code = runCatching { conn.responseCode }.getOrNull() ?: return null

                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: return null
                    if (++hops > MAX_HOPS) {
                        // 끝없이 도는 체인 — 최종지를 못 정하니 실패로 다룬다
                        return null
                    }
                    url = runCatching { URL(url, location) }.getOrNull() ?: return null
                    if (url.protocol != "http" && url.protocol != "https") {
                        // intent:// 등으로 빠지는 체인. 본문 없이 지금까지의 정보로 판정한다
                        return Page(url.toString(), hops, null, null)
                    }
                    continue
                }

                val contentType = conn.contentType
                val isHtml = contentType == null ||
                    contentType.contains("text/html") || contentType.contains("xhtml")
                val html = if (code in 200..299 && isHtml) {
                    val charset = contentType
                        ?.substringAfter("charset=", "")
                        ?.substringBefore(';')?.trim()?.ifEmpty { null } ?: "UTF-8"
                    runCatching {
                        InputStreamReader(conn.inputStream, charset).use { reader ->
                            val buf = CharArray(8192)
                            val sb = StringBuilder()
                            while (sb.length < MAX_BODY_CHARS) {
                                val n = reader.read(buf)
                                if (n < 0) break
                                sb.append(buf, 0, n)
                            }
                            sb.toString()
                        }
                    }.getOrNull()
                } else {
                    null   // HTML이 아니면 읽지 않는다 (다운로드 금지 원칙)
                }
                return Page(url.toString(), hops, contentType, html)
            } finally {
                conn.disconnect()
            }
        }
    }
}
