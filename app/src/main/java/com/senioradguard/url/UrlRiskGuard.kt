package com.senioradguard.url

/**
 * Layer 4의 흐름 제어. "언제 판별할지"와 "같은 것을 두 번 알리지 않기"만 맡는다.
 *
 * 판정 자체는 [UrlRiskPipeline]이, 화면 표시는 서비스가 한다. 이 클래스에
 * 안드로이드 의존을 두지 않아 흐름을 단위 테스트로 덮을 수 있다.
 *
 * ## 두 개의 입구
 *  - **사전** [onAdLinksSeen] — 광고 영역에서 주소를 미리 긁어 왔을 때. 누르기 전에
 *    알 수 있으면 가장 좋지만, 크롬이 주소를 노출하지 않으면 아무것도 오지 않는다
 *  - **사후** [onPageChanged] — 광고를 누른 직후 주소창이 바뀌었을 때. 항상 동작하는
 *    경로이고, 이동을 막지는 못해도 "뒤로 가기"를 권하기에는 늦지 않다
 *
 * ## 클릭과 이동을 어떻게 잇는가
 * 접근성 이벤트에는 "이 클릭이 저 이동을 일으켰다"는 연결이 없다. 광고 영역 안을
 * 누른 사실을 기억해 두고, [CLICK_WINDOW_MS] 안에 일어난 주소 변화만 그 클릭의
 * 결과로 본다. 창을 길게 잡으면 사용자가 스스로 친 주소까지 광고 착지로 오인하고,
 * 짧게 잡으면 느린 회선에서 놓친다.
 *
 * ## 클릭 이벤트가 안 오는 경우 — 이게 오히려 흔하다
 * 실기기 확인(2026-08-14, SM-S937N, 크롬)에서 **웹 페이지 안의 배너를 눌렀을 때
 * TYPE_VIEW_CLICKED가 오지 않는다**는 것이 드러났다. 실제로 광고를 눌러 크롬이
 * 광고주 페이지로 넘어갔는데 클릭 이벤트가 하나도 도착하지 않았다. 클릭 이벤트에만
 * 기대면 모바일 웹 광고는 거의 전부 놓친다.
 *
 * 그래서 이동 자체를 두 번째 신호로 쓴다 — **광고가 떠 있던 화면에서 다른 사이트로
 * 넘어갔다면** 광고 착지로 본다([crossedSiteAfterAds]). 사용자가 정상 외부 링크를
 * 눌렀을 때도 걸리지만, 그 경우 판정은 '낮음'으로 나와 경고가 뜨지 않는다.
 * 즉 틀렸을 때의 대가가 판별 호출 한 번(그리고 캐시 적재)에 그친다.
 */
