package com.senioradguard.risk

/**
 * URL 판정 캐시(`url_verdict`)의 키를 만드는 정규화.
 *
 * 같은 광고 링크가 추적 파라미터(utm_*, gclid …)만 바뀐 채 반복 등장하므로,
 * 그대로 키로 쓰면 캐시가 사실상 매번 미스다. ad_verdict 캐시 키에서 숫자를
 * `#`으로 치환한 것과 같은 이유의 조치다.
 *
 * `java.net.URI`를 쓰지 않는다 — 광고 URL에는 인코딩 안 된 한글·공백·`|`가
 * 흔해서 URISyntaxException으로 죽는다. 필요한 만큼만 직접 자른다.
 */
object UrlNormalizer {

    /** 이름이 정확히 일치하면 제거하는 추적 파라미터 */
    private val trackingParams = setOf(
        "gclid", "fbclid", "msclkid", "dclid", "twclid", "igshid",
        "wbraid", "gbraid", "yclid"
    )

    /**
     * 정규화된 URL을 돌려준다. http/https가 아니거나 호스트를 알아볼 수
     * 없으면 null — 호출부는 이를 "URL 미확보"(Unverified)로 다뤄야 하며,
     * 원본 문자열을 키로 쓰는 폴백을 만들면 안 된다.
     *
     * 하는 일: 스킴·호스트 소문자화, 기본 포트(80/443) 제거, 프래그먼트
     * 제거, 추적 파라미터 제거, 남은 파라미터 이름순 정렬(순서만 다른 같은
     * 주소를 한 키로), userinfo 제거, 빈 경로는 `/`.
     *
     * userinfo 제거는 보안 조치이기도 하다 — `http://bank.com@evil.com`의
     * 실제 호스트는 evil.com이고, 이 형태 자체가 전형적 피싱 수법이다.
     */
    fun normalize(raw: String): String? {
        val parts = parse(raw) ?: return null
        val query = parts.query
            .split('&')
            .filter { it.isNotEmpty() && !isTrackingParam(it.substringBefore('=')) }
            .sorted()
            .joinToString("&")
        return buildString {
            append(parts.scheme).append("://").append(parts.host)
            if (parts.port != null) append(':').append(parts.port)
            append(parts.path)
            if (query.isNotEmpty()) append('?').append(query)
        }
    }

    /** 실제 호스트만 필요할 때 (블랙리스트 도메인 매칭 등) */
    fun hostOf(raw: String): String? = parse(raw)?.host

    private fun isTrackingParam(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("utm_") || lower in trackingParams
    }

    private data class Parts(
        val scheme: String,
        val host: String,
        val port: Int?,
        val path: String,
        val query: String
    )

    private fun parse(raw: String): Parts? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        // 스킴이 없으면 https로 가정한다. 주소창·노드 텍스트에서 얻는 URL은
        // "example.com/path" 꼴이 흔하다.
        val schemeEnd = trimmed.indexOf("://")
        val scheme: String
        val rest: String
        if (schemeEnd >= 0) {
            scheme = trimmed.substring(0, schemeEnd).lowercase()
            rest = trimmed.substring(schemeEnd + 3)
        } else {
            if (trimmed.contains(':') && !trimmed.substringBefore(':').contains('.')) {
                // "market:", "intent:" 같은 다른 스킴 — URL 분석 대상이 아니다.
                // 스토어·앱 이동은 Layer 3(InstallGuard)의 영역이다.
                return null
            }
            scheme = "https"
            rest = trimmed
        }
        if (scheme != "http" && scheme != "https") return null

        val beforeFragment = rest.substringBefore('#')
        val query = beforeFragment.substringAfter('?', "")
        val hostAndPath = beforeFragment.substringBefore('?')

        val slash = hostAndPath.indexOf('/')
        val authority = if (slash >= 0) hostAndPath.substring(0, slash) else hostAndPath
        val path = if (slash >= 0) hostAndPath.substring(slash) else "/"

        // userinfo 제거 — 마지막 @ 뒤가 실제 호스트다
        val hostPort = authority.substringAfterLast('@')
        if (hostPort.isEmpty()) return null

        val host: String
        var port: Int? = null
        val colon = hostPort.lastIndexOf(':')
        if (colon >= 0) {
            host = hostPort.substring(0, colon)
            port = hostPort.substring(colon + 1).toIntOrNull() ?: return null
        } else {
            host = hostPort
        }

        val cleanHost = host.lowercase().removeSuffix(".")
        // 호스트에 공백·비정상 문자가 있으면 URL이 아니라 그냥 문구다
        if (cleanHost.isEmpty() || cleanHost.any { it.isWhitespace() } || '.' !in cleanHost) return null

        if ((scheme == "http" && port == 80) || (scheme == "https" && port == 443)) {
            port = null
        }

        return Parts(scheme, cleanHost, port, path, query)
    }
}
