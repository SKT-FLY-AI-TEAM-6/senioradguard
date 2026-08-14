package com.senioradguard.url

import com.senioradguard.risk.RiskCategory
import com.senioradguard.risk.RiskLevel
import com.senioradguard.risk.RiskVerdict

/**
 * 위험도를 보는 네 방향. 하나의 링크를 서로 다른 눈으로 각각 훑는다.
 *
 * 한 축이 강하게 반응하면 다른 축이 조용해도 위험할 수 있다. 예를 들어
 * "ad.yna.co.kr"은 광고 트래킹 축에서만 걸리므로 위험하지 않고,
 * "tvhot2.com"은 도메인 신뢰도와 저작권 축에서 동시에 걸린다.
 */
enum class RiskAxis(val label: String) {
    DOMAIN_TRUST("도메인 신뢰도"),
    ILLEGAL_CONTENT("저작권·불법성"),
    PHISHING_DECEPTION("피싱·속임수"),
    AD_TRACKING("광고 트래킹")
}

/**
 * 축 하나가 찾아낸 근거 한 줄.
 *
 * @param weight   양수는 위험, 음수는 신뢰. 0~100 눈금
 * @param reason   사람이 읽는 문장. 그대로 경고창과 보호자 기록에 나간다
 * @param hard     이것 하나만으로도 최소 '주의'를 줘야 하는 확정 신호인가.
 *                 LLM이 "괜찮다"고 해도 이 신호들이 만든 점수 아래로는 내려가지 않는다
 *                 ([RiskAggregator.combine] 참고)
 * @param category 이 신호가 가리키는 성격. 판별기 없이 등급을 매길 때 쓴다
 */
data class Signal(
    val axis: RiskAxis,
    val weight: Int,
    val reason: String,
    val hard: Boolean = false,
    val category: RiskCategory = RiskCategory.UNKNOWN
)

/**
 * URL 문자열과 문맥에서 위험 신호를 뽑는다. 네트워크를 쓰지 않는 순수 함수다.
 *
 * 이 신호들은 두 가지로 쓰인다.
 *  1. 판별기(LLM)가 없거나 상한에 걸렸을 때의 **단독 판정 근거** ([HeuristicUrlRiskClassifier])
 *  2. 판별기가 있을 때 프롬프트에 함께 넘기는 **증거**. URL 문자열만 던지면 LLM이
 *     "잘 모르겠다"로 수렴한다. 무엇이 이상한지 짚어주면 판단이 눈에 띄게 안정된다
 *
 * 키워드 목록은 완결일 수 없다. 새 불법 사이트는 매주 생기고 이름을 바꾼다.
 * 빠짐없이 잡는 것은 [com.senioradguard.detector.db.IllegalDomainDao] 블랙리스트와
 * 판별기의 몫이고, 여기서는 **이름만 봐도 이상한 것**을 네트워크 없이 걸러낸다.
 */
object UrlSignals {

    // ── 도메인 신뢰도 ──────────────────────────────────────────

    /**
     * 무료·익명 등록이 쉬워 악용 비중이 높은 최상위 도메인.
     * 여기 있다고 곧바로 위험은 아니다 — 가중치를 낮게 둔 이유다.
     */
    private val RISKY_TLDS = setOf(
        "xyz", "top", "cc", "tk", "ml", "ga", "cf", "gq", "buzz", "click", "link",
        "work", "rest", "icu", "pw", "su", "loan", "men", "date", "racing", "fit",
        "lol", "quest", "sbs", "cyou", "bond"
    )

    /**
     * 널리 알려진 사업자. 신뢰 가산(음수 가중치)과 사칭 탐지의 기준을 겸한다.
     *
     * 화이트리스트로 오해하면 안 된다. 여기 있으면 위험도를 낮추지만, 같은 도메인에서
     * 나온 링크라도 다른 축이 강하게 걸리면 그대로 올라간다.
     */
    private val TRUSTED_ROOTS = setOf(
        "naver.com", "daum.net", "kakao.com", "kakaocorp.com", "google.com",
        "youtube.com", "samsung.com", "lge.co.kr", "apple.com", "microsoft.com",
        "coupang.com", "11st.co.kr", "gmarket.co.kr", "auction.co.kr", "ssg.com",
        "yna.co.kr", "chosun.com", "joongang.co.kr", "donga.com", "hani.co.kr",
        "khan.co.kr", "kbs.co.kr", "mbc.co.kr", "sbs.co.kr", "ytn.co.kr",
        "hankyung.com", "mk.co.kr", "sktelecom.com", "kt.com", "uplus.co.kr",
        "toss.im", "kbstar.com", "shinhan.com", "wooribank.com", "kebhana.com",
        "nonghyup.com", "ibk.co.kr", "nhis.or.kr", "gov.kr"
    )

