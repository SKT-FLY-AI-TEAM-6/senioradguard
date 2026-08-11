package com.senioradguard.detector

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import java.util.concurrent.TimeUnit

/**
 * 광고 도메인 블랙리스트를 주 1회 원격에서 받아 Room DB에 반영하는 백그라운드 작업.
 */
class BlacklistUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repository = BlacklistRepository(applicationContext)
        val updated = repository.refreshFromRemote(BLACKLIST_SOURCES)
        return if (updated) Result.success() else Result.retry()
    }

    companion object {
        // 기본: hosts 형식(0.0.0.0 domain.com), 보조: AdGuard 필터 형식(||domain.com^)
        // 두 소스를 순차 다운로드해 도메인을 합치고 중복 제거 후 DB에 반영한다.
        private val BLACKLIST_SOURCES = listOf(
            BlacklistSource(
                url = "https://raw.githubusercontent.com/smed79/blacklist/master/hosts.txt",
                format = BlacklistFormat.HOSTS
            ),
            BlacklistSource(
                url = "https://github.com/List-KR/List-KR/raw/master/filter.txt",
                format = BlacklistFormat.ADGUARD
            )
        )
        private const val WORK_NAME = "blacklist_weekly_update"

        /**
         * 앱 시작 시 1회 호출하면 이후 주 1회(Wi-Fi 등 네트워크 연결 시) 자동 갱신된다.
         * 이미 예약돼 있으면 그대로 유지한다(KEEP).
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<BlacklistUpdateWorker>(7, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
