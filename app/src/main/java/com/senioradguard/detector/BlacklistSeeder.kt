package com.senioradguard.detector

import android.content.Context
import android.util.Log
import com.senioradguard.detector.db.AppDatabase
import com.senioradguard.detector.db.BlacklistDomain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `assets/blacklist.txt`의 도메인을 첫 실행 때 Room에 넣는다.
 *
 * 원격 다운로드(BlacklistUpdateWorker)를 쓰지 않는다. 14만 개를 받아 파싱하고 DB를
 * 통째로 갈아끼우는 작업이 배터리를 눈에 띄게 쓰고, 그걸 주기적으로 도는 것이
 * 제조사 절전에 이 앱이 얼어붙는 원인이기도 했다. 목록을 APK에 넣어두면 설치 직후부터
 * 동작하고 네트워크도 필요 없다. 갱신 주기가 필요해지면 그때 워커를 되살린다.
 *
 * 삽입은 한 번만 한다. 14만 행이라 매 실행마다 하면 앱이 뜰 때마다 몇 초씩 먹는다.
 */
object BlacklistSeeder {

    private const val TAG = "AdGuardSeed"
    private const val PREFS = "adguard_prefs"
    private const val KEY_SEEDED = "blacklist_seeded_v1"
    private const val ASSET = "blacklist.txt"

    /** 한 번에 넣을 행 수. 통째로 넣으면 SQLite 트랜잭션이 너무 커진다. */
    private const val CHUNK = 5_000

    suspend fun seedIfNeeded(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SEEDED, false)) return@withContext

        val dao = AppDatabase.getInstance(app).blacklistDao()
        val now = System.currentTimeMillis()

        val inserted = runCatching {
            var count = 0
            app.assets.open(ASSET).bufferedReader().useLines { lines ->
                lines.map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .chunked(CHUNK)
                    .forEach { chunk ->
                        dao.insertAll(chunk.map { BlacklistDomain(it, now) })
                        count += chunk.size
                    }
            }
            count
        }.getOrElse {
            // 실패하면 표시를 남기지 않는다 — 다음 실행에서 다시 시도한다.
            Log.e(TAG, "초기 목록 삽입 실패: ${it.message}")
            return@withContext
        }

        prefs.edit().putBoolean(KEY_SEEDED, true).apply()
        Log.i(TAG, "초기 차단 도메인 ${inserted}건 삽입")
    }
}
