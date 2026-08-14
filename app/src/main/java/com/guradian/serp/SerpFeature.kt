package com.guradian.serp

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope

/**
 * 검색 결과 위험도 기능 전체의 **단 하나의 입구.**
 *
 * ## 왜 이 클래스가 있는가 — 병합을 위해서다
 * 이 기능은 다른 팀원이 만드는 task(광고 감지·광고 닫기·전환 탈출)와 별개다.
 * 그런데 안드로이드 접근성 서비스는 앱에 **하나뿐**이라, 모든 task가 같은 파일
 * (`GuardianAccessibilityService`) 한 곳으로 이벤트를 받는다. 각 task가 그 파일에
 * 필드와 분기를 흩뿌리면 병합할 때마다 같은 자리에서 충돌한다.
 *
 * 그래서 이 기능이 서비스에 요구하는 것을 **여섯 줄로 줄였다.**
 *
 * ```kotlin
 * // 1) 필드 하나
 * private val serp by lazy { SerpFeature(this, scope) }
 *
 * // 2) 이벤트 필터에 우리 앱을 더한다 (구글 앱이 targetApps에 없기 때문)
 * packageNames = (targetApps + storePackages + SerpFeature.PACKAGES).toTypedArray()
 *
 * // 3) dispatch에서 한 줄 — targetApps 필터보다 **앞에**
 * serp.onEvent(event, pkg)
 *
 * // 4) 정리 — onInterrupt()와 onDestroy()에 각각
 * serp.stop()
 * ```
 *
 * 이 여섯 줄 밖에서는 `com.guradian.serp` 패키지가 서비스를 전혀 모른다. 패키지를
 * 통째로 복사해 다른 저장소에 옮겨도 그대로 컴파일된다 — 실제로 **이 패키지는
 * 자기 밖의 어떤 클래스도 import하지 않는다** (안드로이드 SDK와 코루틴 제외).
 *
 * ## 이 기능이 건드리지 않는 것
 * 광고 감지의 오버레이(`AdBorderOverlay`)·액션바·룰 엔진·판정 캐시를 일절 쓰지
 * 않는다. 배지는 **자기 창**에 그리므로 광고 테두리와 서로를 지우지 않는다
 * (실기기에서 두 표시가 같은 화면에 공존하는 것을 확인했다).
 *
 * @param service 접근성 서비스 본체. 창을 붙이고([SerpBadgeOverlay]) 화면 루트를
 *        읽는 데 쓴다. **서비스 컨텍스트여야 한다** — applicationContext로는
 *        오버레이 창에 토큰이 없어 `BadTokenException`으로 죽는다
 * @param scope 서비스의 코루틴 스코프. 서비스가 죽을 때 함께 취소되도록 빌려 쓴다
 * @param apiKey Gemini 키. 비어 있으면 규칙만으로 동작한다
 */
class SerpFeature(
    private val service: AccessibilityService,
    scope: CoroutineScope,
    apiKey: String = ""
) {

    companion object {
        /**
         * 이 기능이 이벤트를 받아야 하는 앱들.
         *
         * **`targetApps`와 일부러 다르다.** 어르신이 구글 검색을 쓰는 경로는 크롬만이
         * 아니고 구글 앱·홈 위젯이 오히려 흔한데, 구글 앱은 광고 감지의 대상이 아니라
         * `targetApps`에 없다. 서비스의 `packageNames`에 이 목록을 더해야 구글 앱
         * 이벤트가 서비스까지 도달한다 — 안 더하면 그 앱에서 기능이 통째로 잠자코 있다
         * (실기기에서 그렇게 조용했다).
         */
        val PACKAGES = setOf("com.android.chrome", SerpScanner.GOOGLE_APP)
    }

    private val scanner = SerpScanner()

    private val overlay = SerpBadgeOverlay(service)

    private val engine: SerpRiskEngine = run {
        // 키가 없으면 규칙만으로 돈다. 규칙은 네트워크를 쓰지 않으므로 누누티비류
        // (이름만으로 확정)는 그 상태에서도 잡힌다. 다만 처음 보는 사이트는
        // '확인 안 됨'으로 남아 아무 표시도 하지 않는다 — 모르는 것을 안전하다고
        // 말하지 않기 위해서다.
        val classifier: SerpClassifier =
            if (apiKey.isNotBlank()) GeminiSerpClassifier(apiKey) else RuleOnlyClassifier
        Log.i(SERP_TAG, "serp 판별기=${classifier.source}")
        SerpRiskEngine(classifier)
    }

    private val tracker = SerpTracker(scanner, engine, overlay, scope)

    /**
     * 화면이 꺼지면 배지를 즉시 걷는다.
     *
     * 재확인 타이머([SerpTracker.RECHECK_MS])만으로도 결국 지워지지만, 그 1초 사이에
     * 사용자가 화면을 켜면 **잠금화면 위에 정체불명의 빨간 테두리가 떠 있다.**
     * 실기기에서 실제로 그렇게 남았다. 잠금화면은 접근성 이벤트로 알 수 없어
     * (우리 `packageNames`에 없다) 브로드캐스트가 이 구멍을 메우는 유일한 경로다.
     */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = tracker.clear()
    }

    init {
        runCatching { service.registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF)) }
    }

    /**
     * 이벤트 하나를 받는다. **서비스의 `targetApps` 필터보다 앞에서 부를 것** —
     * 구글 앱은 그 목록에 없어서, 뒤에 두면 이벤트가 여기까지 오지 않는다.
     *
     * 관심 없는 앱이면 아무 일도 하지 않는다.
     */
    fun onEvent(event: AccessibilityEvent, packageName: String) {
        if (packageName !in PACKAGES) return

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            tracker.onScroll(scrollDeltaY(event))
        }
        tracker.onEvent(event) { searchRoot() }
    }

    /** 서비스가 멈추거나 죽을 때. 창을 떼지 않으면 배지가 화면에 남는다. */
    fun stop() {
        tracker.clear()
        runCatching { service.unregisterReceiver(screenOff) }
    }

    /** 검색을 하는 앱일 때만 루트를 준다. 아니면 null — 트래커가 그걸 보고 배지를 지운다. */
    private fun searchRoot(): AccessibilityNodeInfo? =
        service.rootInActiveWindow?.takeIf { it.packageName?.toString() in PACKAGES }

    /**
     * 이 스크롤로 화면이 세로로 몇 px 움직였는가. 노드를 하나도 읽지 않는다.
     *
     * 값을 채우지 않는 뷰는 UNDEFINED(-1)를 그대로 준다. 1px 스크롤은 눈에 보이지도
     * 않으므로 둘을 구분하지 않고 함께 버린다. 화면 높이를 넘는 값은 페이지 점프이거나
     * 쓰레기값이니 밀지 않고 스캔에 맡긴다.
     *
     * 서비스에도 같은 함수가 있지만 가져다 쓰지 않는다 — 그 한 줄 때문에 이 패키지가
     * 서비스 없이는 컴파일되지 않게 되고, 그게 정확히 이 클래스가 없애려는 결합이다.
     */
    private fun scrollDeltaY(event: AccessibilityEvent): Int {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return 0
        val dy = event.scrollDeltaY
        if (dy > -2 && dy < 2) return 0
        val limit = service.resources.displayMetrics.heightPixels
        if (dy > limit || dy < -limit) return 0
        return dy
    }
}
