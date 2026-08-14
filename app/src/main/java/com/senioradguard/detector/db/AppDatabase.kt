package com.senioradguard.detector.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [BlacklistDomain::class, AdVerdict::class, UrlVerdict::class, AdFingerprintLink::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun blacklistDao(): BlacklistDao

    abstract fun adVerdictDao(): AdVerdictDao

    abstract fun urlVerdictDao(): UrlVerdictDao

    abstract fun adFingerprintLinkDao(): AdFingerprintLinkDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "senior_ad_guard.db"
                )
                    // 블랙리스트는 워커가 다시 받아오고 판정은 캐시라 유실이 무해하다.
                    // 손으로 쓴 마이그레이션보다 실수 여지가 적어 파괴적 마이그레이션을 쓴다.
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }
}
