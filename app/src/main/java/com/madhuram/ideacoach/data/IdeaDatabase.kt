package com.madhuram.ideacoach.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [IdeaEntity::class, IdeaAnswerEntity::class], version = 4, exportSchema = false)
@TypeConverters(Converters::class)
abstract class IdeaDatabase : RoomDatabase() {
    abstract fun ideaDao(): IdeaDao

    companion object {
        @Volatile
        private var INSTANCE: IdeaDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS idea_answers (
                        ideaId TEXT NOT NULL,
                        question TEXT NOT NULL,
                        answer TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(ideaId, question),
                        FOREIGN KEY(ideaId) REFERENCES ideas(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_idea_answers_ideaId ON idea_answers(ideaId)"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE ideas ADD COLUMN transcriptionStatus TEXT NOT NULL DEFAULT 'DONE'"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE ideas ADD COLUMN lastTranscriptionError TEXT"
                )
            }
        }

        fun getInstance(context: Context): IdeaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    IdeaDatabase::class.java,
                    "ideacoach.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { INSTANCE = it }
            }
        }
    }
}
