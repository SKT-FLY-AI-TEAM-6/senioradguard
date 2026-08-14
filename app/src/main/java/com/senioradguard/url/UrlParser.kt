package com.senioradguard.url

/**
 * URL 문자열 → [AdLink]. 안드로이드 의존이 없는 순수 함수라 단위 테스트로 전부 덮는다.
 *
 * `java.net.URI`를 쓰지 않는다. 접근성 트리에서 주워 오는 문자열은 스킴이 없거나
 * ("yna.co.kr/article/1"), 공백·따옴표가 섞여 있거나, 한글이 그대로 들어 있어
 * URI가 URISyntaxException으로 죽는 경우가 흔하다. 판별을 못 하는 것보다 거칠게라도
 * 쪼개는 편이 낫다.
 */
object UrlParser {

    /**
     * 라벨이 둘 이상인 공개 접미사. 이게 없으면 "ad.yna.co.kr"의 등록 도메인이
     * "co.kr"로 잡혀 모든 한국 사이트가 한 도메인으로 뭉개진다.
     *
     * 공개 접미사 목록(PSL) 전체는 1만 줄이 넘어 앱에 넣을 수 없다. 어르신이 실제로
     * 마주치는 범위(국내 + 주요 국가)만 담는다. 여기 없는 접미사는 마지막 라벨
     * 하나를 TLD로 보므로, 최악의 경우 rootDomain이 한 단계 넓게 잡힐 뿐 오작동은 아니다.
     */
    private val MULTI_LABEL_SUFFIXES = setOf(
        "co.kr", "or.kr", "ne.kr", "go.kr", "re.kr", "pe.kr", "ac.kr", "hs.kr", "ms.kr",
        "es.kr", "sc.kr", "kg.kr", "seoul.kr", "busan.kr",
        "co.jp", "ne.jp", "or.jp", "ac.jp", "go.jp",
        "co.uk", "org.uk", "ac.uk", "gov.uk",
        "com.cn", "net.cn", "org.cn", "gov.cn",
        "com.au", "net.au", "org.au",
        "com.tw", "com.hk", "com.sg", "com.my", "com.vn", "com.br", "com.mx",
        "com.tr", "co.in", "co.id", "co.za", "co.nz"
    )

    /**
     * 단축 주소 서비스. 최종 목적지를 감추므로 그 자체가 신호다.
     *
     * 국내 서비스(me2.do, url.kr 등)를 함께 넣는다 — 어르신이 카카오톡으로 받는
     * 링크는 대개 이쪽이다.
     */
    private val SHORTENERS = setOf(
        "bit.ly", "bitly.com", "goo.gl", "t.co", "tinyurl.com", "ow.ly", "is.gd",
        "buff.ly", "cutt.ly", "rebrand.ly", "shorturl.at", "t.ly", "tny.im", "rb.gy",
        "me2.do", "url.kr", "c11.kr", "vo.la", "han.gl", "zrr.kr", "muz.so", "lrl.kr",
        "abit.ly", "bit.do", "kko.to", "naver.me", "buly.kr"
    )

    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    /**
     * @param raw           주워 온 URL 문자열. 스킴이 없어도 된다
     * @param sourcePageUrl 링크가 있던 페이지 또는 패키지명
     * @param anchorText    링크 문구
     * @param isAdElement   광고로 표시된 영역에서 나왔는가
     * @return 호스트를 못 읽으면 null. 호스트 없이는 어느 축으로도 판단할 수 없다
     */
    fun parse(
        raw: String,
        sourcePageUrl: String = "",
        anchorText: String = "",
        isAdElement: Boolean = false
    ): AdLink? {
        val components = components(raw) ?: return null
        return AdLink(
            targetUrl = raw.trim(),
            components = components,
            context = LinkContext(
                sourcePageUrl = sourcePageUrl.trim(),
                anchorText = anchorText.trim(),
                isAdElement = isAdElement,
                isShortener = isShortener(components.domain)
            )
        )
    }

    fun components(raw: String): UrlComponents? {
        val trimmed = raw.trim().trim('"', '\'', '<', '>')
        if (trimmed.isEmpty()) return null

        val schemeEnd = trimmed.indexOf("://")
        val protocol = if (schemeEnd > 0) trimmed.take(schemeEnd).lowercase() else ""
        // http/https가 아닌 스킴(intent:, market:, javascript:)은 웹 링크가 아니다.
        // 여기서 걸러야 "intent://..." 같은 문자열이 호스트로 둔갑하지 않는다.
        if (protocol.isNotEmpty() && protocol != "http" && protocol != "https") return null

        val rest = if (schemeEnd > 0) trimmed.substring(schemeEnd + 3) else trimmed
        if (rest.isEmpty()) return null

        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (authorityEnd < 0) rest else rest.take(authorityEnd)
        val path = if (authorityEnd < 0) "" else rest.substring(authorityEnd)

        // "http://naver.com@evil.xyz/" 는 evil.xyz로 간다. @ 앞은 사용자 정보라
        // 브라우저가 무시하는데, 사람 눈에는 naver.com으로 보인다 — 고전적인 속임수다.
        val host = authority.substringAfterLast('@')
            .substringBefore(':')
            .lowercase()
            .trimEnd('.')
        if (host.isEmpty()) return null
        // 호스트에 쓸 수 없는 글자가 있으면 URL이 아니다 (한글 문장 등)
        if (!host.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return null

        if (IPV4.matches(host)) {
            return UrlComponents(protocol, host, host, "", "", path)
        }

        val labels = host.split('.')
        if (labels.size < 2 || labels.any { it.isEmpty() }) return null

        val tld = publicSuffixOf(labels)
        val tldLabels = tld.count { it == '.' } + 1
        // 접미사만 있고 등록 도메인이 없으면("co.kr") 판단할 대상이 없다
        if (labels.size <= tldLabels) return null

        val rootDomain = labels.takeLast(tldLabels + 1).joinToString(".")
        val subdomain = labels.dropLast(tldLabels + 1).joinToString(".")

        return UrlComponents(
            protocol = protocol,
            domain = host,
            rootDomain = rootDomain,
            tld = tld,
            subdomain = subdomain,
            path = path
        )
    }

    private fun publicSuffixOf(labels: List<String>): String {
        if (labels.size >= 2) {
            val twoLabels = labels.takeLast(2).joinToString(".")
            if (twoLabels in MULTI_LABEL_SUFFIXES) return twoLabels
        }
        return labels.last()
    }

    fun isShortener(host: String): Boolean = host.removePrefix("www.") in SHORTENERS

    /**
     * 호스트를 뒤에서부터 라벨 단위로 잘라 올린 목록. 넓은 것부터 좁은 순이다.
     *
     *   "ad.yna.co.kr" → ["ad.yna.co.kr", "yna.co.kr", "co.kr", "kr"]
     *
     * 블랙리스트를 SQL `IN`으로 한 번에 조회하는 데 쓴다. 목록에 "yna.co.kr"만
     * 있어도 하위 호스트가 전부 걸리게 하려면 이 형태가 필요하다.
     */
    fun hostSuffixes(host: String): List<String> {
        val normalized = host.lowercase().trimEnd('.')
        if (normalized.isEmpty()) return emptyList()

        val out = mutableListOf<String>()
        var index = 0
        while (index in 0 until normalized.length) {
            out.add(normalized.substring(index))
            val next = normalized.indexOf('.', index)
            index = if (next < 0) -1 else next + 1
        }
        return out
    }
}
