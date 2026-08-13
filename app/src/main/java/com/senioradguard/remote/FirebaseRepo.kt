package com.senioradguard.remote

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/** 앱 사용자의 역할. */
enum class Role { SENIOR, GUARDIAN;

    val wire: String get() = if (this == SENIOR) "senior" else "guardian"

    companion object {
        fun of(wire: String?): Role? = when (wire) {
            "senior" -> SENIOR
            "guardian" -> GUARDIAN
            else -> null
        }
    }
}

/** 보호자 화면에 뿌릴 차단 내역 한 건. */
data class AdEvent(
    val eventId: String = "",
    val timestamp: Long = 0L,
    val appPackage: String = "",
    /** 마스킹을 거친 광고 문구 */
    val adText: String = "",
    /** blocked / warned / ignored */
    val action: String = "",
    /** 1 | 2 | 3 */
    val layer: Int = 0
)

/**
 * Firebase Realtime Database 접근을 한곳에 모은다.
 *
 * ```
 * users/{userId}      role("senior"|"guardian"), linkedTo(partnerId)
 * events/{userId}/{eventId}  timestamp, appPackage, adText, action, layer
 * settings/{userId}   sensitivity(0.6), whitelist([])
 * ```
 *
 * **google-services.json이 없어도 앱은 그대로 돌아가야 한다.** 파일이 없으면
 * Firebase가 초기화되지 않으므로 여기서 그 상태를 감지해 모든 호출을 조용히
 * 무시한다. 광고 감지(Layer 1·2·3)는 원격 기록과 무관하게 동작하므로, 설정이
 * 안 된 기기에서도 보호 기능 자체는 유지된다.
 */
object FirebaseRepo {

    private const val TAG = "AdGuardFirebase"
    private const val PREFS = "adguard_prefs"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_ROLE = "role"
    private const val KEY_LINKED_TO = "linked_to"

    private var db: DatabaseReference? = null
    private var userId: String = ""

    /** Firebase가 실제로 쓸 수 있는 상태인가. */
    val isConfigured: Boolean get() = db != null

    fun init(context: Context) {
        val app = context.applicationContext
        userId = deviceUserId(app)

        db = runCatching {
            // google-services.json이 없으면 자동 초기화가 일어나지 않아 비어 있다
            if (FirebaseApp.getApps(app).isEmpty()) {
                Log.i(TAG, "google-services.json 없음 — 원격 기록 비활성화")
                return@runCatching null
            }
            FirebaseDatabase.getInstance().reference
        }.getOrElse {
            Log.e(TAG, "Firebase 초기화 실패: ${it.message}")
            null
        }
    }

    /**
     * 기기별 고유 ID. 로그인이 없으므로 ANDROID_ID를 쓴다.
     * 초기화 실패 시에도 로컬 역할 저장은 되어야 하므로 prefs에 캐시한다.
     */
    private fun deviceUserId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_USER_ID, null)?.let { return it }

        @Suppress("HardwareIds")
        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: System.currentTimeMillis().toString()
        prefs.edit().putString(KEY_USER_ID, id).apply()
        return id
    }

    fun currentUserId(): String = userId

    // ── 역할 ────────────────────────────────────────────────

    fun savedRole(context: Context): Role? =
        Role.of(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ROLE, null)
        )

    /** 역할을 로컬에 저장하고, 가능하면 원격에도 올린다. */
    fun setRole(context: Context, role: Role) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ROLE, role.wire).apply()

        val ref = db ?: return
        ref.child("users").child(userId).child("role").setValue(role.wire)
    }

    /**
     * 역할을 지운다. 테스트나 재설정 때 선택 화면으로 되돌리기 위해 쓴다.
     *
     * 원격도 함께 지운다. 로컬만 지우면 서버에는 옛 역할과 옛 연결이 남는다.
     * 지금은 아무도 그 값을 읽지 않아 티가 나지 않지만, 서버에서 연결 관계를
     * 쓰기 시작하면 이미 끊긴 보호자가 계속 연결된 것으로 보인다.
     */
    fun clearRole(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_ROLE).remove(KEY_LINKED_TO).apply()

        val ref = db ?: return
        ref.child("users").child(userId).removeValue()
    }

    fun linkedTo(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LINKED_TO, null)

    /**
     * 보호자–어르신 연결. 보호자가 어르신의 코드를 입력해 부른다.
     * 빈 문자열을 넘기면 연결 해제로 보고 양쪽에서 지운다 — 빈 값을 그대로
     * 올리면 "빈 상대와 연결됨"이라는 상태가 서버에 남는다.
     */
    fun link(context: Context, partnerId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ref = db
        val node = ref?.child("users")?.child(userId)?.child("linkedTo")

        if (partnerId.isBlank()) {
            prefs.edit().remove(KEY_LINKED_TO).apply()
            node?.removeValue()
            return
        }

        prefs.edit().putString(KEY_LINKED_TO, partnerId).apply()
        node?.setValue(partnerId)
    }

    // ── 이벤트 ──────────────────────────────────────────────

    /**
     * 차단 내역 1건 기록. 어르신 모드에서 호출한다.
     * Firebase가 없으면 아무 일도 하지 않는다.
     */
    fun logEvent(appPackage: String, adText: String, action: String, layer: Int) {
        val ref = db ?: return
        val events = ref.child("events").child(userId)
        val id = events.push().key ?: return
        events.child(id).setValue(
            mapOf(
                "timestamp" to System.currentTimeMillis(),
                "appPackage" to appPackage,
                "adText" to adText,
                "action" to action,
                "layer" to layer
            )
        )
    }

    /**
     * 연결된 어르신의 차단 내역을 실시간 구독한다. 보호자 모드에서 쓴다.
     * @return 구독 해제 함수. Firebase가 없으면 아무것도 하지 않는 함수를 돌려준다.
     */
    fun observeEvents(partnerId: String, onChange: (List<AdEvent>) -> Unit): () -> Unit {
        val ref = db ?: return {}
        val node = ref.child("events").child(partnerId)

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val events = snapshot.children.mapNotNull { child ->
                    runCatching {
                        AdEvent(
                            eventId = child.key.orEmpty(),
                            timestamp = child.child("timestamp").getValue(Long::class.java) ?: 0L,
                            appPackage = child.child("appPackage").getValue(String::class.java).orEmpty(),
                            adText = child.child("adText").getValue(String::class.java).orEmpty(),
                            action = child.child("action").getValue(String::class.java).orEmpty(),
                            layer = child.child("layer").getValue(Int::class.java) ?: 0
                        )
                    }.getOrNull()
                }.sortedByDescending { it.timestamp }
                onChange(events)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "이벤트 구독 취소: ${error.message}")
            }
        }

        node.addValueEventListener(listener)
        return { node.removeEventListener(listener) }
    }

    // ── 설정 ────────────────────────────────────────────────

    /** 판별 임계값·화이트리스트. 없으면 기본값을 올려둔다. */
    fun ensureSettings(sensitivity: Float = 0.6f) {
        val ref = db ?: return
        ref.child("settings").child(userId).setValue(
            mapOf(
                "sensitivity" to sensitivity,
                "whitelist" to emptyList<String>()
            )
        )
    }
}
