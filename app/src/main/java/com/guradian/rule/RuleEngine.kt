package com.guradian.rule

import android.view.accessibility.AccessibilityNodeInfo
import com.guradian.store.DetectionLog
import com.guradian.store.EmptyMaliciousUrlSource
import com.guradian.store.MaliciousUrlSource

/** 왜 빠져나가야 하는가. 액션바 문구와 [com.guradian.store.DetectionLog]가 이 값을 쓴다. */
enum class EscapeReason { STORE_REDIRECT, MALICIOUS_URL, INSTALL_TRIGGER }

/** 룰 판정 하나의 결과. */
sealed interface RuleVerdict {
    /** 광고 표기가 확인된 영역들 */
    data class AdRegions(val rects: List<android.graphics.Rect>) : RuleVerdict

    /**
     * 빠져나가야 하는 상황.
     *
     * @param detail 사람이 읽는 근거. 스토어 패키지명이나 눌린 버튼 문구다.
     *        **host 원문은 여기 넣지 않는다** — 로그로 흘러갈 수 있는 값이라,
     *        악성 URL의 경우 host 대신 고정 문구를 쓴다.
     * @param hostHash 크롬에서 온 판정이면 SHA-256. 그 외에는 null.
     *        [com.guradian.store.DetectionLog.onEscape]가 그대로 받는다.
     */
    data class Escape(
        val reason: EscapeReason,
        val detail: String,
        val hostHash: String? = null
    ) : RuleVerdict
}

/**
 * Layer 1의 단일 진입점. — task 1
 *
 * 서비스가 룰을 직접 알지 않게 한다. 원본에서는 서비스가 `AdRegionScanner`와
 * `InstallGuard`를 각각 들고 있었는데, 그러면 룰이 하나 늘 때마다 서비스가 커진다.
 *
 * [browserHost]는 **밖에서 주입해 Layer 2와 같은 인스턴스를 쓴다.** 주소창 접힘
 * 폴백이 상태를 들고 있어서, 인스턴스가 갈라지면 한쪽만 도메인을 기억하게 된다.
 */
class RuleEngine(
    private val scanner: AdRegionScanner = AdRegionScanner(),
    val browserHost: BrowserHost = BrowserHost(),
    private val malicious: MaliciousUrlSource = EmptyMaliciousUrlSource
) {

    /**
     * 광고 영역 스캔.
     *
     * 계획서의 시그니처는 `List<Rect>`였지만 [AdRegionScanner.Result]를 그대로
     * 돌려준다. `truncated`(예산이 모자라 끊김)를 잃으면 [com.guradian.overlay.BorderTracker]의
     * "잘린 결과 홀드"가 성립하지 않기 때문이다 — 빈 목록이 "광고 없음"인지
     * "못 봤음"인지 구분되지 않으면 무거운 페이지에서 테두리가 깜빡인다.
     */
    fun scan(
        root: AccessibilityNodeInfo,
        budgetMs: Long = AdRegionScanner.TIME_BUDGET_MS
    ): AdRegionScanner.Result = scanner.scan(root, budgetMs)

    /** 스토어로 끌려갔는가. 이 판정만 targetApps 필터보다 먼저 돌아야 한다. */
    fun checkPackage(pkg: String): RuleVerdict.Escape? =
        if (EscapeRules.isStorePackage(pkg)) {
            RuleVerdict.Escape(EscapeReason.STORE_REDIRECT, pkg)
        } else {
            null
        }

    /** 방금 누른 버튼이 설치로 이어지는가. */
    fun checkClick(text: String): RuleVerdict.Escape? =
        if (EscapeRules.isInstallTrigger(text)) {
            RuleVerdict.Escape(EscapeReason.INSTALL_TRIGGER, text)
        } else {
            null
        }

    /**
     * 지금 보고 있는 페이지가 악성 URL인가. **크롬에서만 의미가 있다** —
     * 다른 앱에는 주소창이 없어 [BrowserHost.of]가 null을 준다.
     *
     * 지금은 [EmptyMaliciousUrlSource]가 항상 false라 이 경로는 늘 null을 돌려준다.
     * 그래도 호출부를 배선해 두는 이유는, task 4에서 구현체를 끼울 때 서비스를
     * 다시 뜯지 않기 위해서다.
     */
    suspend fun checkUrl(root: AccessibilityNodeInfo): RuleVerdict.Escape? {
        val host = browserHost.of(root) ?: return null
        if (!malicious.isMalicious(host)) return null
        // detail에 host를 넣지 않는다 — 이 값이 로그·화면으로 새어나가면
        // "URL 전문은 보내지 않는다"는 약속이 무너진다. 기록에는 해시만 간다.
        return RuleVerdict.Escape(
            reason = EscapeReason.MALICIOUS_URL,
            detail = "위험한 주소",
            hostHash = DetectionLog.hashHost(host)
        )
    }
}