class UrlRiskGuard(
    private val pipeline: UrlRiskPipeline,
    /** 판정이 나왔을 때 부른다. 화면 표시는 호출부(서비스)의 몫이다. */
    private val onVerdict: suspend (AdLink, UrlRiskVerdict) -> Unit,
    /**
     * 판별기(LLM)를 불러도 되는가. AI 판별 토글이 꺼져 있으면 false를 준다.
     *
     * 꺼도 Layer 4가 통째로 멈추지는 않는다. 확인된 불법 목록 조회와 규칙 판정은
     * 네트워크를 쓰지 않으므로 그대로 돈다 — 위험한 곳에 들어갔을 때 아무 말도
     * 하지 않는 것보다는, 확실한 것만이라도 알려주는 편이 낫다.
     */
    private val allowClassify: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis
) {

    companion object {
        /** 광고를 누른 뒤 이 시간 안에 바뀐 주소만 그 클릭의 결과로 본다. */
        const val CLICK_WINDOW_MS = 8_000L

        /**
         * 광고가 화면에 있었던 뒤 이 시간 안의 사이트 이동만 광고 착지 후보로 본다.
         * 광고가 떠 있는 동안 스캔마다 갱신되므로, 실제로는 "광고를 보던 중에 일어난
         * 이동인가"를 뜻한다.
         */
        const val ADS_SHOWN_WINDOW_MS = 8_000L

        /** 세션 동안 기억할 호스트 수. 한 번 알린 곳은 다시 알리지 않는다. */
        private const val MEMO_CAPACITY = 64
    }

    private class PendingClick(val anchorText: String, val sourcePageUrl: String, val at: Long)

    private var pending: PendingClick? = null

    /** 마지막으로 본 주소. 같은 주소로 이벤트가 여러 번 와도 한 번만 판별한다. */
    private var lastUrl: String? = null

    /** 마지막으로 광고 표시가 화면에 있었던 시각. 0이면 없었다. */
    private var adsShownAt = 0L

    /** 광고가 떠 있던 화면의 출처. 클릭 기억이 없을 때 문맥으로 쓴다. */
    private var adsShownOn = ""

    /**
     * 이번 세션에서 이미 알린 호스트. 스크롤을 오르내릴 때마다 같은 광고 서버로
     * 경고가 반복되면 사용자가 앱을 꺼버린다.
     */
    private val reported = object : LinkedHashMap<String, Boolean>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) =
            size > MEMO_CAPACITY
    }

    /** 광고로 표시된 영역 안이 눌렸다. 뒤이어 올 주소 변화를 이 클릭의 결과로 본다. */
    fun onAdClicked(anchorText: String, sourcePageUrl: String) {
        pending = PendingClick(anchorText, sourcePageUrl, now())
    }

    /** 지금 화면에 광고 표시가 떠 있다. 스캔이 광고를 표시할 때마다 알려준다. */
    fun onAdsShown(sourcePageUrl: String) {
        adsShownAt = now()
        adsShownOn = sourcePageUrl
    }

    /**
     * 주소창을 읽을 필요가 있는가.
     *
     * 호출부가 주소창 조회(IPC 한 번)를 할지 정하는 데 쓴다. 광고를 누른 적도 없고
     * 화면에 광고가 뜬 적도 없으면 착지를 판별할 일이 없으므로 아예 읽지 않는다.
     */
    fun wantsPageUrl(): Boolean = hasPendingClick() || withinAdsWindow()

    /** 아직 유효한 클릭 기억이 있는가. */
    fun hasPendingClick(): Boolean =
        pending?.let { now() - it.at <= CLICK_WINDOW_MS } ?: false

    private fun withinAdsWindow(): Boolean =
        adsShownAt != 0L && now() - adsShownAt <= ADS_SHOWN_WINDOW_MS

    /**
     * 주소창이 바뀌었다. 광고를 누른 기록이 있거나, 광고가 떠 있던 화면에서
     * 다른 사이트로 넘어갔을 때 판별한다.
     *
     * @return 판별을 시작했으면 true. 호출부가 로그를 남길지 정하는 데 쓴다
     */
    suspend fun onPageChanged(url: String?): Boolean {
        val landing = url?.trim().orEmpty()
        if (landing.isEmpty() || landing == lastUrl) return false
        val previous = lastUrl
        lastUrl = landing

        val click = pending?.takeIf { now() - it.at <= CLICK_WINDOW_MS }
        pending = null

        // 클릭 이벤트가 온 경우가 확실한 광고 클릭이다. 안 왔으면 이동 자체를 본다.
        if (click == null && !crossedSiteAfterAds(previous, landing)) return false

        val link = UrlParser.parse(
            raw = landing,
            sourcePageUrl = click?.sourcePageUrl?.ifBlank { adsShownOn } ?: adsShownOn,
            anchorText = click?.anchorText.orEmpty(),
            // 클릭 이벤트 없이 이동만 보고 온 건은 광고 요소라고 단정하지 않는다.
            // 판별기에 넘기는 문맥이라 사실이 아닌 값을 넣으면 판단이 그만큼 틀어진다.
            isAdElement = click != null
        ) ?: return false

        evaluate(link)
        return true
    }

    /**
     * 광고가 떠 있던 화면에서 **다른 사이트로** 넘어갔는가.
     *
     * 같은 사이트 안의 이동(기사 → 기사)은 광고 착지가 아니다. 등록 도메인이
     * 바뀌었을 때만 본다. 사용자가 정상 외부 링크를 눌렀을 때도 걸리지만, 그때는
     * 판정이 '낮음'으로 나와 경고가 뜨지 않는다 — 틀렸을 때의 대가가 판별 호출
     * 한 번에 그치도록 문턱을 여기 두었다.
     */
    private fun crossedSiteAfterAds(previous: String?, landing: String): Boolean {
        if (!withinAdsWindow()) return false
        val before = previous?.let { UrlParser.components(it)?.rootDomain } ?: return false
        val after = UrlParser.components(landing)?.rootDomain ?: return false
        return before != after
    }

    /**
     * 광고 영역에서 주소를 미리 찾았다. 누르기 전에 판별해 둔다.
     *
     * 캐시에 남으므로, 사용자가 실제로 눌렀을 때는 판별기를 부르지 않고 즉시 답이 나온다.
     */
    suspend fun onAdLinksSeen(links: List<AdLink>) {
        for (link in links) evaluate(link)
    }

    private suspend fun evaluate(link: AdLink) {
        // 이번 세션에서 이미 다룬 호스트는 파이프라인까지 가지 않는다
        if (reported[link.cacheKey] != null) return

        val result = runCatching { pipeline.evaluate(link, allowClassify()) }.getOrNull() ?: return

        // 결론이 난 판정이거나 사용자에게 알린 판정만 기억한다. 상한에 걸려 규칙만으로
        // 낸 '낮음'까지 기억해 버리면, 잠시 뒤 판별기가 살아나도 그 도메인을 이 세션
        // 내내 다시 보지 않는다. 결론이 안 난 건은 다음 기회에 제대로 본다 —
        // 그 비용은 DB 조회 한 번이라 반복돼도 부담이 없다.
        val conclusive = result.blacklisted || result.fromCache || result.classified
        if (conclusive || result.verdict.level != RiskLevel.LOW) reported[link.cacheKey] = true

        onVerdict(result.link, result.verdict)
    }

    /** 대상 앱을 벗어났을 때. 남은 클릭 기억이 다음 화면으로 새지 않게 한다. */
    fun reset() {
        pending = null
        lastUrl = null
        adsShownAt = 0L
        adsShownOn = ""
    }
}
