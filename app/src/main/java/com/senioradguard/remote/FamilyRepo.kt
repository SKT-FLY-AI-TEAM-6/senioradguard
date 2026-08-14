package com.senioradguard.remote

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.Query
import com.google.firebase.database.ValueEventListener
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
 * 가족 단위 저장소. **Realtime Database**를 쓴다.
 *
 * ```
 * families/{familyId}
 *   members/{uid}   role, deviceName, fcmToken, joinedAt
 *   settings/{uid}  protectionLevel, whitelist[]
 *   events/{id}     timestamp, riskLevel, type, blocked, packageName, count
 *   reports/{YYYY-MM}  adsBlocked, urlsBlocked, appsBlocked
 * invites/{code}    familyId          ← 6자리 코드로 가족을 찾는 역인덱스
 * ```
 *
 * ## 왜 Firestore가 아닌가
 * 한 번 Firestore로 옮겼다가 되돌렸다. 이 데이터는 "가족 하나에 이벤트가 시간순으로
 * 쌓이고 그걸 통째로 구독한다"가 전부다 — Realtime Database가 정확히 그런 모양이고,
 * 실기기 2대 연동도 이미 이 방식으로 검증했다. Firestore의 복합 질의·오프라인
 * 캐시는 지금 쓰지 않는 기능이라 값을 못 한다. 가족 수가 늘고 월간 리포트 집계처럼
 * 질의가 필요해지면 그때 옮긴다.
 *
 * ## 왜 초대 코드를 별도 노드에 두는가
 * 코드로 가족을 찾으려면 누군가는 그 코드를 **읽을 수 있어야** 한다. 가족 노드 안에
 * 코드를 넣으면 아직 가족이 아닌 사람에게 가족 전체 읽기를 열어줘야 한다.
 * `invites/{code}`에는 familyId 하나만 있으므로 열어줘도 잃을 게 없다.
 */
object FamilyRepo {

    private const val TAG = "AdGuardFamily"
    private const val PREFS = "adguard_prefs"
    private const val KEY_FAMILY = "family_id"
    private const val KEY_ROLE = "role"
    private const val KEY_INVITE = "invite_code"

    /** 보호자 화면에 한 번에 보여줄 최근 내역 수. */
    private const val EVENT_LIMIT = 50

    private var db: DatabaseReference? = null

    /** Firebase와 로그인이 모두 준비됐는가. */
    val isReady: Boolean get() = db != null && uid() != null

    fun init(context: Context) {
        val app = context.applicationContext
        db = runCatching {
            if (FirebaseApp.getApps(app).isEmpty()) {
                Log.i(TAG, "google-services.json 없음 — 원격 기능 비활성화")
                return@runCatching null
            }
            FirebaseDatabase.getInstance().reference
        }.getOrElse {
            Log.e(TAG, "Realtime Database 초기화 실패: ${it.message}")
            null
        }
    }

    fun uid(): String? = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()

    // ── 로컬 상태 ────────────────────────────────────────────

    fun savedRole(context: Context): Role? =
        Role.of(prefs(context).getString(KEY_ROLE, null))

    fun savedFamilyId(context: Context): String? =
        prefs(context).getString(KEY_FAMILY, null)

    /** 이 기기가 만든 가족의 초대 코드. 보호자만 값이 있다. */
    fun savedInviteCode(context: Context): String? =
        prefs(context).getString(KEY_INVITE, null)

