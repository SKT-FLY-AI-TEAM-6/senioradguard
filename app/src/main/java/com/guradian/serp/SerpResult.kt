package com.guradian.serp

/**
 * 검색 결과 목록의 한 칸. **화면 좌표는 여기 없다.**
 *
 * ## 이 세 가지가 화면에 이미 글자로 있다
 * 검색 결과 화면은 광고 배너와 결정적으로 다르다. 배너는 그림 한 장이라 무엇을
 * 파는지 알려면 픽셀을 봐야 하지만, **검색 결과는 도메인·제목·설명이 전부 글자로
 * 나와 있다.** 그래서 이 기능에는 스크린샷도 비전 모델도 필요 없다.
 *
 * 좌표를 뺀 것은 의도적이다. 위험도 판정 전체([UrlSignals] · [RiskAggregator] ·
 * [SerpRules] · [SerpRiskEngine])가 안드로이드 타입을 하나도 안 쓰게 되어
 * **JVM 단위 테스트로 그대로 덮인다.** 좌표는 [SerpScanner.Hit]가 따로 들고 있다가
 * 배지를 그릴 때 다시 만난다.
 *
 * @param host      결과가 가리키는 호스트("tvhot2.com"). 캐시·판정의 단위다
 * @param title     결과 제목
 * @param snippet   제목 아래 설명. 없을 수 있다
 */
data class SerpResult(
    val host: String,
    val title: String,
    val snippet: String
) {
    val parts: UrlParts get() = UrlParts.of(host)
}

/**
 * 호스트를 등록 도메인 단위로 쪼갠 결과. 위험도 판정의 기준 단위가 등록 도메인이다.
 *
 * "news.naver.com"과 "blog.naver.com"은 같은 naver.com이고, "naver.evil.xyz"는
 * naver.com이 **아니다.** 이 구분이 사칭 탐지의 뼈대라 문자열 포함 검사로는 안 되고
 * 반드시 쪼개서 봐야 한다.
 *
 * @param host      전체 호스트 ("blog.naver.com")
 * @param root      등록 가능 도메인 ("naver.com")
 * @param tld       공개 접미사 ("com", "co.kr"). IP 주소면 빈 문자열
 * @param subdomain root 앞부분 ("blog"). 없으면 빈 문자열
 */
data class UrlParts(
    val host: String,
    val root: String,
    val tld: String,
    val subdomain: String
) {
    /** IP 주소로 직접 연결되는가. 정상 서비스가 이렇게 노출되는 일은 거의 없다. */
    val isIpAddress: Boolean get() = tld.isEmpty() && host.isNotEmpty()

    companion object {
        /**
         * 2단계 공개 접미사. 여기 없으면 마지막 한 조각만 TLD로 본다.
         *
         * 전체 Public Suffix List(수천 줄)를 넣지 않는 이유는 무게다. 한국 사용자가
         * 만나는 결과의 대부분이 아래로 덮이고, 빠진 접미사가 있어도 등록 도메인이
         * 한 칸 길게 잡힐 뿐 판정이 뒤집히지는 않는다.
         */
        private val TWO_LEVEL_SUFFIXES = setOf(
            "co.kr", "or.kr", "ne.kr", "go.kr", "re.kr", "pe.kr", "ac.kr", "hs.kr", "ms.kr",
            "co.uk", "org.uk", "ac.uk", "gov.uk",
            "co.jp", "ne.jp", "or.jp", "ac.jp", "go.jp",
            "com.au", "net.au", "org.au", "com.cn", "net.cn", "org.cn",
            "com.tw", "com.hk", "com.sg", "com.br", "co.in", "com.vn", "co.id"
        )

        private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

        /** "https://www.blog.naver.com/x" 처럼 껍데기가 붙어 와도 호스트만 남긴다. */
        fun of(raw: String): UrlParts {
            val host = normalizeHost(raw)
            if (host.isEmpty()) return UrlParts("", "", "", "")
            if (IPV4.matches(host)) return UrlParts(host, host, "", "")

            val labels = host.split('.')
            if (labels.size < 2) return UrlParts(host, host, "", "")

            val lastTwo = labels.takeLast(2).joinToString(".")
            val tldSize = if (labels.size >= 3 && lastTwo in TWO_LEVEL_SUFFIXES) 2 else 1
            val rootSize = tldSize + 1
            if (labels.size < rootSize) return UrlParts(host, host, lastTwo, "")

            return UrlParts(
                host = host,
                root = labels.takeLast(rootSize).joinToString("."),
                tld = labels.takeLast(tldSize).joinToString("."),
                subdomain = labels.dropLast(rootSize).joinToString(".")
            )
        }

        /**
         * 스킴·경로·포트·www를 걷어낸다.
         *
         * 검색 결과의 도메인 줄은 표기가 제각각이다. 구글은 "tving.com › 다시보기"
         * 처럼 경로를 › 로 이어 붙이고, 네이버는 "www."를 붙인 채로 보여준다.
         */
        fun normalizeHost(raw: String): String {
            var value = raw.trim().lowercase()
            if (value.isEmpty()) return ""

            value = value.substringAfter("://")
            // 구글 모바일이 쓰는 경로 구분자. 여기서부터는 호스트가 아니다.
            value = value.substringBefore('›').substringBefore('>').trim()
            value = value.substringBefore('/').substringBefore('?').substringBefore('#')
            value = value.substringAfterLast('@')       // user:pass@host 형태
            value = value.substringBefore(':')          // 포트
            value = value.trim().trimEnd('.').removePrefix("www.")

            return if (value.any { it.isWhitespace() }) "" else value
        }
    }
}
