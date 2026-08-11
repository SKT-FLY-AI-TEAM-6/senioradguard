package com.senioradguard.notification

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * KakaoNotifier
 *
 * 광고 감지 시 보호자에게 카카오톡 메시지를 전송합니다.
 *
 * 동작 방식:
 *   1) 초기 설정 시 노인이 카카오 로그인 → 친구 목록에서 보호자 선택
 *   2) 보호자의 UUID 저장 (카카오 친구 고유 ID)
 *   3) 광고 감지 → 카카오 메시지 API 호출 → 보호자 카카오톡에 도착
 *
 * 보호자는 카카오톡만 있으면 됨 (별도 앱 설치 불필요)
 */
class KakaoNotifier(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("adguard_prefs", Context.MODE_PRIVATE)

    // 카카오 메시지 API 엔드포인트
    // 친구에게 메시지 보내기: /v1/api/talk/friends/message/default/send
    private val MESSAGE_API = "https://kapi.kakao.com/v1/api/talk/friends/message/default/send"

    /**
     * 보호자에게 카카오톡 메시지 전송
     *
     * @param eventType 이벤트 종류 ("app_install_blocked", "ad_warning", "store_redirect")
     * @param detail    감지된 내용 (앱명, 버튼 텍스트 등)
     * @param blocked   실제로 차단됐는지 여부
     */
    suspend fun notify(
        eventType: String,
        detail: String,
        blocked: Boolean
    ) = withContext(Dispatchers.IO) {

        val accessToken = prefs.getString("kakao_access_token", null) ?: return@withContext
        val guardianUuid = prefs.getString("guardian_uuid", null) ?: return@withContext

        val message = buildMessage(eventType, detail, blocked)
        sendKakaoMessage(accessToken, guardianUuid, message)
    }

    // ──────────────────────────────────────
    // 메시지 템플릿 구성
    // ──────────────────────────────────────

    private fun buildMessage(
        eventType: String,
        detail: String,
        blocked: Boolean
    ): JSONObject {
        val now = java.text.SimpleDateFormat("MM월 dd일 HH:mm", java.util.Locale.KOREA)
            .format(java.util.Date())

        val (title, description) = when (eventType) {
            "app_install_blocked" ->
                "🚫 앱 설치 시도 차단" to
                "부모님이 광고로 보이는 앱 설치를 시도했어요.\n[$detail]\n자동으로 차단했습니다."

            "store_redirect" ->
                "⚠️ 앱스토어 이동 감지" to
                "부모님 폰이 앱 설치 화면으로 이동했어요.\n광고로 인한 이동일 수 있습니다."

            "ad_warning" ->
                if (blocked) "🛡️ 광고 차단됨" to "부모님이 광고를 누르려 했지만 차단됐어요.\n[$detail]"
                else "👆 광고 클릭 감지" to "부모님이 광고를 클릭했어요.\n[$detail]\n확인이 필요할 수 있습니다."

            else -> "📱 알림" to detail
        }

        // 카카오 피드 메시지 템플릿 (기본 텍스트 타입)
        return JSONObject().apply {
            put("object_type", "text")
            put("text", "[$now]\n$title\n\n$description\n\n─ Senior AdGuard")
            put("link", JSONObject().apply {
                put("web_url", "https://your-app-domain.com/log")  // 차단 내역 웹 링크 (선택)
                put("mobile_web_url", "https://your-app-domain.com/log")
            })
            put("button_title", "상세 내역 보기")
        }
    }

    // ──────────────────────────────────────
    // 카카오 메시지 API 호출
    // ──────────────────────────────────────

    private fun sendKakaoMessage(
        accessToken: String,
        guardianUuid: String,
        templateObject: JSONObject
    ) {
        try {
            val url = URL(MESSAGE_API)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                doOutput = true
            }

            // receiver_uuids: 보호자의 카카오 UUID (친구 목록에서 가져온 값)
            val body = buildString {
                append("receiver_uuids=%5B%22$guardianUuid%22%5D")  // URL encoded ["uuid"]
                append("&template_object=")
                append(java.net.URLEncoder.encode(templateObject.toString(), "UTF-8"))
            }

            connection.outputStream.use { it.write(body.toByteArray()) }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                // 토큰 만료 시 갱신 필요
                if (responseCode == 401) refreshAccessToken()
            }

            connection.disconnect()

        } catch (e: Exception) {
            // 네트워크 오류 시 로컬 큐에 저장 후 재시도
            PendingNotificationQueue.enqueue(guardianUuid, templateObject.toString())
        }
    }

    // ──────────────────────────────────────
    // 토큰 관리
    // ──────────────────────────────────────

    /**
     * 카카오 Access Token 갱신 (만료 시 자동 호출)
     * Refresh Token으로 새 Access Token 발급
     */
    private fun refreshAccessToken() {
        val refreshToken = prefs.getString("kakao_refresh_token", null) ?: return

        try {
            val url = URL("https://kauth.kakao.com/oauth/token")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                doOutput = true
            }

            val body = "grant_type=refresh_token&client_id=${com.senioradguard.BuildConfig.KAKAO_NATIVE_APP_KEY}&refresh_token=$refreshToken"
            connection.outputStream.use { it.write(body.toByteArray()) }

            val response = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(response)

            // 새 토큰 저장
            prefs.edit()
                .putString("kakao_access_token", json.getString("access_token"))
                .apply()

            connection.disconnect()

        } catch (e: Exception) {
            // 갱신 실패 시 사용자에게 재로그인 안내 필요
        }
    }

    // ──────────────────────────────────────
    // 보호자 설정 저장/조회
    // ──────────────────────────────────────

    fun saveGuardian(uuid: String, name: String) {
        prefs.edit()
            .putString("guardian_uuid", uuid)
            .putString("guardian_name", name)
            .apply()
    }

    fun getGuardianName(): String? = prefs.getString("guardian_name", null)

    fun isGuardianSet(): Boolean = prefs.contains("guardian_uuid")
}
