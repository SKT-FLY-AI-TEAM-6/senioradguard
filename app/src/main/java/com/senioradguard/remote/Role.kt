package com.senioradguard.remote

/** 앱 사용자의 역할. 어르신 폰은 감지하고, 보호자 폰은 그 기록을 본다. */
enum class Role {
    SENIOR, GUARDIAN;

    val wire: String get() = if (this == SENIOR) "senior" else "guardian"

    companion object {
        fun of(wire: String?): Role? = when (wire) {
            "senior" -> SENIOR
            "guardian" -> GUARDIAN
            else -> null
        }
    }
}
