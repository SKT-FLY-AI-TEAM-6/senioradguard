package com.guradian.action

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 광고를 닫는 버튼(✕·건너뛰기)을 찾는다. — task 2
 *
 * ## 정책 — 이 클래스에서 가장 중요한 부분
 * **`ACTION_CLICK` 대리 실행은 사용자가 [광고 닫기]를 누른 경우에만 한다.**
 * 자동으로 닫으면 그건 광고 차단이고 구글 정책 위반이다. 우리가 하는 일은
 * "닫기 버튼이 저기 있는데 너무 작아서 못 누르는 사람 대신 눌러주는 것"이지
 * "광고를 없애는 것"이 아니다.
 *
 * 그래서 **호출부는 액션바의 클릭 핸들러 하나뿐이어야 한다.** 스캔 결과나
 * 이벤트 루프에서 이 함수를 부르는 코드가 생기면 그 순간 성격이 바뀐다.
 *
 * ## 지금은 인터페이스와 배선까지다
 * 휴리스틱 튜닝(앱별 문구·좌표 임계값)은 실기기 관찰이 필요해 별도 계획으로 뺐다.
 * 아래 신호 순서는 원본 `AdDetector.adLabelPackages`에서 확인된 문자열과
 * 일반적인 닫기 버튼 관례를 합친 출발점이다.
 */
class CloseAffordanceFinder {

    /**
     * 신호 (1) — 문구. **유튜브 문구는 실기기에서 확인된 것이다.**
     * "5초 후 건너뛸 수 있습니다"는 아직 못 누르는 상태라 여기 없다 —
     * 그걸 누르면 아무 일도 안 일어나고 사용자는 고장으로 읽는다.
     */
    private val closeTexts = setOf(
        "닫기", "close", "건너뛰기", "광고 건너뛰기", "skip", "skip ad", "skip ads",
        "✕", "×", "x", "⨯", "✖"
    )

    /** 아직 누를 수 없는 상태. 이게 보이면 "잠시 후 다시" 안내가 맞다. */
    private val notYetTexts = listOf("초 후 건너뛸", "후에 건너뛸", "skip in")

    /** 신호 (2) — viewIdResourceName 토큰. */
    private val closeIdTokens = setOf("close", "dismiss", "skip", "btn_close", "close_button")

    /** 결과. 못 찾은 이유를 구분해야 안내 문구가 달라진다. */
    sealed interface Result {
        data class Found(val node: AccessibilityNodeInfo) : Result

        /** 카운트다운 중이라 아직 못 누른다 */
        data object NotYet : Result

        /** 닫기 버튼 자체가 없다 */
        data object NotFound : Result
    }

    /**
     * @param root   현재 화면의 루트
     * @param adRect 닫으려는 광고 영역. 우상단 [CORNER_DP] 반경을 우선으로 본다
     */
    fun find(root: AccessibilityNodeInfo, adRect: Rect, density: Float): Result {
        val corner = Rect(
            adRect.right - (CORNER_DP * density).toInt(),
            adRect.top,
            adRect.right,
            adRect.top + (CORNER_DP * density).toInt()
        )
        val maxSide = (MAX_SIDE_DP * density).toInt()

        var notYet = false
        val candidates = mutableListOf<Scored>()

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_DEPTH) return

            val label = "${node.text ?: ""} ${node.contentDescription ?: ""}"
                .trim().lowercase()
            if (notYetTexts.any { it in label }) notYet = true

            val bounds = Rect().also { node.getBoundsInScreen(it) }
            if (Rect.intersects(bounds, adRect) || bounds.contains(adRect)) {
                val byText = label.split(Regex("\\s+")).any { it in closeTexts } ||
                    label in closeTexts
                val byId = node.viewIdResourceName?.lowercase()
                    ?.split(Regex("[^a-z0-9]+"))
                    ?.any { it in closeIdTokens } == true
                val small = bounds.width() in 1..maxSide && bounds.height() in 1..maxSide
                val inCorner = Rect.intersects(bounds, corner)

                if (byText || byId || (small && inCorner)) {
                    // 신호 순서대로 가중치. 문구가 가장 믿을 만하고 위치가 가장 약하다.
                    val score = (if (byText) 4 else 0) + (if (byId) 2 else 0) +
                        (if (small && inCorner) 1 else 0)
                    clickableOf(node)?.let { candidates.add(Scored(it, score)) }
                }
            }

            for (i in 0 until node.childCount) walk(node.getChild(i) ?: continue, depth + 1)
        }

        walk(root, 0)

        candidates.maxByOrNull { it.score }?.let { return Result.Found(it.node) }
        return if (notYet) Result.NotYet else Result.NotFound
    }

    private class Scored(val node: AccessibilityNodeInfo, val score: Int)

    /**
     * 라벨을 단 노드 자신은 대개 클릭을 안 받는다(TextView·ImageView). 실제로
     * 클릭을 받는 것은 그걸 감싼 버튼이라 조상을 몇 단계 올라간다.
     *
     * 상한을 두는 이유: 계속 올라가면 결국 광고 카드 전체나 화면 루트가 클릭
     * 가능해서, "닫기"를 눌렀는데 **광고가 열리는** 최악의 결과가 나온다.
     */
    private fun clickableOf(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var up = 0
        while (cur != null && up <= MAX_CLIMB) {
            if (cur.isClickable) return cur
            cur = cur.parent
            up++
        }
        return null
    }

    private companion object {
        /** 광고 우상단에서 닫기 버튼을 찾을 반경 */
        const val CORNER_DP = 48

        /** 닫기 버튼이라기엔 너무 큰 것을 거른다 */
        const val MAX_SIDE_DP = 48

        /** 라벨 노드에서 클릭 가능한 조상까지 올라갈 단계 수 */
        const val MAX_CLIMB = 5

        const val MAX_DEPTH = 60
    }
}
