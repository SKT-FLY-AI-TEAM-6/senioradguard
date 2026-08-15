package com.guradian.serp

/**
 * 우리가 이름을 아는 사이트들. **규칙의 데이터 부분을 코드에서 떼어낸 자리다.**
 *
 * ## 왜 인터페이스인가 — 목록은 절대 완결되지 않는다
 * 불법 사이트는 차단되면 숫자만 바꿔 되살아난다(`tvhot2` → `tvhot3`,
 * `noonoo` → `nooo16` → `noonootvk2`). 실기기 확인에서도 우리 목록에 있던
 * `tvhot2.com` 옆에 목록에 없던 `nooo16.tv`가 나란히 떴다. 앱에 박아 넣은 목록으로
 * 따라가는 것은 **원리적으로 불가능하다.**
 *
 * 그래서 이 설계는 목록에 기대지 않는다.
 *
 *  - 목록에 있으면 → 공짜로, 즉시, 네트워크 없이 판정한다 (빠른 길)
 *  - 목록에 없으면 → 판별기가 본다 (**기본 경로**)
 *  - 판별기도 못 보면 → [RiskGrade.UNKNOWN]. 아무 표시도 하지 않는다.
 *    모르는 것을 '안전'이라고 말하지 않는 것이 이 구조의 핵심이다
 *
 * 목록이 커질수록 호출이 줄어들 뿐, 목록이 작다고 기능이 망가지지는 않는다.
 *
 * ## 교체 지점
 * 지금은 [BuiltInKnownSites]가 앱에 박힌 씨앗을 준다. 나중에 방송통신심의위원회
 * 차단 목록이나 KISA 피드를 받으면 **이 인터페이스 구현 하나만 갈아끼우면 된다** —
 * [UrlSignals]도 [SerpRules]도 손대지 않는다.
 */
interface KnownSites {

    /** 널리 알려진 사업자·공식 서비스의 등록 도메인 ("tving.com"). */
    val trustedRoots: Set<String>

    /** 접미사만으로 신뢰하는 공공 영역 ("go.kr"). 기관 수가 많아 일일이 못 적는다. */
    val publicSuffixes: Set<String>

    /** 사칭에 쓰이는 유명 이름. **토큰이 정확히 일치할 때만** 본다. */
    val impersonatedBrands: Set<String>

    /** 불법 스트리밍·웹툰·토렌트 사이트가 도메인에 흔히 쓰는 조각. */
    val piracyHostTerms: Set<String>

    /** 도박 사이트가 쓰는 이름. 짧아서 토큰 단위로만 본다. */
    val gamblingHostTerms: Set<String>

    /** 성인 사이트가 쓰는 이름. 역시 토큰 단위. */
    val adultHostTerms: Set<String>

    /** 최종 목적지를 감추는 단축 주소 서비스. */
    val shorteners: Set<String>

    /**
     * 목록 두 개를 겹친다. 원격 피드를 받았을 때 **씨앗을 지우지 않고 얹는** 데 쓴다.
     * 피드가 비거나 내려받기에 실패해도 최소한 씨앗만큼은 계속 동작한다.
     */
    operator fun plus(other: KnownSites): KnownSites = object : KnownSites {
        override val trustedRoots = this@KnownSites.trustedRoots + other.trustedRoots
        override val publicSuffixes = this@KnownSites.publicSuffixes + other.publicSuffixes
        override val impersonatedBrands =
            this@KnownSites.impersonatedBrands + other.impersonatedBrands
        override val piracyHostTerms = this@KnownSites.piracyHostTerms + other.piracyHostTerms
        override val gamblingHostTerms =
            this@KnownSites.gamblingHostTerms + other.gamblingHostTerms
        override val adultHostTerms = this@KnownSites.adultHostTerms + other.adultHostTerms
        override val shorteners = this@KnownSites.shorteners + other.shorteners
    }
}

