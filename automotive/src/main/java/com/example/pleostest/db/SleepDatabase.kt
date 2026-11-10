package com.example.pleostest.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [SleepRecord::class], version = 2, exportSchema = false)
abstract class SleepDatabase : RoomDatabase() {
    abstract fun sleepRecordDao(): SleepRecordDao

    companion object {
        @Volatile private var INSTANCE: SleepDatabase? = null

        /** v2: 목적지 좌표 추가 (기존 기록은 0,0 = 미상) */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sleep_records ADD COLUMN destinationLat REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE sleep_records ADD COLUMN destinationLng REAL NOT NULL DEFAULT 0.0")
            }
        }

        fun getInstance(context: Context): SleepDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SleepDatabase::class.java,
                    "sleep_records.db"
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
    }
}
