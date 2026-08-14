package com.senioradguard.guard

import android.util.Log
import com.senioradguard.region.WebNode

/**
 * 광고가 사용자를 다른 사이트나 다른 앱으로 강제로 끌고 갔을 때 "뒤로 가기"를 띄운다.
 *
 * 이전 구현은 크롬의 주소창 뷰 id(`url_bar`)와 탭 목록 id(`tab_list_recycler`, `hub_toolbar`)를
 * 직접 찾았다. 그래서 다른 브라우저에서는 주소를 읽지 못해 **오류도 없이 조용히 아무 일도 하지 않았다.**
 * 여기서는 브라우저 UI를 전혀 보지 않고, 스캐너가 웹 문서 뿌리에서 읽어온 주소만 받는다.
 *
 * 주소가 바뀌는 것은 강제 이동 말고도 세 가지가 있어 그것들을 걸러내야 한다.
 *  - 같은 사이트 안에서 기사를 눌러 이동 → 호스트가 그대로라 애초에 안 걸린다
 *  - 되돌아가는 중 → 거쳐 온 사이트를 기억해 두고 직전 사이트로 돌아가면 알리지 않는다
 *    (안 그러면 뒤로 가기를 누른 그 페이지에서 버튼이 또 떠서 무한히 반복된다)
 *  - 사용자가 탭 목록에서 다른 창을 고름 → 그 창의 주소는 원래 다른 사이트다
 */
class NavigationGuard {

    private companion object {
        const val TAG = "NavGuard"
        const val PROMPT_MS = 12_000L
        /** 웹이 안 보이던 화면(탭 목록·새 탭)을 거친 직후의 주소 변화는 탭 전환으로 본다 */
        const val TAB_SWITCH_WINDOW_MS = 5_000L
        /**
         * 그 "웹이 안 보이던 구간"이 이만큼은 이어져야 탭 목록으로 인정한다.
         *
         * 웹이 잠깐 사라지는 것만으로는 탭 전환과 **그냥 페이지 이동**이 구분되지 않는다.
         * 크롬은 같은 탭에서 다음 페이지로 넘어갈 때도 접근성 트리를 통째로 비운다.
         * 실측(2026-08-13): 연합뉴스TV → hear.com 광고 이동의 공백이 약 600ms,
         * 쿠팡앱으로 넘어갈 때가 390ms였다. 그걸 탭 전환으로 읽어서 **강제 이동이
         * 안내되지 않았고**, 게다가 siteStack이 초기화되어 되돌아 나올 때 거꾸로 안내가 떴다.
         *
         * 탭 목록은 사용자가 눈으로 훑고 고르는 화면이라 훨씬 오래 머문다.
         */
        const val TAB_SWITCH_MIN_BLANK_MS = 1_500L
        const val STACK_LIMIT = 10
        /**
         * "방금까지 웹을 보고 있었다"로 인정하는 시간.
         *
         * 직전 스캔 한 번만 보면 안 된다 — **크롬은 앱을 넘기기 전에 접근성 트리에서
         * 웹 콘텐츠를 먼저 걷어낸다.** 실측(2026-08-13, 연합뉴스TV → 쿠팡앱, 3회):
         * `웹이 사라짐`이 찍히고 191·273·200ms 뒤에 패키지가 바뀌었다. 그 빈 스캔을
         * 물어버려서 강제 이동이 세 번 다 조용히 무시됐다.
         */
        const val WEB_MEMORY_MS = 5_000L
        /**
         * "아직 한 번도 없었다"를 0으로 두면 안 된다. 시각은 부팅 이후 경과 시간이라
         * 부팅 직후 5초 동안은 `now - 0 < 5초`가 참이 되어, 그 사이의 강제 이동이
         * 전부 탭 전환으로 오인된다.
         */
        const val NEVER = Long.MIN_VALUE
    }

    private val siteStack = mutableListOf<String>()
    /**
     * 앱 사이를 오간 자취. 사이트 기록([siteStack])과 **같은 이유로** 필요하다.
     *
     * 넘어간 앱에 웹이 없을 때만 알리던 동안에는 되돌아 나오는 길이 저절로 걸러졌다
     * (쇼핑앱 → 브라우저는 웹이 보이니 조건에서 탈락). 그 조건을 걷어낸 지금은
     * 나오는 길도 "앱이 바뀌었고 웹에서 왔다"에 그대로 걸려 안내가 다시 뜬다.
     * 거쳐 온 앱을 기억해 두고 되돌아가는 것이면 알리지 않는다.
     */
    private val appStack = mutableListOf<String>()
    private var lastSite: String? = null

