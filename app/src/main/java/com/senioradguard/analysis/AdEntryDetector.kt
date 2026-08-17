package com.senioradguard.analysis

/** 가림막을 띄운 사유. 로그와 검증 지표(광고 클릭 판별 정확도)에 남긴다. */
enum class ShieldReason {
    /** 광고 영역 클릭 직후의 이동 */
    AD_CLICK,

    /** 광고망 과금 리다이렉터 도메인 감지 */
    AD_REDIRECTOR,

    /** 도착 URL에 광고 클릭 추적 파라미터(gclid 등)가 붙어 있음 */
    AD_LANDING,

    /**
     * 스쳐 지나간 제3의 도메인(중계)을 거쳐 도착 — [com.senioradguard.guard.RelayTransitTracker] 판정.
     *
     * 위 세 가지가 못 잡는 구멍을 메운다. 실측(쿠팡 배너, 네이트 뉴스):
     * `m.news.nate.com → api.ootoo.co.kr(0.2초) → login.coupang.com`에서
     * 중계 도메인이 우리 목록에 없고, 도착 URL에 광고 파라미터도 없으며,
     * 크롬이 웹 광고에 클릭 이벤트를 만들지 않아 **셋 다 빗나갔다.** 중계 추적은
     * 도메인 목록이 아니라 "머문 시간"으로 판단하므로 처음 보는 광고망도 잡는다.
     */
    AD_RELAY
}

/**
 * "이 페이지 진입이 광고에서 왔는가"를 판정한다.
 *
 * 브라우저 전환·페이지 이동 자체는 기사 클릭에서도 일어나므로 그것만으로
 * 가림막을 띄우면 안 된다. 판정 재료는 세 가지다.
 *
 * 1. 직전 클릭이 표시 중인 광고 영역 안이었는가 (서비스가 좌표 대조 후
 *    [recordAdClick]/[recordNonAdClick]으로 알려준다) + 시간 창
 * 2. 이동한 곳이 광고망 리다이렉터인가 — 기사 클릭은 리다이렉터를 거치지 않는다
 * 3. 도착 URL에 광고 클릭 추적 파라미터가 붙어 있는가 — **웹 광고의 주 판별
 *    경로다.** 실기기 검증(2026-08-14, 연합뉴스TV의 실제 사기성 광고)에서
 *    크롬이 웹 광고 탭에 TYPE_VIEW_CLICKED를 아예 만들지 않는 것이 확인됐다.
 *    좌표 판별(1)은 표준 링크에서만 통하고, 실제 광고 위젯은 이 경로로 잡는다.
 *    광고망은 과금 정산을 위해 도착 URL에 클릭 ID(gclid 등)를 반드시 붙이므로
 *    클릭을 못 봐도 도착지가 광고발임을 URL이 증명한다.
 *
 * 셋 다 아니면 개입하지 않는다. 판별 불가를 가림막으로 처리하면 기사를 누를
 * 때마다 가림막이 떠서 서비스를 못 쓰게 된다 — 그 경우 호출부는 조용히
 * 캐시 조회만 한다.
 *
 * 시계를 주입받는 순수 클래스라 JVM 단위 테스트 대상이다.
 */
class AdEntryDetector(private val clock: () -> Long) {

    // 0이나 -1 같은 센티널 값을 쓰지 않는다 — 주입된 시계가 0부터 시작하면
    // "클릭 시각 0"과 "대기 없음"이 구분되지 않는다 (단위 테스트가 잡은 버그).
    private var pendingAt: Long? = null

    /** 광고 영역 안 클릭이 확인됐다. 이후 [CLICK_NAV_WINDOW_MS] 안의 이동을 광고발로 본다. */
    fun recordAdClick() {
        pendingAt = clock()
    }

    /**
     * 광고 밖 클릭. 이전의 광고 클릭 대기를 취소한다 — 광고를 눌렀다가 바로
     * 다른 곳을 눌렀다면 다음 이동은 나중 클릭의 결과다.
     */
    fun recordNonAdClick() {
        pendingAt = null
    }

    fun hasFreshPending(): Boolean =
        pendingAt?.let { clock() - it <= CLICK_NAV_WINDOW_MS } == true

    /**
     * 페이지 이동이 감지됐을 때 호출한다. 가림막 사유를 돌려주고 클릭 대기를
     * 소모한다(같은 클릭으로 가림막이 두 번 뜨지 않게). null이면 개입 금지.
     *
     * @param host 이동한 곳의 호스트. 확보 전이면 null — 클릭 대기만으로 판정한다.
     * @param rawUrl 주소창에 보이는 이동 후 URL 원문(정규화 전). 광고 클릭
     *        추적 파라미터 검사에 쓰므로 정규화 전 값이어야 한다 —
     *        UrlNormalizer가 바로 그 파라미터들을 지운다.
     * @param previousHost 직전에 있던 호스트. **직전이 리다이렉터였다면 지금
     *        페이지는 광고 도착지다** — 리다이렉터 체류는 수백 ms라 그 순간을
     *        놓칠 수 있는데, 다음 이동에서 한 번 더 잡는 확정 신호다
     *        (실기기: nate → trace.popin.cc → 랜딩의 두 번째 전환).
     */
    fun reasonForNavigation(
        host: String?,
        rawUrl: String? = null,
        previousHost: String? = null
    ): ShieldReason? {
        val fresh = hasFreshPending()
        pendingAt = null
        return when {
            fresh -> ShieldReason.AD_CLICK
            host != null && isAdRedirector(host) -> ShieldReason.AD_REDIRECTOR
            previousHost != null && isAdRedirector(previousHost) -> ShieldReason.AD_REDIRECTOR
            rawUrl != null && isAdLanding(rawUrl) -> ShieldReason.AD_LANDING
            else -> null
        }
    }