    /** 사칭에 쓰이는 이름들. 라벨 토큰이 정확히 일치할 때만 본다. */
    private val IMPERSONATED_BRANDS = setOf(
        "naver", "kakao", "kakaobank", "daum", "toss", "kbstar", "kbbank", "shinhan",
        "woori", "hana", "nonghyup", "ibk", "samsung", "coupang", "google", "youtube",
        "apple", "netflix", "paypal", "sktelecom", "uplus", "nhis", "gov", "police",
        "epost", "hometax"
    )

    // ── 저작권·불법성 ──────────────────────────────────────────

    /** 불법 스트리밍·웹툰·토렌트 사이트가 도메인에 흔히 쓰는 조각. */
    private val PIRACY_HOST_TERMS = setOf(
        "nunu", "noonoo", "tvwiki", "tvmon", "tvhot", "tvzone", "linkkf", "kissasian",
        "newtoki", "manatoki", "booktoki", "toonkor", "torrent", "torrents", "openload",
        "streamtape", "dramacool", "9anime", "yadong", "avsee", "sharebox", "jjalbot"
    )

    /** 문구·경로에 나타나는 불법 유통 표현. */
    private val PIRACY_TEXT_TERMS = setOf(
        "다시보기", "무료보기", "무료 보기", "전편무료", "전편 무료", "무료다운",
        "토렌트", "자막", "링크모음", "누누티비", "무료영화", "웹툰 무료"
    )

    private val GAMBLING_HOST_TERMS = setOf(
        "bet", "bets", "betting", "toto", "casino", "slot", "slots", "baccarat",
        "poker", "gamble", "gambling", "sportstoto", "powerball"
    )

    private val GAMBLING_TEXT_TERMS = setOf(
        "토토", "카지노", "바카라", "슬롯", "먹튀", "첫충", "꽁머니", "배팅", "사설"
    )

    /** 정상 서비스지만 저작권 침해 파일 유통 비중이 높아 검증이 필요한 곳. */
    private val UNVERIFIED_SHARING = setOf(
        "4shared.com", "mediafire.com", "mega.nz", "rapidgator.net", "zippyshare.com"
    )

    // ── 피싱·속임수 ────────────────────────────────────────────

    /** 다급함·이득으로 판단을 흐리는 문구. 어르신 대상 사기의 공통 문법이다. */
    private val BAIT_TERMS = setOf(
        "당첨", "경품", "축하합니다", "1등", "무료 지급", "지금 확인", "긴급",
        "본인인증", "계정 정지", "이용 제한", "확인 요망", "미수령", "환급",
        "verify", "login", "secure", "account", "gift", "prize", "winner", "claim"
    )

    // ── 광고 트래킹 ────────────────────────────────────────────

