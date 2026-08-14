package com.senioradguard.detector

import android.content.Context
import android.util.Log

/**
 * 지금 보고 있는 페이지의 도메인이 차단 목록에 있는지 확인한다.
 *
 * ## 왜 "클릭한 링크"가 아니라 "지금 있는 페이지"인가
 * 원래 설계는 링크를 누르는 순간 그 URL을 검사하는 것이었다. **접근성 API로는
 * 불가능하다.** 노드에는 화면에 보이는 텍스트와 id만 있고 `href`는 실리지 않는다.
 * 크롬이 노출하는 유일한 주소는 주소창(`url_bar`)이고, 그건 이미 이동한 뒤의 값이다.
 *
 * 그래서 "누르기 전 차단"이 아니라 **"도착 직후 경고"**가 된다. 페이지는 이미 열렸지만
 * 개인정보를 넣거나 앱을 설치하기 전에 멈춰 세울 수 있으므로, 노인 보호라는 목적에는
 * 여전히 의미가 있다. 이 한계는 코드로 좁힐 수 없으니 문구로 정직하게 알린다.
 *
 * ## 조회 비용
 * 14만 개 도메인을 매번 DB에서 읽으면 페이지가 바뀔 때마다 수백 ms가 든다.
 * [BlacklistCache]가 메모리에 한 번만 올리고, 워커가 목록을 갱신할 때 무효화한다.
 * 실제 대조는 [DomainMatcher]의 접미사 분해라 집합 조회 몇 번으로 끝난다.
 */
class UrlGuard(context: Context) {

    private val repository = BlacklistRepository(context.applicationContext)

    /**
     * @param host 주소창에서 읽은 도메인 (`hankyung.com`). 앱 패키지명이 들어오면
     *             점이 없으므로 자연히 걸리지 않는다.
     * @return 차단 목록에 있으면 true. 목록을 아직 못 받았으면 항상 false —
     *         모르면 막지 않는 쪽이 안전하다.
     */
    suspend fun isBlocked(host: String?): Boolean {
        if (host.isNullOrBlank() || !host.contains('.')) return false

        val domains = runCatching { BlacklistCache.domains { repository.getDomains() } }
            .getOrElse {
                Log.w(TAG, "차단 목록을 읽지 못함: ${it.message}")
                return false
            }
        if (domains.isEmpty()) return false

        return DomainMatcher.isBlocked(host, domains)
    }

    private companion object {
        const val TAG = "AdGuardUrl"
    }
}