    companion object {
        /**
         * 광고 클릭에서 이동까지 허용하는 시간 창. 네이티브 광고는 클릭 후 SDK가
         * 서버와 통신해 URL을 만들므로 이동까지 1~2초가 걸릴 수 있다.
         *
         * 3초 → 10초 상향 (phase6 실측): 광고 클릭 감지 후 주소가 바뀌기까지
         * 7초가 걸린 사례가 있었다 — 광고 서버 응답, 중계, 앱 딥링크 해석이
         * 차례로 붙는다. 창을 넓히면 "광고를 눌러 닫은 뒤 스스로 다른 곳을 연"
         * 경우를 잘못 잡을 수 있지만, 10초 안에 그 두 가지를 다 하는 경우는 드물다.
         */
        const val CLICK_NAV_WINDOW_MS = 10_000L

        /**
         * 광고망 과금 리다이렉터. 광고 클릭은 거의 항상 이 중 하나를 거쳐가고,
         * 일반 콘텐츠 클릭은 거치지 않는다. 서브도메인 포함 접미사 일치로 본다.
         *
         * popin.cc는 실기기 검증(2026-08-14, 네이트 뉴스의 popIn 추천위젯 광고)에서
         * 실제 경유가 확인돼 추가했다 — 새 광고망을 만나면 여기에 계속 보탠다.
         */
        private val redirectorHosts = setOf(
            "googleadservices.com", "doubleclick.net", "googlesyndication.com",
            "adservice.google.com", "criteo.com", "adfit.kakao.com",
            "taboola.com", "dable.io", "outbrain.com", "ad.daum.net",
            // 국내 광고망
            "popin.cc", "mobon.net", "realclick.co.kr", "cauly.net", "adop.cc",
            // 실측(2026-08-14, 연합뉴스TV 쿠팡 배너): 클릭 → 아래 경유지 → 쿠팡 앱
            // 딥링크. 경유지를 못 잡으면 앱으로 빠져나가는 광고를 통째로 놓친다.
            "clickads.co.kr", "mjbiz.co.kr",
            "link.coupang.com"   // 쿠팡 파트너스 (coupang.com 본체는 리다이렉터가 아님)
        )

        fun isAdRedirector(host: String): Boolean {
            val h = host.lowercase().removeSuffix(".")
            return redirectorHosts.any { h == it || h.endsWith(".$it") }
        }

        /**
         * 신뢰 플랫폼의 공식 관문. 여기서 체인이 끝나면 목적지는 그 플랫폼 안이다 —
         * 도메인 소유자가 플랫폼 자신이라 다른 곳으로는 못 간다.
         * 실측(쿠팡 파트너스): link.coupang.com은 302 없이 JS로 coupang:// 앱
         * 스킴을 쏘는 페이지라 체인 추적이 여기서 끝난다. "확인 불가"가 아니라
         * "쿠팡으로 연결되는 광고"가 정직한 판정이다.
         */
        private val trustedTerminals = mapOf(
            "link.coupang.com" to "쿠팡"
        )

        fun trustedTerminalName(host: String): String? {
            val h = host.lowercase().removeSuffix(".")
            return trustedTerminals.entries
                .firstOrNull { h == it.key || h.endsWith(".${it.key}") }?.value
        }

        /**
         * 광고망이 과금 정산용으로 도착 URL에 붙이는 클릭 추적 파라미터.
         * 이게 붙어 있으면 이 페이지는 광고 클릭으로 도착한 것이다.
         *
         * 이름만으로 판정하는 목록에 일반 utm_*은 넣지 않는다 — utm_source 등은
         * 뉴스레터·SNS 공유 링크에도 흔해서 오탐이 난다. utm_medium은 값이
         * 유료 매체를 가리킬 때만 인정한다.
         */
        private val adClickParams = setOf(
            "gclid", "gad_source", "gbraid", "wbraid",   // 구글 광고
            "msclkid",                                    // 마이크로소프트 광고
            "n_media", "n_ad", "n_ad_group", "n_campaign", "n_query"  // 네이버 광고
        )

        private val adUtmMediums = setOf("cpc", "ppc", "paid", "display", "banner", "paid_social")

        /**
         * 리다이렉터가 목적지를 쿼리에 담아 두는 경우(origUrl= 등) 그 URL을 꺼낸다.
         * JS로만 넘어가는 경유지는 서버 리다이렉트 체인으로 안 풀리는데
         * (실측: api.mjbiz.co.kr/kw/rdw?origUrl=link.coupang.com…), 목적지가
         * 쿼리에 그대로 있어 이걸 이어가면 체인이 계속된다.
         * 리다이렉터 호스트의 URL에만 쓸 것 — 일반 페이지에선 오탐이 난다.
         */
        fun embeddedDestination(rawUrl: String): String? {
            val query = rawUrl.substringAfter('?', "").substringBefore('#')
            if (query.isEmpty()) return null
            for (param in query.split('&')) {
                val value = param.substringAfter('=', "")
                val decoded = runCatching {
                    java.net.URLDecoder.decode(value, "UTF-8")
                }.getOrNull() ?: continue
                if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
                    return decoded
                }
            }
            return null
        }

        /** 도착 URL에 광고 클릭 추적 파라미터가 붙어 있는가. */
        fun isAdLanding(rawUrl: String): Boolean {
            val query = rawUrl.substringAfter('?', "").substringBefore('#')
            if (query.isEmpty()) return false
            for (param in query.split('&')) {
                val name = param.substringBefore('=').lowercase()
                if (name in adClickParams) return true
                if (name == "utm_medium" &&
                    param.substringAfter('=', "").lowercase() in adUtmMediums
                ) return true
            }
            return false
        }
    }
}
