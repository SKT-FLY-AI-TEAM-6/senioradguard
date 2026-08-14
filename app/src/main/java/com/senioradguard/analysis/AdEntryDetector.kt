package com.senioradguard.analysis

/** 가림막을 띄운 사유. 로그와 검증 지표(광고 클릭 판별 정확도)에 남긴다. */
enum class ShieldReason {
    /** 광고 영역 클릭 직후의 이동 */
    AD_CLICK,

    /** 광고망 과금 리다이렉터 도메인 감지 — 클릭 이벤트를 놓쳤을 때의 백업 신호 */
    AD_REDIRECTOR
}

/**
 * "이 페이지 진입이 광고에서 왔는가"를 판정한다.
 *
 * 브라우저 전환·페이지 이동 자체는 기사 클릭에서도 일어나므로 그것만으로
 * 가림막을 띄우면 안 된다. 판정 재료는 두 가지다.
 *
 * 1. 직전 클릭이 표시 중인 광고 영역 안이었는가 (서비스가 좌표 대조 후
 *    [recordAdClick]/[recordNonAdClick]으로 알려준다) + 시간 창
 * 2. 이동한 곳이 광고망 리다이렉터인가 — 기사 클릭은 리다이렉터를 거치지 않는다
 *
 * 둘 다 아니면 개입하지 않는다. 판별 불가를 가림막으로 처리하면 기사를 누를
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
     */
    fun reasonForNavigation(host: String?): ShieldReason? {
        val fresh = hasFreshPending()
        pendingAt = null
        return when {
            fresh -> ShieldReason.AD_CLICK
            host != null && isAdRedirector(host) -> ShieldReason.AD_REDIRECTOR
            else -> null
        }
    }

    companion object {
        /**
         * 광고 클릭에서 이동까지 허용하는 시간 창. 네이티브 광고는 클릭 후 SDK가
         * 서버와 통신해 URL을 만들므로 이동까지 1~2초가 걸릴 수 있다.
         */
        const val CLICK_NAV_WINDOW_MS = 3_000L

        /**
         * 광고망 과금 리다이렉터. 광고 클릭은 거의 항상 이 중 하나를 거쳐가고,
         * 일반 콘텐츠 클릭은 거치지 않는다. 서브도메인 포함 접미사 일치로 본다.
         */
        private val redirectorHosts = setOf(
            "googleadservices.com", "doubleclick.net", "googlesyndication.com",
            "adservice.google.com", "criteo.com", "adfit.kakao.com",
            "taboola.com", "dable.io", "outbrain.com", "ad.daum.net"
        )

        fun isAdRedirector(host: String): Boolean {
            val h = host.lowercase().removeSuffix(".")
            return redirectorHosts.any { h == it || h.endsWith(".$it") }
        }
    }
}
