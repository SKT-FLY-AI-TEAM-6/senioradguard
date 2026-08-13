package com.guradian.action

import android.os.Handler
import android.os.Looper

/**
 * 전환 탈출 — 끌려간 화면에서 원래 자리로 돌아온다. — task 3
 *
 * 원본 `OverlayManager.showWarning`에 묻혀 있던 2단계 폴백만 떼어냈다. 판정은
 * [com.guradian.rule.EscapeRules](task 1), 경고 UI는 액션바(task 2)가 맡고
 * 여기 남은 것은 **탈출 동작 그 자체**뿐이다.
 *
 * ## 2단계 폴백을 빼면 스토어에 갇힌다
 * BACK 한 번으로 안 되는 화면이 실제로 있다. 광고가 새 태스크로 스토어를 열었거나
 * 스토어가 백스택의 뿌리인 경우 `GLOBAL_ACTION_BACK`이 아무 일도 하지 않는다.
 * 그래서 [HOME_FALLBACK_DELAY_MS] 뒤에 최상단 패키지가 그대로면 홈으로 보낸다.
 * **이 폴백을 빠뜨리면 회귀다.**
 *
 * @param onBack       1단계 — performGlobalAction(GLOBAL_ACTION_BACK)
 * @param onForceHome  2단계 — performGlobalAction(GLOBAL_ACTION_HOME)
 * @param currentForegroundPackage 지금 최상단 패키지. BACK이 먹었는지 판단에 쓴다
 */
class EscapeAction(
    private val onBack: () -> Unit,
    private val onForceHome: () -> Unit,
    private val currentForegroundPackage: () -> String?
) {

    private val handler = Handler(Looper.getMainLooper())
    private var pendingFallback: Runnable? = null

    /**
     * @param trappedIn 빠져나가려는 패키지. 이게 그대로면 BACK이 안 먹은 것이다.
     * @param onSettled 탈출이 끝났을 때. 액션바가 확장을 되돌리는 데 쓴다.
     */
    fun perform(trappedIn: String?, onSettled: () -> Unit = {}) {
        cancel()
        onBack()

        val runnable = Runnable {
            pendingFallback = null
            // trappedIn이 null이면 비교할 기준이 없다. 그때는 홈으로 보내지 않는다 —
            // 멀쩡히 돌아온 사용자를 홈으로 쫓아내는 쪽이 더 나쁘다.
            if (trappedIn != null && currentForegroundPackage() == trappedIn) {
                onForceHome()
            }
            onSettled()
        }
        pendingFallback = runnable
        handler.postDelayed(runnable, HOME_FALLBACK_DELAY_MS)
    }

    /** 예약된 2단계를 취소한다. 사용자가 스스로 빠져나왔을 때. */
    fun cancel() {
        pendingFallback?.let { handler.removeCallbacks(it) }
        pendingFallback = null
    }

    private companion object {
        /**
         * BACK이 먹었는지 판단하기까지 기다리는 시간. 원본 값 그대로다.
         * 더 짧으면 화면 전환 애니메이션이 끝나기 전에 판단해 멀쩡한 BACK을
         * 실패로 보고 홈으로 보낸다.
         */
        const val HOME_FALLBACK_DELAY_MS = 1500L
    }
}