    /** 공식 광고 서버. 광고이긴 하나 위험한 링크는 아니다. */
    private val AD_NETWORK_ROOTS = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google.com", "criteo.com", "criteo.net", "taboola.com",
        "outbrain.com", "moatads.com", "adnxs.com", "rubiconproject.com",
        "pubmatic.com", "casalemedia.com", "smartadserver.com", "adfit.net",
        "adop.cc", "mobon.net", "widerplanet.com"
    )

    private val AD_HOST_PREFIXES = setOf("ad", "ads", "adserver", "adm", "track", "trk", "click")

    private val AD_PATH_MARKERS = setOf(
        "/realmedia/ads/", "/adclick", "/pagead/", "/click_lx", "/dclk", "/adserver",
        "utm_source=", "utm_medium=", "gclid=", "fbclid="
    )

    /**
     * 링크 하나에서 모든 축의 신호를 뽑는다. 순서는 축 정의 순서를 따른다.
     */
    fun of(link: AdLink): List<Signal> {
        val c = link.components
        val out = mutableListOf<Signal>()

        out += domainTrust(link, c)
        out += illegalContent(link, c)
        out += phishing(link, c)
        out += adTracking(link, c)

        return out
    }

    private fun domainTrust(link: AdLink, c: UrlComponents): List<Signal> {
        val out = mutableListOf<Signal>()

        if (c.rootDomain in TRUSTED_ROOTS || c.tld == "go.kr" || c.tld == "gov.kr") {
            out += Signal(
                RiskAxis.DOMAIN_TRUST, -45,
                "널리 알려진 사업자 도메인(${c.rootDomain})",
                category = RiskCategory.TRUSTED_KNOWN_BRAND
            )
            return out
        }

        if (c.tld.isEmpty()) {
            out += Signal(
                RiskAxis.DOMAIN_TRUST, 35,
                "도메인 대신 IP 주소로 연결됨",
                hard = true, category = RiskCategory.PHISHING_OR_SCAM
            )
        }
        if (c.tld in RISKY_TLDS) {
            out += Signal(RiskAxis.DOMAIN_TRUST, 25, "악용 비중이 높은 도메인(.${c.tld})")
        }
        if (c.domain.contains("xn--")) {
            out += Signal(
                RiskAxis.DOMAIN_TRUST, 30,
                "다른 글자로 흉내 낼 수 있는 주소(퓨니코드)",
                category = RiskCategory.PHISHING_OR_SCAM
            )
        }

        val name = c.rootDomain.substringBefore('.')
        if (name.count { it == '-' } >= 3 || name.length >= 25) {
            out += Signal(RiskAxis.DOMAIN_TRUST, 15, "기계가 만든 듯한 긴 도메인")
        }
        if (c.rootDomain in UNVERIFIED_SHARING) {
            out += Signal(
                RiskAxis.DOMAIN_TRUST, 40,
                "저작권 침해 파일 유통 비중이 높은 공유 서비스",
                category = RiskCategory.UNVERIFIED_THIRD_PARTY
            )
        }
        return out
    }

    private fun illegalContent(link: AdLink, c: UrlComponents): List<Signal> {
        val out = mutableListOf<Signal>()
        val hostText = c.domain
        val text = "${link.context.anchorText} ${c.path}".lowercase()

        PIRACY_HOST_TERMS.firstOrNull { it in hostText }?.let {
            out += Signal(
                RiskAxis.ILLEGAL_CONTENT, 70,
                "불법 다시보기 사이트가 쓰는 이름($it)",
                hard = true, category = RiskCategory.ILLEGAL_STREAMING_OR_COPYRIGHT
            )
        }
        PIRACY_TEXT_TERMS.firstOrNull { it in text }?.let {
            out += Signal(
                RiskAxis.ILLEGAL_CONTENT, 45,
                "불법 유통을 암시하는 문구($it)",
                category = RiskCategory.ILLEGAL_STREAMING_OR_COPYRIGHT
            )
        }

        // 도박 용어는 짧아서 부분 일치로 보면 "betterlife", "slotech"까지 걸린다.
        // 라벨을 토큰으로 쪼개 정확히 같을 때만 인정한다.
        labelTokens(c.domain).firstOrNull { it in GAMBLING_HOST_TERMS }?.let {
            out += Signal(
                RiskAxis.ILLEGAL_CONTENT, 75,
                "불법 도박 사이트가 쓰는 이름($it)",
                hard = true, category = RiskCategory.ILLEGAL_GAMBLING
            )
        }
        GAMBLING_TEXT_TERMS.firstOrNull { it in text }?.let {
            out += Signal(
                RiskAxis.ILLEGAL_CONTENT, 55,
                "도박을 권유하는 문구($it)",
                hard = true, category = RiskCategory.ILLEGAL_GAMBLING
            )
        }
        return out
    }

    private fun phishing(link: AdLink, c: UrlComponents): List<Signal> {
        val out = mutableListOf<Signal>()

        if (link.context.isShortener) {
            out += Signal(
                RiskAxis.PHISHING_DECEPTION, 30,
                "단축 주소라 어디로 가는지 알 수 없음",
                category = RiskCategory.UNVERIFIED_THIRD_PARTY
            )
        }
        if (c.protocol == "http") {
            out += Signal(RiskAxis.PHISHING_DECEPTION, 20, "암호화되지 않은 연결(http)")
        }

        // "http://naver.com@evil.xyz/" — @ 앞은 브라우저가 버리는데 사람 눈에는 보인다.
        // 파서가 이미 실제 호스트만 남겼으므로 원문에서 직접 확인한다.
        if (authorityOf(link.targetUrl).contains('@')) {
            out += Signal(
                RiskAxis.PHISHING_DECEPTION, 45,
                "주소에 @가 있어 보이는 곳과 실제 목적지가 다름",
                hard = true, category = RiskCategory.PHISHING_OR_SCAM
            )
        }

        // 사칭 — 브랜드 이름이 등록 도메인이 아니라 서브도메인이나 경로에만 있다.
        // "naver.secure-login.xyz"는 실제로는 secure-login.xyz다.
        val rootName = c.rootDomain.substringBefore('.')
        val decoyTokens = labelTokens(c.subdomain) + labelTokens(c.path)
        decoyTokens.firstOrNull { it in IMPERSONATED_BRANDS && it != rootName }?.let {
            out += Signal(
                RiskAxis.PHISHING_DECEPTION, 60,
                "'$it' 이름을 흉내 냈지만 실제 주소는 ${c.rootDomain}",
                hard = true, category = RiskCategory.PHISHING_OR_SCAM
            )
        }

        if (c.subdomain.isNotEmpty() && c.subdomain.count { it == '.' } >= 2) {
            out += Signal(RiskAxis.PHISHING_DECEPTION, 15, "주소 앞단이 불필요하게 깊음")
        }

        if (c.path.substringBefore('?').endsWith(".apk", ignoreCase = true)) {
            out += Signal(
                RiskAxis.PHISHING_DECEPTION, 60,
                "앱 설치 파일(.apk)을 직접 내려받게 함",
                hard = true, category = RiskCategory.MALWARE_OR_UNWANTED_APP
            )
        }

        val text = "${link.context.anchorText} ${c.path}".lowercase()
        BAIT_TERMS.firstOrNull { it in text }?.let {
            out += Signal(
                RiskAxis.PHISHING_DECEPTION, 25,
                "판단을 재촉하는 문구($it)",
                category = RiskCategory.PHISHING_OR_SCAM
            )
        }
        return out
    }

    private fun adTracking(link: AdLink, c: UrlComponents): List<Signal> {
        val out = mutableListOf<Signal>()
        val lowerPath = c.path.lowercase()

        if (c.rootDomain in AD_NETWORK_ROOTS) {
            out += Signal(
                RiskAxis.AD_TRACKING, 10,
                "알려진 광고 서버(${c.rootDomain})",
                category = RiskCategory.OFFICIAL_AD_TRACKER
            )
        } else if (c.subdomain.substringBefore('.') in AD_HOST_PREFIXES) {
            out += Signal(
                RiskAxis.AD_TRACKING, 10,
                "광고 전용 주소(${c.domain})",
                category = RiskCategory.OFFICIAL_AD_TRACKER
            )
        }

        AD_PATH_MARKERS.firstOrNull { it in lowerPath }?.let {
            out += Signal(
                RiskAxis.AD_TRACKING, 10,
                "광고 클릭 추적 경로($it)",
                category = RiskCategory.OFFICIAL_AD_TRACKER
            )
        }
        if (link.context.isAdElement) {
            out += Signal(RiskAxis.AD_TRACKING, 5, "광고로 표시된 영역에서 나온 링크")
        }
        return out
    }

    /** "ad.yna.co.kr" → [ad, yna, co, kr]. 숫자·하이픈·구분자로 잘라 토큰만 남긴다. */
    private fun labelTokens(value: String): List<String> =
        value.lowercase().split(Regex("""[^a-z]+""")).filter { it.isNotEmpty() }

    /** 원문 URL에서 스킴을 뗀 뒤 첫 '/' 앞부분. @ 속임수를 보려면 원문이 필요하다. */
    private fun authorityOf(rawUrl: String): String {
        val rest = rawUrl.substringAfter("://", rawUrl)
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        return if (end < 0) rest else rest.take(end)
    }
}
