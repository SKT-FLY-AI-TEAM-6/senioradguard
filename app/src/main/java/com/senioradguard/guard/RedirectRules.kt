package com.senioradguard.guard

/**
 * 광고를 눌렀을 때 흔히 끌려가는 쇼핑몰. **DB를 쓰지 않고 상수로만 관리한다.**
 *
 * 목록이 짧고 거의 바뀌지 않는다. 이 정도를 원격 갱신 테이블로 만들면 다운로드·
 * 파싱·마이그레이션이 붙는데, 얻는 것은 "몇 달에 한 번 항목 추가"뿐이다.
 *
 * ## 이건 차단이 아니다
 * 쿠팡·알리는 정상 쇼핑몰이다. 문제는 사이트가 아니라 **어르신이 광고를 누른 줄
 * 모르고 끌려왔다는 것**이다. 그래서 막지 않고 "여기로 왔다"고 알린 뒤 돌아갈
 * 길을 준다. 계속 보겠다면 그대로 둔다.
 */
object RedirectRules {

    /** 브라우저 주소창에서 잡히는 도메인. */
    val AD_REDIRECT_TARGETS = setOf(
        "coupang.com",
        "aliexpress.com",
        "temu.com",
        "shein.com"
    )

    /** 앱으로 튕겨 나갈 때 잡히는 패키지명. */
    val AD_REDIRECT_PACKAGES = setOf(
        "com.coupang.mobile",
        "com.alibaba.aliexpresshd",
        "com.einnovation.temu",
        "com.zzkko"
    )

    /** 사용자에게 보여줄 이름. 패키지명을 그대로 보여주면 어르신이 못 알아본다. */
    private val DISPLAY_NAMES = mapOf(
        "coupang.com" to "쿠팡",
        "com.coupang.mobile" to "쿠팡",
        "aliexpress.com" to "알리익스프레스",
        "com.alibaba.aliexpresshd" to "알리익스프레스",
        "temu.com" to "테무",
        "com.einnovation.temu" to "테무",
        "shein.com" to "쉬인",
        "com.zzkko" to "쉬인"
    )

    /**
     * 이 패키지가 광고에서 흔히 넘어가는 쇼핑 앱인가.
     */
    fun isRedirectPackage(packageName: String?): Boolean =
        packageName != null && packageName in AD_REDIRECT_PACKAGES

    /**
     * 이 도메인이 광고에서 흔히 넘어가는 쇼핑몰인가. 서픽스로 본다 —
     * `m.coupang.com`, `shop.coupang.com`도 같은 곳이다.
     */
    fun isRedirectHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.removePrefix("www.").lowercase()
        return AD_REDIRECT_TARGETS.any { h == it || h.endsWith(".$it") }
    }

    /** 경고 문구에 넣을 이름. 모르는 값이면 받은 그대로 돌려준다. */
    fun displayName(key: String): String {
        DISPLAY_NAMES[key]?.let { return it }
        val h = key.removePrefix("www.").lowercase()
        val matched = AD_REDIRECT_TARGETS.firstOrNull { h == it || h.endsWith(".$it") }
        return matched?.let { DISPLAY_NAMES[it] } ?: key
    }
}
