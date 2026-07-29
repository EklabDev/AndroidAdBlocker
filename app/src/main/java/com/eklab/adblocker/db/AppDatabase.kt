package com.eklab.adblocker.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.eklab.adblocker.db.entities.ConnectionLog
import com.eklab.adblocker.db.entities.Rule

@Database(
    entities = [ConnectionLog::class, Rule::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun connectionLogDao(): ConnectionLogDao

    abstract fun ruleDao(): RuleDao

    companion object {
        const val DB_NAME = "traffic_inspector.db"
    }
}
