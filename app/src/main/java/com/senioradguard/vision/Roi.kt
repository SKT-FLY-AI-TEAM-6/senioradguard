package com.senioradguard.vision

import android.graphics.Rect

/**
 * 판별할 화면 영역의 종류.
 *
 * 같은 이미지라도 무엇으로 보느냐에 따라 판단이 달라진다. 광고 배너의 "무료"는
 * 유인 문구지만, 검색 결과의 "무료 다시보기"는 불법 사이트의 간판이다.
 */
enum class RoiKind(val label: String) {
    /** Layer 1·2가 광고로 표시한 영역 */
    AD("광고"),

    /** 검색 결과 한 칸. 공식 OTT와 불법 사이트가 나란히 나오는 그 목록 */
    SEARCH_RESULT("검색 결과")
}

/**
 * 판별 대상 영역 하나 — Region Of Interest.
 *
 * 화면 전체가 아니라 **이 사각형만** 잘라서 판별기에 보낸다. 전체를 보내면
 * 카톡 대화·사진·계좌번호가 그대로 따라나가고, 그건 되돌릴 수 없다.
 *
 * @param shownText 접근성 트리에서 읽어낸 글자. 있으면 이미지와 함께 넘겨 판단을
 *                  받치고, 없으면 빈 문자열이다(이미지 전용 배너)
 * @param shownUrl  화면이나 extras에서 얻은 주소. 있으면 불법 목록을 **공짜로**
 *                  먼저 조회할 수 있다 — 검색 결과에서 특히 잘 나온다
 */
data class Roi(
    val rect: Rect,
    val kind: RoiKind,
    val shownText: String,
    val shownUrl: String,
    val sourceKey: String
)
