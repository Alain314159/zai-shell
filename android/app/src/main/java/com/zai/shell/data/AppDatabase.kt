package com.zai.shell.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Base de datos SQLite del historial offline.
 *
 * Singleton: usar AppDatabase.getInstance(context)
 *
 * Reemplaza la implementación anterior en SharedPreferences que
 * parseaba/serializaba TODO el JSON en cada save (O(N²)).
 * Con Room/SQLite:
 *  - save es O(M) donde M = mensajes en la conversación (no todas)
 *  - search es O(log N) con índice en content
 *  - puede manejar miles de conversaciones sin degradación
 */
@Database(
    entities = [ConversationEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "zai_shell.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