/**
 * 앱에 박힌 씨앗 목록. **시연·개발용 소량 표본이지 방어선이 아니다.**
 *
 * 여기 없는 것을 잡는 일은 판별기의 몫이다. 목록을 늘리는 것으로 이 기능의 성능을
 * 끌어올리려 하지 말 것 — 늘려도 다음 주에 이름이 바뀐다. 목록의 역할은 **가장 흔한
 * 것들을 공짜로 걸러 판별기 호출을 아끼는 것**이다.
 */
object BuiltInKnownSites : KnownSites {

    /**
     * 검색 결과에서는 이쪽이 특히 중요하다. "드라마 다시보기"를 검색하면 공식 OTT와
     * 불법 사이트가 같은 목록에 나란히 뜨는데, **공식 쪽에 초록을 붙여 주는 것이
     * 불법 쪽에 빨강을 붙이는 것만큼 중요하다.** 어느 것을 눌러도 되는지가 보이지
     * 않으면 어르신은 결국 맨 위를 누른다.
     */
    override val trustedRoots = setOf(
        // 포털·플랫폼
        "naver.com", "daum.net", "kakao.com", "google.com", "google.co.kr", "youtube.com",
        "namu.wiki", "wikipedia.org", "tistory.com", "brunch.co.kr",
        // 공식 OTT·방송 (이 기능의 시나리오가 정확히 여기다)
        "netflix.com", "tving.com", "wavve.com", "watcha.com", "coupangplay.com",
        "disneyplus.com", "primevideo.com", "seezn.com", "kocowa.com", "laftel.net",
        "kbs.co.kr", "imbc.com", "sbs.co.kr", "jtbc.co.kr", "ebs.co.kr", "tvn.co.kr",
        // 언론
        "yna.co.kr", "chosun.com", "joongang.co.kr", "donga.com", "hani.co.kr",
        "khan.co.kr", "ytn.co.kr", "hankyung.com", "mk.co.kr", "news1.kr", "newsis.com",
        // 유통·금융·통신
        "coupang.com", "11st.co.kr", "gmarket.co.kr", "auction.co.kr", "ssg.com",
        "lotteon.com", "musinsa.com", "oliveyoung.co.kr", "ohou.se",
        "samsung.com", "lge.co.kr", "apple.com", "microsoft.com",
        "sktelecom.com", "kt.com", "uplus.co.kr",
        "toss.im", "kbstar.com", "shinhan.com", "wooribank.com", "kebhana.com",
        "nonghyup.com", "ibk.co.kr", "kakaobank.com"
    )

    override val publicSuffixes = setOf("go.kr", "or.kr", "ac.kr")

    /**
     * 짧고 흔한 이름(sk·kt·lg·gov)은 넣지 않았다. 부분 일치로 걸면 정상 도메인이
     * 무더기로 걸리고, 정상에 붙는 경고 하나가 불법을 놓치는 것보다 해롭다.
     */
    override val impersonatedBrands = setOf(
        "naver", "kakao", "kakaobank", "daum", "toss", "kbstar", "shinhan", "woori",
        "nonghyup", "samsung", "coupang", "google", "youtube", "apple", "netflix",
        "disney", "tving", "wavve", "watcha", "paypal", "hometax", "epost", "kbank"
    )

    override val piracyHostTerms = setOf(
        "nunu", "noonoo", "tvwiki", "tvmon", "tvhot", "tvzone", "tvnori", "linkkf",
        "kissasian", "newtoki", "manatoki", "booktoki", "toonkor", "torrent", "openload",
        "streamtape", "dramacool", "9anime", "yadong", "avsee", "sharebox", "dasibogi",
        "movieuf", "kmovie", "hdtv", "freetv"
    )

    override val gamblingHostTerms = setOf(
        "bet", "bets", "betting", "toto", "casino", "slot", "slots", "baccarat",
        "poker", "gamble", "gambling", "sportstoto", "powerball"
    )

    override val adultHostTerms = setOf("av", "avsee", "yadong", "sex", "porn", "adult", "19")

    override val shorteners = setOf(
        "bit.ly", "tinyurl.com", "goo.gl", "t.co", "is.gd", "buly.kr", "me2.do",
        "han.gl", "vo.la", "abit.ly", "url.kr", "muz.so"
    )
}
