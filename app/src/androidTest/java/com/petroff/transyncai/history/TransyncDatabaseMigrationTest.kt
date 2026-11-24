package com.petroff.transyncai.history

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** App update path: history saved by the version-1 schema must survive the summary column. */
@RunWith(AndroidJUnit4::class)
class TransyncDatabaseMigrationTest {

    @Test
    fun upgradingAVersion1DatabaseKeepsRecords() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-test.db"
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS conversation_records (id TEXT NOT NULL, " +
                    "timestamp INTEGER NOT NULL, title TEXT, sourceLanguageName TEXT NOT NULL, " +
                    "targetLanguageName TEXT NOT NULL, sourceText TEXT NOT NULL, " +
                    "translatedText TEXT NOT NULL, PRIMARY KEY(id))"
            )
            db.execSQL(
                "INSERT INTO conversation_records VALUES " +
                    "('r1', 1000, NULL, 'English', 'Russian', 'Hello', 'Привет')"
            )
            db.version = 1
        }

        val database = Room.databaseBuilder(context, TransyncDatabase::class.java, name)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
        try {
            val record = runBlocking { database.conversationRecordDao().getAll() }.single()
            assertEquals("r1", record.id)
            assertEquals("Hello", record.sourceText)
            assertEquals("Привет", record.translatedText)
            assertNull(record.summary)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun upgradingAVersion2DatabaseKeepsRecordsAndSummaries() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration-test-v2.db"
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS conversation_records (id TEXT NOT NULL, " +
                    "timestamp INTEGER NOT NULL, title TEXT, sourceLanguageName TEXT NOT NULL, " +
                    "targetLanguageName TEXT NOT NULL, sourceText TEXT NOT NULL, " +
                    "translatedText TEXT NOT NULL, summary TEXT, PRIMARY KEY(id))"
            )
            db.execSQL(
                "INSERT INTO conversation_records VALUES " +
                    "('r1', 1000, 'Встреча', 'English', 'Russian', 'Hello', 'Привет', 'Обсудили сроки.')"
            )
            db.version = 2
        }

        val database = Room.databaseBuilder(context, TransyncDatabase::class.java, name)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
        try {
            val record = runBlocking { database.conversationRecordDao().getAll() }.single()
            assertEquals("Обсудили сроки.", record.summary)
            assertEquals("Встреча", record.title)
            assertNull(record.context)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}

