package com.senioradguard.url

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 접근성 노드에서 광고가 데려갈 주소를 찾아낸다.
 *
 * ## 안드로이드에서 링크 주소를 얻는 것은 생각보다 어렵다
 * `AccessibilityNodeInfo`에는 href에 해당하는 표준 필드가 없다. 그래서 세 경로를
 * 순서대로 시도하고, 앞이 실패하면 뒤로 물러난다.
 *
 *  1. **크롬 extras** — 크로미움은 링크 노드의 extras에 [EXTRA_TARGET_URL] 키로
 *     대상 주소를 넣는다(`WebContentsAccessibilityImpl`). 있으면 이게 가장 정확하고
 *     **누르기 전에** 알 수 있는 유일한 경로다. 다만 크롬 버전과 노드 종류에 따라
 *     비어 있는 경우가 있어 이것만 믿을 수는 없다.
 *  2. **화면에 드러난 주소** — 광고 카드가 문구나 contentDescription에 도메인을
 *     그대로 노출하는 경우가 많다("스폰서 · example.com").
 *  3. **누른 뒤 주소창** — 위 둘이 다 비면 사용자가 광고를 누른 다음 크롬 주소창이
 *     바뀌는 것을 보고 착지 주소를 읽는다([currentPageUrl]). 사후 확인이라 이동을
 *     막지는 못하지만 **반드시 동작하는 경로**이고, 위험한 곳에 도착했을 때
 *     "뒤로 가기"를 권하는 데는 충분하다. 이 순서 설계의 핵심이 여기다 —
 *     사전 경로는 있으면 좋은 것이고, 사후 경로가 기능의 바닥을 받친다.
 *
 * 네이티브 앱(유튜브·인스타 등)은 1·2가 거의 통하지 않는다. 앱 안 광고는 눌렀을 때
 * 크롬이나 커스텀탭이 뜨므로 3번 경로로 잡힌다.
 */
object LinkHarvester {

    /** 크로미움이 링크 대상 주소를 넣는 extras 키. */
    const val EXTRA_TARGET_URL = "AccessibilityNodeInfo.targetUrl"

    private const val CHROME_URL_BAR = "com.android.chrome:id/url_bar"

    private const val MAX_DEPTH = 40

    /** 한 번의 수집에서 볼 노드 수 상한. 노드 하나가 IPC 한 번이다. */
    private const val MAX_NODES = 1200

    /** 링크 문구로 쓸 최대 길이. */
    private const val MAX_ANCHOR_CHARS = 120

    /**
     * 눌러도 어디로 가지 않는 파일들.
     *
     * 실기기 확인(2026-08-14, SM-S937N, 연합뉴스)에서 크롬이 **이미지 노드에도**
     * [EXTRA_TARGET_URL]을 채워 준다는 것이 드러났다. 그대로 두면 img2·img4·
     * img5…처럼 이미지 CDN 호스트마다 판별기 호출이 한 건씩 나간다 — 한 페이지에
     * 6건이 그렇게 낭비됐다. 클릭 목적지가 아닌 것은 여기서 끊는다.
     */
    private val ASSET_EXTENSIONS = setOf(
        ".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg", ".bmp", ".ico",
        ".css", ".js", ".woff", ".woff2", ".ttf", ".mp4", ".webm", ".mp3"
    )

    private val EXPLICIT_URL = Regex("""https?://[^\s"'<>()\[\]]+""", RegexOption.IGNORE_CASE)

    /**
     * 스킴 없이 노출된 도메인. 마지막 라벨이 [KNOWN_TLDS]에 있을 때만 인정한다 —
     * 그러지 않으면 "index.html", "사진.jpg", "1.5"까지 주소로 읽힌다.
     */
    private val BARE_HOST = Regex(
        """(?:[a-z0-9][a-z0-9-]{0,62}\.)+([a-z]{2,24})(?:/[^\s"'<>()\[\]]*)?""",
        RegexOption.IGNORE_CASE
    )

    private val KNOWN_TLDS = setOf(
        "com", "net", "org", "kr", "io", "me", "tv", "cc", "co", "info", "biz", "app",
        "dev", "shop", "store", "site", "online", "live", "xyz", "top", "link", "click",
        "club", "news", "today", "jp", "cn", "uk", "us", "de", "fr", "ru", "in", "au",
        "ca", "br", "it", "es", "nl", "se", "pl", "tw", "hk", "sg", "my", "vn", "th",
        "ph", "id", "gg", "pw", "buzz", "icu", "work"
    )

