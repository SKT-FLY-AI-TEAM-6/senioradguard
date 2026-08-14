package com.senioradguard.vision

import kotlin.math.max
import kotlin.math.min

/**
 * 두 사각형이 "같은 영역"인지 판정한다.
 *
 * ## 왜 필요한가
 * 판별이 끝나면 그 영역의 테두리를 위험도 색으로 **바꿔** 그려야 한다. 그런데
 * 판정이 붙은 사각형과, 그 뒤 스캔이 새로 찾아낸 사각형은 좌표가 정확히 같지 않다 —
 * 스크롤이 몇 픽셀 밀렸거나 광고가 리사이즈됐다. 정확히 일치할 때만 바꾸면 색이
 * 거의 안 바뀌고, 겹치기만 하면 바꾸면 옆 카드의 색까지 가져간다.
 *
 * 겹친 넓이 비율(IoU)로 본다. 안드로이드 의존이 없도록 정수만 받는다 —
 * `Rect`의 메서드를 부르면 JVM 단위 테스트에서 못 돌린다.
 */
object RegionMatcher {

    /** 이 비율 이상 겹치면 같은 영역으로 본다. */
    const val SAME_REGION_IOU = 0.6f

    /**
     * 교집합 넓이 ÷ 합집합 넓이. 0~1.
     * 한쪽이 다른 쪽을 완전히 품고 있어도 크기가 많이 다르면 낮게 나온다.
     */
    fun iou(
        aLeft: Int, aTop: Int, aRight: Int, aBottom: Int,
        bLeft: Int, bTop: Int, bRight: Int, bBottom: Int
    ): Float {
        val aArea = area(aLeft, aTop, aRight, aBottom)
        val bArea = area(bLeft, bTop, bRight, bBottom)
        if (aArea <= 0L || bArea <= 0L) return 0f

        val interWidth = min(aRight, bRight) - max(aLeft, bLeft)
        val interHeight = min(aBottom, bBottom) - max(aTop, bTop)
        if (interWidth <= 0 || interHeight <= 0) return 0f

        val inter = interWidth.toLong() * interHeight.toLong()
        val union = aArea + bArea - inter
        if (union <= 0L) return 0f
        return inter.toFloat() / union.toFloat()
    }

    fun same(
        aLeft: Int, aTop: Int, aRight: Int, aBottom: Int,
        bLeft: Int, bTop: Int, bRight: Int, bBottom: Int,
        threshold: Float = SAME_REGION_IOU
    ): Boolean =
        iou(aLeft, aTop, aRight, aBottom, bLeft, bTop, bRight, bBottom) >= threshold

    private fun area(left: Int, top: Int, right: Int, bottom: Int): Long {
        val w = (right - left).toLong()
        val h = (bottom - top).toLong()
        return if (w <= 0L || h <= 0L) 0L else w * h
    }
}
