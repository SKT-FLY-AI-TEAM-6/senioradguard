package com.senioradguard.remote

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
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
 *
 * **익명 인증을 먼저 거친다.** 보안 규칙이 `auth != null`을 요구하기 때문이다.
 * 인증이 없으면 서버가 요청자를 구분할 수 없어, 규칙을 아무리 써도 curl 한 줄로
 * DB 전체를 읽고 지울 수 있다(개발 중 실제로 그랬다). 로그인 화면은 없다 —
 * 익명 인증은 기기마다 계정을 자동으로 만들어 주므로 어르신이 할 일이 없다.
 *
 * 연결 코드는 여전히 ANDROID_ID다. auth.uid는 28자라 보호자가 손으로 옮겨
 * 적기 어렵다. 대신 `users/{userId}/owner`에 auth.uid를 심어, 규칙이 "이
 * 코드의 주인만 쓸 수 있다"를 강제한다.
 */
object FirebaseRepo {

    private const val TAG = "AdGuardFirebase"
    private const val PREFS = "adguard_prefs"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_ROLE = "role"
    private const val KEY_LINKED_TO = "linked_to"

    private var db: DatabaseReference? = null
    private var userId: String = ""

    /** 익명 인증이 끝났는가. 규칙이 auth를 요구하므로 이전에는 어떤 쓰기도 통과 못 한다. */
    private var authed = false

    /**
     * 인증 전에 발생한 기록. 앱이 뜨자마자 광고를 잡으면 인증(보통 1초 이내)보다
     * 빨라, 버리면 첫 감지가 통째로 사라진다. 인증이 끝나면 순서대로 흘려보낸다.
     * 인증이 끝내 실패하면 이 목록도 의미가 없으므로 상한을 둔다.
     */
    private const val PENDING_CAP = 20
    private val pending = ArrayDeque<Map<String, Any>>()

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

        if (db != null) signIn()
    }

    /**
     * 익명 로그인. 기기마다 계정이 하나 생기고 재실행해도 유지된다.
     *
     * 실패하면 원격 기능만 죽고 광고 감지는 그대로 돈다. 콘솔에서 Anonymous
     * 제공업체를 켜지 않으면 CONFIGURATION_NOT_FOUND로 떨어지므로 로그를 남긴다.
     */
    private fun signIn() {
        val auth = FirebaseAuth.getInstance()
        auth.currentUser?.let { onSignedIn(it.uid); return }

        auth.signInAnonymously()
            .addOnSuccessListener { result ->
                onSignedIn(result.user?.uid ?: return@addOnSuccessListener)
            }
            .addOnFailureListener {
                Log.e(TAG, "익명 인증 실패 — 원격 기록 비활성화: ${it.message}")
                pending.clear()
            }
    }

    private fun onSignedIn(uid: String) {
        authed = true
        // 이 코드의 주인이 누구인지 서버에 심는다. 규칙이 이 값으로 쓰기를 막는다.
        db?.child("users")?.child(userId)?.child("owner")?.setValue(uid)
        Log.i(TAG, "익명 인증 완료 — 연결 코드=$userId")

        while (pending.isNotEmpty()) writeEvent(pending.removeFirst())
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

        val node = db?.child("users")?.child(userId) ?: return
        // owner는 남긴다. 이 코드가 누구 것인지에 대한 주장이라 역할과 무관하고,
        // 지우면 규칙상 다른 기기가 같은 코드를 가로챌 수 있다.
        node.child("role").removeValue()
        node.child("linkedTo").removeValue()
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
        if (db == null) return
        val event = mapOf(
            "timestamp" to System.currentTimeMillis(),
            "appPackage" to appPackage,
            "adText" to adText,
            "action" to action,
            "layer" to layer
        )

        if (!authed) {
            if (pending.size >= PENDING_CAP) pending.removeFirst()
            pending.addLast(event)
            return
        }
        writeEvent(event)
    }

    private fun writeEvent(event: Map<String, Any>) {
        val events = db?.child("events")?.child(userId) ?: return
        val id = events.push().key ?: return
        events.child(id).setValue(event)
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
