package com.example.pleostest.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [SleepRecord::class], version = 3, exportSchema = false)
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

        /** v3: 밤새 공조 소비 실측 보정용 컬럼 (기존 기록은 미측정) */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sleep_records ADD COLUMN startSocPct INTEGER")
                db.execSQL("ALTER TABLE sleep_records ADD COLUMN outsideTempC REAL")
                db.execSQL("ALTER TABLE sleep_records ADD COLUMN predictedHvacKw REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE sleep_records ADD COLUMN endSocPct INTEGER")
                db.execSQL("ALTER TABLE sleep_records ADD COLUMN endedAt INTEGER")
            }
        }

        fun getInstance(context: Context): SleepDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SleepDatabase::class.java,
                    "sleep_records.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
            }
    }
}
