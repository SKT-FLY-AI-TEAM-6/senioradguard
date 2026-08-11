package com.senioradguard.notification

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * PendingNotificationQueue
 *
 * 카카오 메시지 전송이 네트워크 오류로 실패했을 때 로컬에 저장해두고,
 * 다음 성공적인 전송 시점(WorkManager 재시도 등)에 다시 보낼 수 있도록 합니다.
 */
object PendingNotificationQueue {

    private const val PREFS_NAME = "adguard_pending_notifications"
    private const val KEY_QUEUE = "queue"

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private lateinit var prefs: android.content.SharedPreferences

    fun enqueue(guardianUuid: String, templateObjectJson: String) {
        if (!::prefs.isInitialized) return

        val queue = readQueue()
        queue.put(JSONObject().apply {
            put("guardianUuid", guardianUuid)
            put("templateObject", templateObjectJson)
        })
        prefs.edit().putString(KEY_QUEUE, queue.toString()).apply()
    }

    fun drain(): List<Pair<String, String>> {
        if (!::prefs.isInitialized) return emptyList()

        val queue = readQueue()
        val result = (0 until queue.length()).map { i ->
            val entry = queue.getJSONObject(i)
            entry.getString("guardianUuid") to entry.getString("templateObject")
        }
        prefs.edit().remove(KEY_QUEUE).apply()
        return result
    }

    private fun readQueue(): JSONArray {
        val raw = prefs.getString(KEY_QUEUE, null) ?: return JSONArray()
        return JSONArray(raw)
    }
}
