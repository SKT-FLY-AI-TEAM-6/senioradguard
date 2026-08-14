package com.senioradguard.remote

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.senioradguard.risk.ProtectionLevel
import com.senioradguard.risk.RiskLevel
import kotlinx.coroutines.tasks.await

/** 보호자 화면에 뿌릴 차단 내역 한 건. 화면에 뜬 글자는 담지 않는다. */
data class FamilyEvent(
    val eventId: String = "",
    val timestamp: Long = 0L,
    val packageName: String = "",
    val type: String = "",
    val riskLevel: String = "",
    val blocked: Boolean = false,
    val count: Int = 0
)

/**
 * 가족 단위 Firestore 저장소.
 *
 * ```
 * families/{familyId}
 *   members/{uid}   role, fcmToken, deviceName
 *   settings/{uid}  protectionLevel, whitelist[]
 *   events/{id}     timestamp, riskLevel, type, blocked, packageName
 *   reports/{YYYY-MM}  adsBlocked, urlsBlocked, appsBlocked
 * invites/{code}    familyId          ← 6자리 코드로 가족을 찾는 역인덱스
 * ```
 *
 * ## 왜 초대 코드를 별도 컬렉션에 두는가
 * 코드로 가족을 찾으려면 누군가는 그 코드를 **읽을 수 있어야** 한다. 가족 문서에
 * 코드를 넣어두고 질의하면, 아직 가족이 아닌 사람에게 가족 문서 읽기를 열어줘야
 * 한다. `invites/{code}`에는 familyId 하나만 두므로 열어줘도 잃을 게 없다.
 *
 * ## 익명 인증에서 Google 로그인으로
 * 익명 계정은 기기마다 새로 생긴다. 어르신이 폰을 바꾸면 가족 연결이 통째로
 * 사라지고, 보호자가 두 기기에서 같은 가족을 볼 수도 없다. 가족이라는 개념을
 * 두는 순간 기기가 아니라 사람을 식별해야 한다.
 *
 * **어르신에게 로그인을 요구하는 것은 분명한 UX 손실이다.** 그 대가로 얻는 것은
 * 기기 교체·재설치에도 유지되는 연결이다.
 */
object FamilyRepo {

    private const val TAG = "AdGuardFamily"
    private const val PREFS = "adguard_prefs"
    private const val KEY_FAMILY = "family_id"
    private const val KEY_ROLE = "role"

    private var db: FirebaseFirestore? = null

    /** Firebase와 로그인이 모두 준비됐는가. */
    val isReady: Boolean get() = db != null && uid() != null

    fun init(context: Context) {
        val app = context.applicationContext
        db = runCatching {
            if (FirebaseApp.getApps(app).isEmpty()) {
                Log.i(TAG, "google-services.json 없음 — 원격 기능 비활성화")
                return@runCatching null
            }
            FirebaseFirestore.getInstance()
        }.getOrElse {
            Log.e(TAG, "Firestore 초기화 실패: ${it.message}")
            null
        }
    }

    fun uid(): String? = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()

    // ── 로컬 상태 ────────────────────────────────────────────

    fun savedRole(context: Context): Role? =
        Role.of(prefs(context).getString(KEY_ROLE, null))

    fun savedFamilyId(context: Context): String? =
        prefs(context).getString(KEY_FAMILY, null)

