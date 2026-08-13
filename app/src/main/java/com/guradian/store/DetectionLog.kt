package com.guradian.store

import android.util.Log
import com.guradian.rule.EscapeReason
import java.security.MessageDigest

/**
 * 감지·대응 이벤트 기록. — 이음매 (task 4·5가 여기에 붙는다)
 *
 * ## 시그니처가 곧 프라이버시 경계다
 * **화면 텍스트 원문과 URL 전문은 이 인터페이스로 들어올 수 없다.** 발표 문서의
 * "보내지 않는다 — 화면 텍스트 원문 · URL 전문"을 문서가 아니라 타입으로 강제한
 * 것이다. 나중에 누가 구현체를 잘못 만들어도 원문이 새어나갈 통로 자체가 없다.
 *
 * 그래서 host는 [hashHost]를 거친 SHA-256만 받는다. 같은 사이트인지 세는 것은
 * 되고, 어느 사이트였는지 되짚는 것은 안 된다.
 *
 * **이 규칙을 깨는 파라미터를 추가하지 말 것.** 원문이 필요해 보이면 그건 집계
 * 방식을 다시 생각해야 한다는 신호다.
 */
interface DetectionLog {

    /**
     * @param source 어느 앱·사이트 종류인지가 아니라 **어느 레이어가 잡았는지**.
     *        "rule" 또는 "agent". 앱 패키지명은 넣지 않는다.
     */
    fun onAdDetected(source: String, count: Int, aiGuessed: Boolean)

    fun onAdClosed(source: String, succeeded: Boolean)

    /** @param hostHash [hashHost]로 만든 SHA-256. 크롬이 아니면 null. */
    fun onEscape(reason: EscapeReason, hostHash: String?)

    companion object {
        /** host를 되짚을 수 없는 형태로 바꾼다. 원문을 넘기기 전에 반드시 통과시킬 것. */
        fun hashHost(host: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(host.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}

/**
 * 지금의 구현 — 로그 한 줄만 남긴다.
 *
 * task 4에서 RoomDetectionLog + 서버 전송 큐로 교체한다. 호출부는 이미 전부
 * 배선돼 있으므로 그때 서비스를 다시 뜯지 않아도 된다.
 *
 * 삼성 One UI가 일반 앱의 Log.d/Log.v를 억제하므로 Log.i를 쓴다.
 */
object NoopDetectionLog : DetectionLog {

    private const val TAG = "GurADian"

    override fun onAdDetected(source: String, count: Int, aiGuessed: Boolean) {
        Log.i(TAG, "detected source=$source count=$count ai=$aiGuessed")
    }

    override fun onAdClosed(source: String, succeeded: Boolean) {
        Log.i(TAG, "closed source=$source ok=$succeeded")
    }

    override fun onEscape(reason: EscapeReason, hostHash: String?) {
        Log.i(TAG, "escape reason=$reason host=${hostHash?.take(8) ?: "-"}")
    }
}
