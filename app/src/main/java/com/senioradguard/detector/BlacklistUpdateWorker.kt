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
        if (updated) BlacklistCache.invalidate()
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
        // 주기가 바뀌었으므로 이름도 바꾼다. 같은 이름으로 KEEP을 쓰면 예전에
        // 주 1회로 예약된 작업이 그대로 살아남아 새 주기가 적용되지 않는다.
        private const val WORK_NAME = "blacklist_monthly_update"

        /**
         * 앱 시작 시 1회 호출하면 이후 자동 갱신된다. 이미 예약돼 있으면 유지한다(KEEP).
         *
         * 주 1회에서 **월 1회로 늦췄다.** 14만 개 도메인을 받아 파싱하고 DB를 통째로
         * 갈아끼우는 작업이라 배터리와 데이터를 눈에 띄게 쓴다. 광고 도메인 목록은
         * 하루 이틀 사이에 뒤집히는 종류가 아니라 이 주기로 충분하다.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<BlacklistUpdateWorker>(30, TimeUnit.DAYS)
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