    fun clearLocal(context: Context) {
        prefs(context).edit().remove(KEY_ROLE).remove(KEY_FAMILY).remove(KEY_INVITE).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── 가족 만들기 / 참여하기 ────────────────────────────────

    /**
     * 보호자가 가족을 만든다. 이미 만든 가족이 있으면 그대로 쓴다.
     * @return 만들어진 familyId. 실패하면 null.
     */
    suspend fun createFamily(context: Context, code: String): String? {
        val root = db ?: return null
        val uid = uid() ?: return null

        return runCatching {
            val familyId = savedFamilyId(context)
                ?: root.child("families").push().key
                ?: return null

            root.child("families").child(familyId).child("members").child(uid)
                .setValue(memberDoc(Role.GUARDIAN))
                .await()

            // 역인덱스. 6자리라 충돌은 드물고, 겹쳐도 앞선 가족이 사라지는 게
            // 아니라 코드만 새 가족을 가리킨다.
            root.child("invites").child(code)
                .setValue(mapOf("familyId" to familyId, "createdAt" to System.currentTimeMillis()))
                .await()

            save(context, Role.GUARDIAN, familyId)
            // 코드를 기억해 둔다. 보호자가 나중에 다시 불러줘야 할 때 화면에서
            // 확인할 수 있어야 한다.
            prefs(context).edit().putString(KEY_INVITE, code).apply()
            familyId
        }.getOrElse {
            Log.e(TAG, "가족 생성 실패: ${it.message}")
            null
        }
    }

    /** 어르신이 코드를 넣어 가족에 들어간다. @return 성공 여부. */
    suspend fun joinFamily(context: Context, code: String): Boolean {
        val root = db ?: return false
        val uid = uid() ?: return false

        return runCatching {
            val familyId = root.child("invites").child(code).child("familyId")
                .get().await().getValue(String::class.java) ?: return false

            root.child("families").child(familyId).child("members").child(uid)
                .setValue(memberDoc(Role.SENIOR))
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
        "joinedAt" to System.currentTimeMillis()
    )

    /** FCM 토큰 등록. 토큰은 앱 재설치·복원 때 바뀌므로 실행마다 갱신한다. */
    fun updateFcmToken(context: Context, token: String) {
        val root = db ?: return
        val uid = uid() ?: return
        val familyId = savedFamilyId(context) ?: return

        root.child("families").child(familyId).child("members").child(uid)
            .child("fcmToken").setValue(token)
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
        val root = db ?: return
        val familyId = savedFamilyId(context) ?: return
        if (uid() == null) return

        val events = root.child("families").child(familyId).child("events")
        val id = events.push().key ?: return
        events.child(id).setValue(
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
    fun observeEvents(familyId: String, onChange: (List<FamilyEvent>) -> Unit): () -> Unit {
        val root = db ?: return {}

        // 최근 것만 받는다. 전체를 구독하면 몇 달치가 매번 통째로 내려온다.
        // Realtime Database는 오름차순만 주므로 받은 뒤 뒤집는다.
        val query: Query = root.child("families").child(familyId).child("events")
            .orderByChild("timestamp")
            .limitToLast(EVENT_LIMIT)

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                onChange(
                    snapshot.children.map { child ->
                        FamilyEvent(
                            eventId = child.key.orEmpty(),
                            timestamp = child.child("timestamp").getValue(Long::class.java) ?: 0L,
                            packageName = child.child("packageName")
                                .getValue(String::class.java).orEmpty(),
                            type = child.child("type").getValue(String::class.java).orEmpty(),
                            riskLevel = child.child("riskLevel")
                                .getValue(String::class.java).orEmpty(),
                            blocked = child.child("blocked").getValue(Boolean::class.java) ?: false,
                            count = (child.child("count").getValue(Long::class.java) ?: 0L).toInt()
                        )
                    }.sortedByDescending { it.timestamp }
                )
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "이벤트 구독 실패: ${error.message}")
            }
        }

        query.addValueEventListener(listener)
        return { query.removeEventListener(listener) }
    }

    // ── 설정 ────────────────────────────────────────────────

    /** 보호자가 바꾼 보호 강도를 어르신 기기가 실시간으로 받는다. */
    fun observeProtectionLevel(
        familyId: String,
        memberUid: String,
        onChange: (ProtectionLevel) -> Unit
    ): () -> Unit {
        val root = db ?: return {}
        val node = root.child("families").child(familyId)
            .child("settings").child(memberUid).child("protectionLevel")

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                onChange(ProtectionLevel.of(snapshot.getValue(Int::class.java)))
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "보호 강도 구독 실패: ${error.message}")
            }
        }
        node.addValueEventListener(listener)
        return { node.removeEventListener(listener) }
    }

    fun setProtectionLevel(context: Context, memberUid: String, level: ProtectionLevel) {
        val root = db ?: return
        val familyId = savedFamilyId(context) ?: return
        root.child("families").child(familyId)
            .child("settings").child(memberUid).child("protectionLevel")
            .setValue(level.value)
    }
}
