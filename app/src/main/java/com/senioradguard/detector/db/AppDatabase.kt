package com.senioradguard.detector.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [BlacklistDomain::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun blacklistDao(): BlacklistDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "senior_ad_guard.db"
                ).build().also { instance = it }
            }
    }
}
