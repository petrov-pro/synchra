package com.petroff.synchra.history

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ConversationRecordEntity::class], version = 4, exportSchema = false)
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

/**
 * v4 keeps the user's general context apart from the conversation's. Older records keep their
 * joined text as the conversation context, so it is never taken for facts about the user.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversation_records ADD COLUMN userContext TEXT")
    }
}
