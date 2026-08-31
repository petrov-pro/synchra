package com.petroff.transyncai.history

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [ConversationRecordEntity::class], version = 1, exportSchema = false)
abstract class TransyncDatabase : RoomDatabase() {
    abstract fun conversationRecordDao(): ConversationRecordDao
}