    // 원본은 스캔 스레드 전용이었지만 GuArDian에서는 dismiss()가 메인 스레드
    // (가림막 결말·버튼)에서도 불린다 — 늦게 보이는 것을 막는 최소한의 장치.
    @Volatile
    private var promptUntil = 0L
    /** 지금 이어지고 있는 "웹이 안 보이는" 구간의 시작. NEVER면 웹이 보이는 중 */
    private var blankStart = NEVER
    /** 그 구간에서 마지막으로 웹이 안 보인다고 확인한 시각 */
    private var blankLast = NEVER
    /** 방금 끝난 공백 구간이 끝난 시각과 길이 */
    private var blankEndAt = NEVER
    private var blankMs = 0L
    private var prevPkg: String? = null
    /** 마지막으로 웹 콘텐츠를 본 시각. [WEB_MEMORY_MS]에 왜 시각이어야 하는지 적어 뒀다 */
    private var lastWebAt = NEVER

    /**
     * 스캔은 200ms마다 도는데 대부분은 아무것도 안 바뀐다. 같은 줄을 계속 찍으면
     * 정작 봐야 할 전환이 묻히므로, 상태가 실제로 달라졌을 때만 남긴다.
     */
    private var lastLog = ""

    private fun trace(what: String) {
        if (what == lastLog) return
        lastLog = what
        Log.i(TAG, what)
    }

    /**
     * 안내가 "앱에서 빠져나가기"인지(광고가 다른 앱을 열었다) "페이지 되돌아가기"인지.
     * 판단은 스캔 스레드에서, 읽기는 버튼을 누른 메인 스레드에서 일어난다.
     */
    @Volatile
    var leavesApp = false
        private set

