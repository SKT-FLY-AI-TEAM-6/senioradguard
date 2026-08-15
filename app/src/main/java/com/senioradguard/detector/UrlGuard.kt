package com.senioradguard.detector

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.senioradguard.detector.db.AppDatabase

/**
 * 브라우저 주소창을 읽어 알려진 피싱·사기 도메인인지 대조한다.
 *
 * ## 사전 차단이 아니라 도착 직후 경고다
 * 링크를 누르는 순간 막으려면 그 링크의 URL을 알아야 하는데, **접근성 트리에는
 * `href`가 없다.** 노드에 실리는 것은 화면에 보이는 글자와 id뿐이다. 브라우저가
 * 내주는 유일한 주소는 주소창이고 그건 이미 이동한 뒤의 값이다.
 *
 * 그래서 목표를 "막는다"가 아니라 **"도착했음을 즉시 알리고 되돌아가게 돕는다"**로
 * 둔다. 사칭 문자로 사기 사이트에 들어간 어르신은 페이지가 열린 것만으로는 아직
 * 아무 피해도 입지 않았다 — 개인정보를 넣거나 앱을 받기 전에 멈춰 세우면 된다.
 */
class UrlGuard(context: Context) {

    private val dao = AppDatabase.getInstance(context.applicationContext).blacklistDao()

    /** 메모리 캐시. 14만 건을 페이지마다 DB에서 읽으면 수백 ms가 든다. */
    private var domains: Set<String>? = null

    /**
     * 브라우저 화면에서 주소창을 찾아 도메인을 돌려준다.
     *
     * @return 도메인(`example.com`). 브라우저가 아니거나 주소창을 못 찾으면 null.
     */
    fun hostOf(root: AccessibilityNodeInfo): String? {
        val pkg = root.packageName?.toString() ?: return null
        val urlBarId = URL_BAR_IDS[pkg] ?: return null

        val shown = root.findAccessibilityNodeInfosByViewId(urlBarId)
            ?.firstOrNull()
            ?.text?.toString()?.trim()
            .orEmpty()
        if (shown.isEmpty()) return null

        return hostFrom(shown)
    }

    /**
     * 주소창 문자열에서 도메인만 뽑는다.
     *
     * 주소창은 스킴을 감추고 `hankyung.com/article/...`처럼 보여준다. 그대로
     * [Uri.parse]에 넣으면 host가 비고 전체가 path로 잡히므로, 스킴이 없으면
     * 붙여준다. 검색어를 입력 중인 상태처럼 도메인이 아닌 값은 null로 돌린다.
     */
    fun hostFrom(shown: String): String? {
        val withScheme = if (shown.contains("://")) shown else "https://$shown"
        val host = runCatching { Uri.parse(withScheme).host }.getOrNull()?.lowercase()
            ?: return null
        if (!host.contains('.')) return null
        return host.removePrefix("www.")
    }

    /**
     * 차단 목록에 있는가. 서픽스 매칭이라 `ads.example.com`은 `example.com`으로도 걸린다.
     *
     * @return 목록을 아직 못 읽었으면 false — 모르면 막지 않는 쪽이 안전하다.
     */
    suspend fun isBlocked(host: String?): Boolean {
        if (host.isNullOrBlank()) return false

        val loaded = domains ?: runCatching { dao.getAllDomains().toSet() }
            .getOrElse {
                Log.w(TAG, "차단 목록을 읽지 못함: ${it.message}")
                return false
            }.also { domains = it }

        if (loaded.isEmpty()) return false
        return DomainMatcher.isBlocked(host, loaded)
    }

    companion object {
        private const val TAG = "AdGuardUrl"

        /**
         * 브라우저별 주소창 뷰 id.
         *
         * 삼성 인터넷은 **광고 감지 대상이 아니다** — 렌더링된 웹 콘텐츠를 접근성
         * 트리에 전혀 내놓지 않는다. 하지만 주소창은 앱 자신의 위젯이라 정상적으로
         * 읽힌다. 그래서 도메인 대조에는 쓸 수 있다.
         */
        val URL_BAR_IDS = mapOf(
            "com.android.chrome" to "com.android.chrome:id/url_bar",
            "com.sec.android.app.sbrowser" to
                "com.sec.android.app.sbrowser:id/location_bar_edit_text"
        )
    }
}
