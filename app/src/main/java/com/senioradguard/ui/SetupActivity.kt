package com.senioradguard.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.kakao.sdk.auth.model.OAuthToken
import com.kakao.sdk.common.model.ClientError
import com.kakao.sdk.common.model.ClientErrorCause
// import com.kakao.sdk.friend.client.selectFriend
// import com.kakao.sdk.friend.core.PickerClient
// import com.kakao.sdk.friend.core.model.OpenPickerFriendRequestParams
// import com.kakao.sdk.friend.core.model.SelectParams
// import com.kakao.sdk.friend.core.model.SelectionMode
// import com.kakao.sdk.friend.core.model.ViewType
import com.kakao.sdk.user.UserApiClient
import com.senioradguard.notification.KakaoNotifier

/**
 * SetupActivity
 *
 * 앱 최초 실행 시 1회 진행하는 설정 화면.
 * 노인이 직접 하기 어려우므로 보호자(자녀)가 함께 진행하도록 안내.
 *
 * 순서:
 *   Step 1) 카카오 로그인 (노인 계정으로)
 *   Step 2) 보호자 정보 입력 — 원래는 친구 목록 피커였으나, 비즈 앱 전환/검수 승인 전이라
 *           임시로 UUID/이름을 직접 입력받는 화면으로 대체 (아래 TODO 참고)
 *   Step 3) 입력된 보호자 UUID 저장 → 완료
 */
class SetupActivity : ComponentActivity() {

    private lateinit var notifier: KakaoNotifier

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notifier = KakaoNotifier(this)

        // 이미 설정된 경우 스킵
        if (notifier.isGuardianSet()) {
            finish()
            return
        }

        startKakaoLogin()
    }

    // ── Step 1: 카카오 로그인 ──────────────────

    private fun startKakaoLogin() {
        val callback: (OAuthToken?, Throwable?) -> Unit = { token, error ->
            if (error != null) {
                showError("카카오 로그인에 실패했습니다.\n다시 시도해주세요.")
            } else if (token != null) {
                saveTokens(token)
                showManualGuardianInput()  // Step 2로 이동 (임시: 친구 피커 대신 수동 입력)
            }
        }

        // 카카오톡 설치 여부에 따라 분기
        if (UserApiClient.instance.isKakaoTalkLoginAvailable(this)) {
            UserApiClient.instance.loginWithKakaoTalk(this) { token, error ->
                if (error != null) {
                    // 카카오톡 로그인 실패 → 계정 로그인으로 폴백
                    if (error is ClientError && error.reason == ClientErrorCause.Cancelled) return@loginWithKakaoTalk
                    UserApiClient.instance.loginWithKakaoAccount(this, callback = callback)
                } else if (token != null) {
                    saveTokens(token)
                    showManualGuardianInput()  // 임시: 친구 피커 대신 수동 입력
                }
            }
        } else {
            UserApiClient.instance.loginWithKakaoAccount(this, callback = callback)
        }
    }

    private fun saveTokens(token: OAuthToken) {
        getSharedPreferences("adguard_prefs", MODE_PRIVATE).edit()
            .putString("kakao_access_token", token.accessToken)
            .putString("kakao_refresh_token", token.refreshToken)
            .apply()
    }

    // ── Step 2 (임시): 보호자 정보 수동 입력 ──────────
    //
    // TODO: 비즈 앱 전환 검수 승인 후 아래 openFriendPicker()로 교체 예정.
    // 친구 피커(PickerClient)는 검수 미승인 상태라 팀 멤버가 아닌 계정에서는
    // "talk user is null" 등으로 실패함 — 승인 전까지는 보호자 UUID/이름을
    // 직접 입력받는 이 화면으로 대체한다.

    private fun showManualGuardianInput() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 144, 48, 48)
        }

        val title = TextView(this).apply {
            text = "보호자 정보 입력 (임시)"
            textSize = 22f
            setPadding(0, 0, 0, 40)
        }

        val uuidLabel = TextView(this).apply {
            text = "보호자 카카오 UUID"
            textSize = 16f
        }
        val uuidInput = EditText(this).apply {
            hint = "보호자 카카오 UUID를 입력하세요"
        }

        val nameLabel = TextView(this).apply {
            text = "보호자 이름"
            textSize = 16f
            setPadding(0, 40, 0, 0)
        }
        val nameInput = EditText(this).apply {
            hint = "보호자 이름을 입력하세요"
        }

        val confirmBtn = Button(this).apply {
            text = "확인"
            setPadding(0, 56, 0, 0)
            setOnClickListener {
                val uuid = uuidInput.text.toString().trim()
                val name = nameInput.text.toString().trim()
                if (uuid.isEmpty() || name.isEmpty()) {
                    showError("보호자 카카오 UUID와 이름을 모두 입력해주세요.")
                    return@setOnClickListener
                }
                notifier.saveGuardian(uuid, name)
                showSuccess(name)
            }
        }

        root.addView(title)
        root.addView(uuidLabel)
        root.addView(uuidInput)
        root.addView(nameLabel)
        root.addView(nameInput)
        root.addView(confirmBtn)

        setContentView(root)
    }

    // ── Step 2 (원래 구현, 검수 승인 후 복원 예정) ──────────
    // 카카오 정책상 친구 API/피커는 비즈 앱 전환 + 사용 신청(검수) 승인이 필요해
    // 당장은 사용할 수 없으므로 주석 처리만 해두고 삭제하지 않는다.
    //
    // private fun openFriendPicker() {
    //     // 카카오 제공 친구 선택 UI — 별도 구현 불필요
    //     val params = OpenPickerFriendRequestParams(
    //         title = "알림 받을 가족을 선택해주세요",
    //         selectParams = SelectParams.friend(SelectionMode.SINGLE, 1, 1)  // 보호자 1명만
    //     )
    //     PickerClient.instance.selectFriend(
    //         context = this,
    //         params = params,
    //         viewType = ViewType.FULL
    //     ) { selectedUsers, error ->
    //         if (error != null || selectedUsers == null) {
    //             android.util.Log.e("SeniorAdGuard", "friend picker failed", error)
    //             showError("보호자 선택에 실패했습니다.\n${error?.message}")
    //             return@selectFriend
    //         }
    //
    //         val guardian = selectedUsers.users?.firstOrNull() ?: return@selectFriend
    //         val uuid = guardian.uuid ?: return@selectFriend
    //         val name = guardian.profileNickname ?: "보호자"
    //
    //         // Step 3: 저장 완료
    //         notifier.saveGuardian(uuid, name)
    //         showSuccess(name)
    //     }
    // }

    // ── UI 피드백 ──────────────────────────────

    private fun showError(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun showSuccess(guardianName: String) {
        android.widget.Toast.makeText(
            this,
            "$guardianName 님에게 알림이 전송됩니다.\n설정 완료!",
            android.widget.Toast.LENGTH_LONG
        ).show()
        finish()
    }
}