    /** 크롬 주소창의 현재 주소. 주소창이 접혀 사라졌으면 null. */
    fun currentPageUrl(root: AccessibilityNodeInfo): String? =
        runCatching {
            root.findAccessibilityNodeInfosByViewId(CHROME_URL_BAR)
                ?.firstOrNull()
                ?.text?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()

    /**
     * 노드 하나가 가리키는 주소. 못 찾으면 null.
     *
     * extras를 먼저 보고, 없으면 노드에 드러난 글자에서 찾는다.
     * 이미지·스크립트처럼 눌러도 이동하지 않는 주소는 걸러낸다.
     */
    fun urlOf(node: AccessibilityNodeInfo): String? {
        val found = runCatching { node.extras?.getCharSequence(EXTRA_TARGET_URL)?.toString() }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: urlInText("${node.text ?: ""} ${node.contentDescription ?: ""}")

        return found?.takeUnless { isStaticAsset(it) }
    }

    /** 눌러도 이동하지 않는 주소인가 (이미지·스타일시트·미디어 등). */
    fun isStaticAsset(url: String): Boolean {
        val path = url.substringAfter("://", url)
            .substringBefore('?')
            .substringBefore('#')
            .lowercase()
        return ASSET_EXTENSIONS.any { path.endsWith(it) }
    }

    /**
     * 글자 안에 섞인 주소를 뽑는다. 안드로이드 의존이 없어 단위 테스트로 덮는다.
     *
     * 스킴이 붙은 주소를 우선한다 — 본문에 도메인이 여러 개 보일 때 "https://"가
     * 붙은 쪽이 실제 링크일 가능성이 훨씬 높다.
     */
    fun urlInText(text: String): String? {
        EXPLICIT_URL.find(text)?.let { return it.value.trimEnd('.', ',', ')') }

        val bare = BARE_HOST.find(text) ?: return null
        val tld = bare.groupValues[1].lowercase()
        if (tld !in KNOWN_TLDS) return null
        return bare.value.trimEnd('.', ',', ')')
    }

    /**
     * 광고로 표시된 영역 안에서 링크를 모은다. 누르기 전에 미리 위험도를 보려는 경로다.
     *
     * @param regions Layer 1·2가 광고로 표시한 영역. 이 안의 링크만 본다 —
     *                화면의 모든 링크를 판별하면 비용도, 오탐도 감당이 안 된다
     * @param maxLinks 상한. 한 화면의 광고는 많아야 대여섯 개다
     */
    fun harvest(
        root: AccessibilityNodeInfo,
        regions: List<Rect>,
        sourcePageUrl: String,
        maxLinks: Int = 5
    ): List<AdLink> {
        if (regions.isEmpty()) return emptyList()

        val out = LinkedHashMap<String, AdLink>()
        val budget = intArrayOf(MAX_NODES)
        collect(root, 0, regions, sourcePageUrl, maxLinks, budget, out)
        return out.values.toList()
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        depth: Int,
        regions: List<Rect>,
        sourcePageUrl: String,
        maxLinks: Int,
        budget: IntArray,
        out: MutableMap<String, AdLink>
    ) {
        if (depth > MAX_DEPTH || out.size >= maxLinks || budget[0] <= 0) return
        budget[0]--

        val bounds = Rect().also { node.getBoundsInScreen(it) }
        // 이 가지가 광고 영역과 아예 겹치지 않으면 더 내려갈 필요가 없다
        if (bounds.width() > 0 && bounds.height() > 0 &&
            regions.none { Rect.intersects(it, bounds) }
        ) return

        urlOf(node)?.let { raw ->
            val link = UrlParser.parse(
                raw = raw,
                sourcePageUrl = sourcePageUrl,
                anchorText = anchorTextOf(node),
                isAdElement = true
            )
            // 같은 호스트가 카드마다 반복되므로 호스트 단위로 접는다
            if (link != null) out.putIfAbsent(link.cacheKey, link)
        }

        for (i in 0 until node.childCount) {
            collect(node.getChild(i) ?: continue, depth + 1, regions, sourcePageUrl, maxLinks, budget, out)
            if (out.size >= maxLinks || budget[0] <= 0) return
        }
    }

    private fun anchorTextOf(node: AccessibilityNodeInfo): String =
        "${node.text ?: ""} ${node.contentDescription ?: ""}"
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(MAX_ANCHOR_CHARS)
}
