package com.guradian.rule

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 크롬 주소창에서 도메인을 읽는다. — task 1
 *
 * senioradguard `CandidateExtractor.sourceKeyOf()`에서 **로직 그대로** 옮겼다.
 * 원본은 이 판정이 Layer 2 안에 묻혀 있었는데, 악성 URL 판정(task 1)도 같은 값을
 * 필요로 하므로 룰 쪽으로 끌어냈다.
 *
 * 도메인을 쓰는 이유는 두 가지다.
 *  - 캐시 키의 앞부분 — 같은 사이트의 같은 광고가 재방문 때 캐시에 걸린다.
 *    크롬 하나로 묶으면 사이트가 달라도 같은 키를 공유해 판정이 오염된다.
 *  - 악성 URL 조회의 입력.
 *
 * **인스턴스를 공유해서 쓸 것.** [last] 상태가 인스턴스마다 따로 놀면 접힘 폴백이
 * 반쪽만 동작한다.
 */
class BrowserHost {

    /**
     * 마지막으로 주소창에서 읽은 도메인. 주소창이 접혀 사라졌을 때 쓴다.
     * 브라우저가 아닌 앱으로 넘어가면 지운다 — 다른 앱 카드에 남의 도메인이
     * 붙으면 캐시가 뒤섞인다.
     */
    private var last: String? = null

    /** @return 크롬이면 도메인(주소창이 접혔으면 직전 값), 그 외 앱이면 null */
    fun of(root: AccessibilityNodeInfo): String? {
        val pkg = root.packageName?.toString() ?: return null
        if (pkg != CHROME) return resolve(pkg, null)

        val urlBar = root.findAccessibilityNodeInfosByViewId("$CHROME:id/url_bar")?.firstOrNull()
        return resolve(pkg, urlBar?.text?.toString())
    }

    /**
     * 노드 접근을 걷어낸 순수 부분. 단위 테스트가 여기를 덮는다.
     *
     * 크롬은 아래로 스크롤하면 주소창을 접어 트리에서 사라지게 한다. 그때
     * null로 물러나면 같은 페이지가 스크롤 위치에 따라 두 개의 캐시 키를 갖게 되고,
     * 게다가 모든 사이트가 패키지명 키를 공유해 서로의 판정에 오염된다.
     * 마지막으로 본 도메인을 기억해 그 구멍을 메운다.
     */
    internal fun resolve(pkg: String, shown: String?): String? {
        if (pkg != CHROME) {
            last = null
            return null
        }

        val text = shown?.trim().orEmpty()
        if (text.isEmpty()) return last

        // 주소창은 "hankyung.com/article/..." 처럼 스킴 없이 보여준다
        val host = text.removePrefix("https://").removePrefix("http://")
            .substringBefore('/')
            .removePrefix("www.")
        if (!host.contains('.')) return last

        last = host
        return host
    }

    companion object {
        const val CHROME = "com.android.chrome"
    }
}