    /**
     * 스캔 한 번마다 부른다.
     * @return "뒤로 가기" 안내를 지금 띄워야 하면 true
     */
    fun update(pkg: String, pageHost: String?, sawWeb: Boolean, now: Long): Boolean {
        val appChanged = pkg != prevPkg
        // **이번 스캔의 sawWeb을 반영하기 전에** 읽어야 한다. 안 그러면 넘어간 앱이
        // 스스로 웹을 보여주는 것만으로 "웹에서 왔다"가 되어 버린다.
        val cameFromWeb = lastWebAt != NEVER && now - lastWebAt < WEB_MEMORY_MS

        if (appChanged) {
            siteStack.clear()
            lastSite = null
            blankStart = NEVER
            blankEndAt = NEVER

            // 자취가 비어 있으면 떠나온 앱부터 바닥에 깔아 둔다 (되돌아올 자리)
            if (appStack.isEmpty()) prevPkg?.let { appStack.add(it) }
            // 맨 위(지금 떠나는 앱)를 뺀 어딘가에 있으면 되돌아가는 길이다.
            // 한 칸 아래만 보지 않는 이유: 앱이 스스로 닫히며 두 칸을 건너뛰고 돌아오기도 한다.
            var backTo = -1
            for (i in 0 until appStack.size - 1) if (appStack[i] == pkg) backTo = i

            when {
                backTo >= 0 -> {
                    while (appStack.size > backTo + 1) appStack.removeAt(appStack.size - 1)
                    promptUntil = 0
                    trace("app $prevPkg -> $pkg : goingBack stack=$appStack")
                }
                // 광고가 브라우저에서 곧바로 다른 앱을 열어버린 경우(쇼핑앱 등).
                // 홈 화면·최근 앱·알림창을 거쳐 사용자가 직접 연 것은 그 화면들이
                // AdRules.isIgnoredPackage에 걸려 [reset]으로 기록이 지워지므로
                // cameFromWeb이 false가 되어 여기 오지 않는다.
                //
                // 넘어간 앱에 웹이 없을 것(!sawWeb)을 조건에 두었었는데 그게 오히려
                // **노려야 할 대상을 걸러냈다.** 쿠팡·11번가 같은 쇼핑앱은 껍데기만
                // 네이티브고 내용은 WebView라, 앱이 이미 떠 있는 상태로 복귀하면
                // 첫 스캔부터 웹이 보인다. 그래서 콜드 스타트(스플래시가 먼저 뜬다)인
                // 첫 진입만 안내가 뜨고 두 번째부터는 조용히 실패했다.
                cameFromWeb && !isLauncher(pkg) -> {
                    appStack.add(pkg)
                    leavesApp = true
                    promptUntil = now + PROMPT_MS
                    trace("app $prevPkg -> $pkg : PROMPT (앱으로 강제 이동) stack=$appStack")
                }
                else -> {
                    appStack.add(pkg)
                    promptUntil = 0
                    trace("app $prevPkg -> $pkg : no prompt (cameFromWeb=$cameFromWeb)")
                }
            }
            if (appStack.size > STACK_LIMIT) appStack.removeAt(0)
        } else {
            // 웹이 안 보이는 구간의 **길이**를 잰다. 시작 시각만으로는 부족하다 —
            // 탭 목록을 30초 들여다보다 다른 탭을 고르면 그 30초가 흘러 창(5초)을 넘겨 버리고,
            // 반대로 끝난 시각만 보면 200ms짜리 페이지 이동과 구분되지 않는다.
            if (!sawWeb) {
                if (blankStart == NEVER) blankStart = now
                blankLast = now
            } else if (blankStart != NEVER) {
                blankMs = blankLast - blankStart
                blankEndAt = blankLast
                blankStart = NEVER
                trace("$pkg: 웹이 ${blankMs}ms 동안 안 보였다")
            }

            val site = pageHost?.let { WebNode.siteOf(it) }
            if (sawWeb && site != null && site != lastSite) {
                val goingBack = siteStack.size >= 2 && siteStack[siteStack.size - 2] == site
                // 공백이 **직후**이면서 **충분히 길었을** 때만 탭 전환으로 본다
                val sinceBlank = if (blankEndAt == NEVER) Long.MAX_VALUE else now - blankEndAt
                val tabSwitch =
                    sinceBlank < TAB_SWITCH_WINDOW_MS && blankMs >= TAB_SWITCH_MIN_BLANK_MS
                val why = when {
                    goingBack -> "goingBack"
                    tabSwitch -> "tabSwitch(공백 ${blankMs}ms, ${sinceBlank}ms 전)"
                    lastSite != null -> "PROMPT (사이트 강제 이동, 공백 ${blankMs}ms)"
                    else -> "첫 사이트"
                }
                trace("site $lastSite -> $site : $why stack=$siteStack")
                when {
                    goingBack -> siteStack.removeAt(siteStack.size - 1)
                    tabSwitch -> { siteStack.clear(); siteStack.add(site) }
                    lastSite != null -> {
                        siteStack.add(site)
                        leavesApp = false
                        promptUntil = now + PROMPT_MS
                    }
                    else -> siteStack.add(site)
                }
                if (siteStack.size > STACK_LIMIT) siteStack.removeAt(0)
                lastSite = site
                // 다 쓴 표시는 지운다. 안 지우면 한 번 탭 목록을 거친 뒤로는 **그 뒤의 이동이
                // 전부 탭 전환으로 먹힌다** — 5초 안에 다음 사이트로 넘어가는 한 계속 그렇다.
                blankEndAt = NEVER
            }
        }

        prevPkg = pkg
        if (sawWeb) lastWebAt = now
        return now < promptUntil
    }

    /** 감지 대상이 아닌 화면으로 나갔을 때 기록을 정리한다 */
    fun reset() {
        if (prevPkg != null) trace("reset() — 기록 삭제 (직전 $prevPkg)")
        siteStack.clear()
        appStack.clear()
        lastSite = null
        promptUntil = 0
        blankStart = NEVER
        blankEndAt = NEVER
        prevPkg = null
        // 홈·최근 앱·알림창을 거쳐 사용자가 직접 연 앱은 강제 이동이 아니다.
        // 그 화면들이 여기로 오므로, 웹을 봤다는 기억도 같이 끊는다.
        lastWebAt = NEVER
    }

    /** 사용자가 "그냥 두기"를 눌렀거나 안내를 실행했을 때 */
    fun dismiss() {
        promptUntil = 0
    }

    private fun isLauncher(pkg: String) =
        pkg.contains("launcher") || pkg.contains("home") || pkg == "android"
}
