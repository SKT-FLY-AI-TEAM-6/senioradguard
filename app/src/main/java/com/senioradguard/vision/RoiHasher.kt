package com.senioradguard.vision

/**
 * 잘라낸 영역의 지문. 같은 배너를 두 번 판별하지 않기 위한 캐시 키다.
 *
 * ## 왜 픽셀 해시인가
 * 광고 영역은 스크롤할 때마다 좌표가 바뀌고, 같은 배너가 화면을 오르내린다.
 * 좌표를 키로 쓰면 캐시가 한 번도 맞지 않아 스크롤 한 번에 판별기 호출이 수십 건
 * 나간다. 반대로 텍스트를 키로 쓰면 **이미지 전용 배너에는 키가 아예 없다** —
 * 그게 이 레이어를 만든 이유이므로 텍스트 키는 쓸 수 없다.
 *
 * ## dHash를 쓰는 이유
 * 평균값 기준(aHash)은 밝기가 조금만 흔들려도 비트가 뒤집힌다. dHash는 **이웃
 * 픽셀끼리의 대소**만 보므로 밝기·대비 변화에 둔감하고, 광고가 애니메이션으로
 * 살짝 밝아졌다 어두워지는 흔한 경우에 같은 지문을 유지한다.
 *
 * 9×8 회색조에서 가로 이웃을 비교해 64비트를 만든다.
 */
object RoiHasher {

    /** 해시를 만들 때 줄이는 크기. 가로가 하나 더 넓은 것은 이웃 비교 때문이다. */
    const val WIDTH = 9
    const val HEIGHT = 8

    /**
     * 이 거리 이내면 같은 영역으로 본다.
     *
     * 0으로 두면 압축 잡음 한 픽셀에도 캐시가 빗나가고, 크게 두면 서로 다른 배너가
     * 한 판정을 공유한다. 64비트 중 6비트(약 9%)까지 허용한다.
     */
    const val SIMILAR_DISTANCE = 6

    /**
     * @param gray 회색조 값 [width]×[height]개. 행 우선(row-major)
     * @return 64비트 지문. 입력 크기가 맞지 않으면 0
     */
    fun dHash(gray: IntArray, width: Int = WIDTH, height: Int = HEIGHT): Long {
        if (width < 2 || height < 1) return 0L
        if (gray.size < width * height) return 0L

        var hash = 0L
        var bit = 0
        for (y in 0 until height) {
            for (x in 0 until width - 1) {
                val left = gray[y * width + x]
                val right = gray[y * width + x + 1]
                if (left > right) hash = hash or (1L shl bit)
                bit++
                if (bit >= 64) return hash
            }
        }
        return hash
    }

    /** 두 지문이 몇 비트나 다른가 (해밍 거리). */
    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** 같은 영역으로 볼 만큼 닮았는가. */
    fun similar(a: Long, b: Long, maxDistance: Int = SIMILAR_DISTANCE): Boolean =
        distance(a, b) <= maxDistance

    /**
     * DB 키 문자열. 출처를 앞에 붙인다 — 같은 그림이라도 어느 앱·사이트에서 나왔는지에
     * 따라 판단이 달라지고, 출처가 섞이면 한쪽의 판정이 다른 쪽을 오염시킨다.
     */
    fun key(sourceKey: String, hash: Long): String = "$sourceKey|${hash.toULong().toString(16)}"
}
