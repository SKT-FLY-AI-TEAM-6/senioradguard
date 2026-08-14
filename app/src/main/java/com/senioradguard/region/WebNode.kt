package com.senioradguard.region

import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 브라우저가 웹 문서의 정보를 접근성 노드에 얹어 보내는 통로.
 *
 * HTML 원문 전체가 오는 것은 아니고, 오는 것과 오지 않는 것이 정해져 있다.
 *   오는 것    : id 속성(viewIdResourceName), 링크 href·이미지 src(targetUrl), AX 롤(chromeRole)
 *   오지 않는 것: class 속성, data-* 속성, iframe의 src
 *
 * 크롬 계열과 파이어폭스(GeckoView)는 키 이름만 다르고 구조가 같다.
 */
object WebNode {

    const val CHROME_ROLE = "AccessibilityNodeInfo.chromeRole"
    const val GECKO_ROLE = "AccessibilityNodeInfo.geckoRole"
    const val TARGET_URL = "AccessibilityNodeInfo.targetUrl"

    /**
     * 크롬은 화면 밖으로 스크롤된 노드에 이 표시를 붙인다.
     * 그런 노드는 좌표가 높이 0으로 접혀 오기도 하지만 항상 그렇지는 않아서, 표시를 직접 본다.
     * (실측: 광고 노드 대부분이 `900x0` + offscreen=true 로 왔다)
     */
    const val OFFSCREEN = "AccessibilityNodeInfo.offscreen"

    /** 크롬이 웹 문서 전체를 담는 호스트 노드로 쓰는 클래스명 */
    const val WEB_HOST_CLASS = "android.webkit.WebView"

    /** targetUrl은 이 롤에서만 채워진다 (브라우저가 링크·이미지·비디오에만 실어 보낸다) */
    private val urlBearingRoles = setOf("link", "image", "video", "img")

    /** 광고 한 칸의 경계가 되는 롤 */
    private val frameRoles = setOf("iframe", "iframePresentational", "rootWebArea")

    /**
     * 중첩 문서의 경계. iframe 안쪽은 남의 문서다.
     * (rootWebArea는 iframe마다 하나씩 또 생기므로 경계 판정에는 쓰지 않는다)
     */
    private val nestedDocRoles = setOf("iframe", "iframePresentational")

    /**
     * `co.kr`처럼 두 단계짜리 공용 접미사. 여기까지는 "사이트"가 아니라 등록 단위 밖이다.
     * (m.news.nate.com 과 m.nate.com 을 같은 사이트로 보려면 nate.com 까지 잘라야 한다)
     */
    private val twoLevelSuffixes = setOf(
        "co", "or", "ne", "go", "re", "pe", "ac", "hs", "ms", "es", "sc", "kg", "com", "net", "org"
    )

    /**
     * extras는 노드마다 Bundle을 만들 수 있어 비싸다. 웹 서브트리 안에서만 부르고
     * 네이티브 UI에서는 아예 건드리지 않는다.
     */
    fun str(node: AccessibilityNodeInfo, key: String): String? = try {
        node.extras?.getString(key)?.takeIf { it.isNotEmpty() }
    } catch (e: Throwable) {
        null
    }

    fun role(node: AccessibilityNodeInfo): String? =
        str(node, CHROME_ROLE) ?: str(node, GECKO_ROLE)

    /** 이 노드가 웹 문서의 뿌리인가 (= 여기서부터 아래가 웹 콘텐츠다) */
    fun isWebHost(node: AccessibilityNodeInfo): Boolean {
        if (node.className?.toString() == WEB_HOST_CLASS) return true
        //                ^^^^^^^^^^^ className은 CharSequence다. String과 == 로 비교하면
        //                구현체가 SpannableString일 때 항상 false가 되어 판정이 통째로 무너진다.
        return role(node) == "rootWebArea"
    }

    fun isFrame(node: AccessibilityNodeInfo): Boolean {
        if (node.className?.toString() == WEB_HOST_CLASS) return true
        return role(node) in frameRoles
    }

    /** 남의 문서(iframe)로 들어가는 경계인가 */
    fun isNestedDoc(node: AccessibilityNodeInfo): Boolean = role(node) in nestedDocRoles

