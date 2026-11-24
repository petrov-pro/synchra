package com.petroff.transyncai.history

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ConversationRecordEntity::class], version = 2, exportSchema = false)
abstract class TransyncDatabase : RoomDatabase() {
    abstract fun conversationRecordDao(): ConversationRecordDao
}

/** v2 adds the Claude summary; existing conversations keep everything and get no summary. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversation_records ADD COLUMN summary TEXT")
    }
}
