package com.senioradguard.guard

/**
 * 광고를 눌러 쇼핑몰에 닿았는지를 **주소가 바뀐 흔적**으로 판단한다.
 *
 * ## 왜 클릭 이벤트로는 안 되는가
 * 원래는 `TYPE_VIEW_CLICKED`로 광고 노드를 눌렀는지 확인했다. 실기기에서 확인해
 * 보니 **크롬은 웹 광고를 눌러도 그 이벤트를 보내지 않는다.** 같은 순간의 로그에
 * 광고 영역은 잡혀 있었고(OCR 광고=1~4) 클릭 이벤트만 0건이었다. 웹 콘텐츠는
 * 가상 노드 트리라 위젯 클릭으로 취급되지 않기 때문이다.
 *
 * ## 대신 무엇을 보는가
 * 광고를 누르면 브라우저는 광고 중계 서버를 거쳐 목적지로 간다. 실측 사례:
 *
 * ```
 * m.yonhapnewstv.co.kr  ← 읽던 뉴스
 * clickads.co.kr        ← 0.22초만 머무름  (중계)
 * link.coupang.com      ← 3초
 * coupang.com           ← 도착
 * ```
 *
 * 주소를 직접 입력하거나 즐겨찾기로 들어가면 이런 **스쳐 지나가는 제3의 도메인**이
 * 없다. 그 차이로 "광고를 눌러서 왔다"를 가른다.
 *
 * ## 이 판정이 틀리는 경우
 * 광고가 중계 없이 곧바로 쇼핑몰로 보내면 놓친다(경고를 안 함). 반대로 사용자가
 * 검색 결과처럼 중계성 링크를 거쳐 들어가면 잘못 경고할 수 있다. 놓치는 쪽이
 * 덜 해로우므로 증거가 없으면 조용히 있는 쪽을 택한다.
 */
class RedirectChainTracker(private val now: () -> Long = System::currentTimeMillis) {

    companion object {
        /** 이보다 짧게 머문 도메인은 사람이 읽은 페이지가 아니라 중계로 본다. */
        const val TRANSIENT_MS = 2_500L

        /** 이 시간이 지난 방문 기록은 버린다. */
        const val HISTORY_MS = 15_000L

        /**
         * 한국에서 흔한 2단계 접미사. `co.kr`까지 잘라야 `a.co.kr`과 `b.co.kr`을
         * 다른 사이트로 본다 — 두 조각만 쓰면 둘 다 "co.kr"이 되어 같은 곳이 된다.
         */
        private val TWO_LEVEL_SUFFIXES = setOf(
            "co.kr", "or.kr", "ne.kr", "go.kr", "re.kr", "pe.kr", "ac.kr",
            "co.jp", "co.uk", "com.au", "com.cn", "com.br"
        )

        /** 도메인에서 "같은 사이트"를 가르는 단위를 뽑는다 (eTLD+1 근사). */
        fun registrable(host: String): String {
            val parts = host.removePrefix("www.").lowercase().split('.')
            if (parts.size <= 2) return parts.joinToString(".")
            val lastTwo = parts.takeLast(2).joinToString(".")
            val take = if (lastTwo in TWO_LEVEL_SUFFIXES) 3 else 2
            return parts.takeLast(take).joinToString(".")
        }
    }

    private data class Visit(val site: String, val at: Long)

    private val history = ArrayDeque<Visit>()

    /**
     * 중계를 타기 직전에 보고 있던 사이트.
     *
     * 광고를 눌렀는데 중계를 거쳐 **원래 페이지로 되돌아오는** 경우가 있다
     * (실측: tenbizt.com → krrtb.c.appier.net → tenbizt.com). 사용자는 있던
     * 자리에 그대로 있으므로 "광고를 통해 이동했습니다"는 틀린 말이 된다.
     */
    var originBeforeRelay: String? = null
        private set

    /**
     * 주소가 바뀔 때마다 부른다.
     *
     * @return 광고를 거쳐 온 것으로 보이면 true
     */
    fun onHost(host: String): Boolean {
        val t = now()
        val site = registrable(host)

        while (history.isNotEmpty() && t - history.first().at > HISTORY_MS) {
            history.removeFirst()
        }
        // 같은 사이트 안에서의 이동(m.coupang.com → coupang.com)은 새 방문이 아니다.
        if (history.lastOrNull()?.site == site) return false

        val previous = history.lastOrNull()
        history.addLast(Visit(site, t))

        // 직전 방문이 "스쳐 지나간 제3의 도메인"이면 중계를 거친 것이다.
        if (previous == null) return false
        if (previous.site == site) return false
        if (t - previous.at > TRANSIENT_MS || !wasTransient(previous)) return false

        // 중계(previous) 앞에 있던 사이트가 원래 자리다.
        originBeforeRelay = history.elementAtOrNull(history.size - 3)?.site
        return true
    }

    /** 직전 방문이 그 앞 방문과도 다른 사이트여야 "끼어든 중계"다. */
    private fun wasTransient(previous: Visit): Boolean {
        val beforePrevious = history.elementAtOrNull(history.size - 3) ?: return true
        return beforePrevious.site != previous.site
    }

    fun reset() = history.clear()
}
