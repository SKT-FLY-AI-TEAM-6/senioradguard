package com.senioradguard.logger

import android.content.Context
import com.senioradguard.agent.CardText
import com.senioradguard.remote.FirebaseRepo

/**
 * 차단 내역을 남긴다. 보호자 모드가 이 기록을 실시간으로 구독해 보여준다.
 *
 * 전송 경로가 카카오 메시지 API에서 Firebase Realtime Database로 바뀌었다.
 * 카카오는 비즈 앱 검수 승인이 나지 않아 호출이 계속 거부됐고, 승인 여부와
 * 무관하게 동작하는 경로가 필요했다.
 *
 * **문구는 반드시 마스킹해서 올린다.** 어르신 화면에 뜬 글자가 그대로 원격에
 * 남으면 안 된다. CardText.mask가 전화·카드·주민번호 패턴을 지운다.
 */
object AdEventLogger {

    /** 광고 문구를 원격에 남길 때의 길이 상한. */
    private const val MAX_TEXT = 120

    fun init(context: Context) {
        FirebaseRepo.init(context)
    }

    /** Layer 1·2가 광고를 표시했을 때. */
    fun logAdMarked(packageName: String, adText: String, layer: Int) {
        record(packageName, adText, action = "warned", layer = layer)
    }

    /** Layer 3 — 스토어 강제 이동 감지. */
    fun logStoreRedirect(storePackage: String) {
        record(storePackage, "앱 설치 화면으로 이동", action = "warned", layer = 3)
    }

    /** Layer 3 — 설치 유도 버튼 클릭에 경고를 띄웠을 때. */
    fun logInstallBlocked(buttonText: String) {
        record("", buttonText, action = "blocked", layer = 3)
    }

    /** Layer 3 — 사용자가 경고를 무시하고 "그냥 보기"를 골랐을 때. */
    fun logIgnored(packageName: String, detail: String) {
        record(packageName, detail, action = "ignored", layer = 3)
    }

    private fun record(packageName: String, text: String, action: String, layer: Int) {
        FirebaseRepo.logEvent(
            appPackage = packageName,
            adText = CardText.mask(text).take(MAX_TEXT),
            action = action,
            layer = layer
        )
    }
}