    /**
     * 화면 밖으로 스크롤되어 지금은 보이지 않는 노드.
     *
     * **이 값은 String이 아니라 Boolean으로 온다.** `getString`으로 읽으면 Bundle이 내부에서
     * ClassCastException을 만들어 스택 트레이스째 로그에 찍고 null을 돌려준다 — 판정은 늘 false가
     * 되어 조용히 죽고, 웹 노드마다 경고가 쌓여 실측에서 25초에 100만 줄이 넘었다.
     * (덤프 도구가 `offscreen=[true]`로 보여준 것은 값을 toString()한 결과였을 뿐이다.)
     */
    fun isOffscreen(node: AccessibilityNodeInfo): Boolean = try {
        val ex = node.extras
        ex != null && ex.containsKey(OFFSCREEN) && ex.getBoolean(OFFSCREEN, false)
    } catch (e: Throwable) {
        false
    }

    /** 이 노드가 URL을 실어 보낼 수 있는 종류인가 — 아니면 targetUrl을 읽어봐야 헛수고다 */
    fun canBearUrl(node: AccessibilityNodeInfo): Boolean {
        val r = role(node) ?: return false
        return r.lowercase() in urlBearingRoles
    }

    /**
     * 앵커(`<a href>`)인가. 이미지의 `src`와 구분해야 한다.
     * 현재 페이지가 어느 사이트인지 추정할 때 이미지까지 세면 안 된다 — 이미지는 CDN을 가리키고,
     * 그 CDN은 사이트와 다른 도메인인 경우가 많다. 실제로 11번가에서 이미지 CDN(cdn.011st.com)이
     * 링크보다 많아서 페이지 주소가 통째로 잘못 잡혔다.
     */
    fun isLink(node: AccessibilityNodeInfo): Boolean = role(node)?.lowercase() == "link"

    fun targetHost(node: AccessibilityNodeInfo): String? =
        str(node, TARGET_URL)?.let { hostOf(it) }

    // 현재 페이지 주소는 노드에서 직접 얻을 수 없다.
    // `AccessibilityNodeInfo.url` 키를 기대했으나 실측 결과 어느 노드에도 존재하지 않았고
    // (크롬 150, 2026-08-11), 주소창은 스크롤로 툴바가 숨으면 트리에서 아예 사라진다.
    // 그래서 [AdScanner]가 **최상위 문서(iframe 밖)의 링크들이 가리키는 호스트**로 역산한다.

    /**
     * 주소 문자열에서 호스트만 뽑는다.
     *
     * 기존 구현은 `substringBefore('/')`를 썼는데, 주소창이 스킴을 포함한 전체 주소
     * (`https://m.example.com/...`)를 보여주는 순간 호스트가 모든 사이트에서 `https:`로
     * 고정되어 버렸다. 크롬이 비포커스 상태에서 도메인만 보여주는 덕에 우연히 동작했을 뿐이다.
     */
    fun hostOf(raw: String?): String? {
        var s = raw?.trim()?.trimStart('‎', '‏') ?: return null
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = "https://$s"
        val host = try {
            Uri.parse(s).host?.lowercase()
        } catch (e: Throwable) {
            null
        } ?: return null
        return host.ifEmpty { null }
    }

    /**
     * 서브도메인을 떼고 등록 단위 도메인만 남긴다.
     *
     * `m.news.nate.com`과 `m.nate.com`은 같은 사이트인데 접두사만 떼서는 `news.nate.com` ≠ `nate.com`이
     * 되어 다른 사이트로 오해한다. 그래서 마지막 두 조각(`co.kr`류는 세 조각)만 남긴다.
     *
     * 공용 접미사 목록을 통째로 들고 있지는 않으므로 완벽하지 않다. 다만 이 값은
     * "광고인가"를 정하는 근거가 아니라 **내부 링크를 광고로 오인하지 않기 위한 안전장치**로만 쓰므로
     * 틀리는 쪽이 미탐(광고를 놓침)이지 오탐이 아니다.
     */
    fun siteOf(host: String): String {
        val parts = host.lowercase().trim('.').split('.')
        if (parts.size <= 2) return parts.joinToString(".")
        val secondLast = parts[parts.size - 2]
        val take = if (parts.last().length <= 3 && secondLast in twoLevelSuffixes) 3 else 2
        return parts.takeLast(minOf(take, parts.size)).joinToString(".")
    }

    /** 같은 사이트인가 — 광고망 판정에서 내부 링크를 걸러내는 데 쓴다 */
    fun sameSite(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        return siteOf(a) == siteOf(b)
    }
}