    fun clearLocal(context: Context) {
        prefs(context).edit().remove(KEY_ROLE).remove(KEY_FAMILY).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── 가족 만들기 / 참여하기 ────────────────────────────────

    /**
     * 보호자가 가족을 만든다. 이미 만든 가족이 있으면 그대로 쓴다.
     * @return 어르신에게 불러줄 6자리 초대 코드. 실패하면 null.
     */
    suspend fun createFamily(context: Context, code: String): String? {
        val store = db ?: return null
        val uid = uid() ?: return null

        return runCatching {
            val familyId = savedFamilyId(context) ?: store.collection("families").document().id

            store.collection("families").document(familyId)
                .collection("members").document(uid)
                .set(memberDoc(Role.GUARDIAN))
                .await()

            // 역인덱스. 코드가 겹치면 나중 것이 이깁니다 — 6자리라 충돌은 드물고,
            // 겹쳐도 앞사람 가족이 사라지는 게 아니라 코드만 새 가족을 가리킨다.
            store.collection("invites").document(code)
                .set(mapOf("familyId" to familyId, "createdAt" to FieldValue.serverTimestamp()))
                .await()

            save(context, Role.GUARDIAN, familyId)
            familyId
        }.getOrElse {
            Log.e(TAG, "가족 생성 실패: ${it.message}")
            null
        }
    }

    /** 어르신이 코드를 넣어 가족에 들어간다. @return 성공 여부. */
    suspend fun joinFamily(context: Context, code: String): Boolean {
        val store = db ?: return false
        val uid = uid() ?: return false

        return runCatching {
            val familyId = store.collection("invites").document(code).get().await()
                .getString("familyId") ?: return false

            store.collection("families").document(familyId)
                .collection("members").document(uid)
                .set(memberDoc(Role.SENIOR))
                .await()

            save(context, Role.SENIOR, familyId)
            true
        }.getOrElse {
            Log.e(TAG, "가족 참여 실패: ${it.message}")
            false
        }
    }

    private fun save(context: Context, role: Role, familyId: String) {
        prefs(context).edit()
            .putString(KEY_ROLE, role.wire)
            .putString(KEY_FAMILY, familyId)
            .apply()
    }

    private fun memberDoc(role: Role) = mapOf(
        "role" to role.wire,
        "deviceName" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "joinedAt" to FieldValue.serverTimestamp()
    )

    /** FCM 토큰 등록. 토큰은 앱 재설치·복원 때 바뀌므로 실행마다 갱신한다. */
    fun updateFcmToken(context: Context, token: String) {
        val store = db ?: return
        val uid = uid() ?: return
        val familyId = savedFamilyId(context) ?: return

        store.collection("families").document(familyId)
            .collection("members").document(uid)
            .set(mapOf("fcmToken" to token), com.google.firebase.firestore.SetOptions.merge())
    }

    // ── 이벤트 ──────────────────────────────────────────────

    fun logEvent(
        context: Context,
        packageName: String,
        type: String,
        risk: RiskLevel,
        blocked: Boolean,
        count: Int = 1
    ) {
        val store = db ?: return
        val familyId = savedFamilyId(context) ?: return
        if (uid() == null) return

        store.collection("families").document(familyId)
            .collection("events")
            .add(
                mapOf(
                    "timestamp" to System.currentTimeMillis(),
                    "packageName" to packageName,
                    "type" to type,
                    "riskLevel" to risk.wire,
                    "blocked" to blocked,
                    "count" to count
                )
            )
    }

    /** 가족의 차단 내역을 실시간 구독한다. @return 구독 해제 함수. */
    fun observeEvents(
        familyId: String,
        limit: Long = 50,
        onChange: (List<FamilyEvent>) -> Unit
    ): () -> Unit {
        val store = db ?: return {}

        val registration: ListenerRegistration = store.collection("families")
            .document(familyId).collection("events")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(limit)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "이벤트 구독 실패: ${error.message}")
                    return@addSnapshotListener
                }
                onChange(
                    snapshot?.documents.orEmpty().map { doc ->
                        FamilyEvent(
                            eventId = doc.id,
                            timestamp = doc.getLong("timestamp") ?: 0L,
                            packageName = doc.getString("packageName").orEmpty(),
                            type = doc.getString("type").orEmpty(),
                            riskLevel = doc.getString("riskLevel").orEmpty(),
                            blocked = doc.getBoolean("blocked") ?: false,
                            count = (doc.getLong("count") ?: 0L).toInt()
                        )
                    }
                )
            }
        return { registration.remove() }
    }

    // ── 설정 ────────────────────────────────────────────────

    /** 보호자가 바꾼 보호 강도를 어르신 기기가 실시간으로 받는다. */
    fun observeProtectionLevel(
        familyId: String,
        memberUid: String,
        onChange: (ProtectionLevel) -> Unit
    ): () -> Unit {
        val store = db ?: return {}
        val registration = store.collection("families").document(familyId)
            .collection("settings").document(memberUid)
            .addSnapshotListener { doc, _ ->
                val level = doc?.getLong("protectionLevel")?.toInt()
                onChange(ProtectionLevel.of(level))
            }
        return { registration.remove() }
    }

    fun setProtectionLevel(context: Context, memberUid: String, level: ProtectionLevel) {
        val store = db ?: return
        val familyId = savedFamilyId(context) ?: return
        store.collection("families").document(familyId)
            .collection("settings").document(memberUid)
            .set(
                mapOf("protectionLevel" to level.value),
                com.google.firebase.firestore.SetOptions.merge()
            )
    }
}
