package com.petroff.synchra.history

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ConversationRecordEntity::class], version = 3, exportSchema = false)
abstract class SynchraDatabase : RoomDatabase() {
    abstract fun conversationRecordDao(): ConversationRecordDao
}

/** v2 adds the Claude summary; existing conversations keep everything and get no summary. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversation_records ADD COLUMN summary TEXT")
    }
}

/** v3 keeps the context the conversation was recorded with, for its summary. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversation_records ADD COLUMN context TEXT")
    }
}
