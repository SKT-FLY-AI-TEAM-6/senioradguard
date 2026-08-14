package com.senioradguard.remote

import kotlin.random.Random

/**
 * 보호자가 어르신에게 불러줄 6자리 초대 코드.
 *
 * 예전 연결 코드는 ANDROID_ID 16자리 16진수였다. 전화로 불러주기엔 너무 길고,
 * `0`과 `O`, `b`와 `6`을 헷갈려 잘못 적는 일이 잦다. 숫자 6자리면 전화번호처럼
 * 불러줄 수 있다.
 *
 * 6자리 숫자는 100만 가지다. 코드가 겹칠 수 있지만 초대는 한 번 쓰고 마는 값이라
 * 문제가 되지 않는다 — 겹치면 그 코드가 새 가족을 가리킬 뿐, 앞선 가족이
 * 사라지지는 않는다.
 */
object InviteCode {

    const val LENGTH = 6

    fun generate(random: Random = Random.Default): String =
        (1..LENGTH).map { random.nextInt(10) }.joinToString("")

    /** 사용자가 입력한 값을 코드로 쓸 수 있게 다듬는다. 공백·하이픈을 걷어낸다. */
    fun normalize(input: String): String =
        input.filter { it.isDigit() }

    fun isValid(code: String): Boolean =
        code.length == LENGTH && code.all { it.isDigit() }
}
