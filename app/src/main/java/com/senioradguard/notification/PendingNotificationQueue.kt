package com.senioradguard.notification

// ⚠️ 카카오 연동은 전면 보류되어 아래 구현 전체를 주석 처리했다. (삭제하지 않음)
//
// 이유: 카카오 비즈 앱 검수 승인이 나지 않아 메시지 API가 계속 거부됐고,
// 보호자 알림은 Firebase Realtime Database 실시간 구독으로 대체했다
// (remote/FirebaseRepo.kt, ui/GuardianActivity.kt).
//
// 되살리려면 build.gradle.kts에 카카오 SDK 의존성을, AndroidManifest에
// AuthCodeHandlerActivity와 <queries>를 다시 넣어야 한다.
//
// 줄 단위(//)로 주석 처리한 이유: 블록 주석으로 감쌌더니 안쪽 KDoc의 */와
// 짝이 어긋나 "Unclosed comment"로 빌드가 깨졌다.

//
// import android.content.Context
// import org.json.JSONArray
// import org.json.JSONObject
//
// /**
//  * PendingNotificationQueue
//  *
//  * 카카오 메시지 전송이 네트워크 오류로 실패했을 때 로컬에 저장해두고,
//  * 다음 성공적인 전송 시점(WorkManager 재시도 등)에 다시 보낼 수 있도록 합니다.
//  */
// object PendingNotificationQueue {
//
//     private const val PREFS_NAME = "adguard_pending_notifications"
//     private const val KEY_QUEUE = "queue"
//
//     fun init(context: Context) {
//         prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
//     }
//
//     private lateinit var prefs: android.content.SharedPreferences
//
//     fun enqueue(guardianUuid: String, templateObjectJson: String) {
//         if (!::prefs.isInitialized) return
//
//         val queue = readQueue()
//         queue.put(JSONObject().apply {
//             put("guardianUuid", guardianUuid)
//             put("templateObject", templateObjectJson)
//         })
//         prefs.edit().putString(KEY_QUEUE, queue.toString()).apply()
//     }
//
//     fun drain(): List<Pair<String, String>> {
//         if (!::prefs.isInitialized) return emptyList()
//
//         val queue = readQueue()
//         val result = (0 until queue.length()).map { i ->
//             val entry = queue.getJSONObject(i)
//             entry.getString("guardianUuid") to entry.getString("templateObject")
//         }
//         prefs.edit().remove(KEY_QUEUE).apply()
//         return result
//     }
//
//     private fun readQueue(): JSONArray {
//         val raw = prefs.getString(KEY_QUEUE, null) ?: return JSONArray()
//         return JSONArray(raw)
//     }
// }
//
