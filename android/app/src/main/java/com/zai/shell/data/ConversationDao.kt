package com.zai.shell.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * DAO para operaciones CRUD de conversaciones y mensajes.
 * Todas las consultas son suspend (coroutines) para no bloquear el hilo principal.
 */
@Dao
interface ConversationDao {

    // ========== CONVERSATIONS ==========

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversation(conv: ConversationEntity)

    @Query("SELECT * FROM conversations ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun listConversations(limit: Int = 50, offset: Int = 0): List<ConversationEntity>

    @Query("SELECT * FROM conversations ORDER BY timestamp DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversation(id: String): ConversationEntity?

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: String)

    @Query("DELETE FROM conversations")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun count(): Int

    /**
     * Trim conversations to max N (oldest first by timestamp)
     */
    @Query("DELETE FROM conversations WHERE id NOT IN (SELECT id FROM conversations ORDER BY timestamp DESC LIMIT :keep)")
    suspend fun trimOldConversations(keep: Int)

    // ========== MESSAGES ==========

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(msg: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE conversationId = :convId ORDER BY seq ASC")
    suspend fun getMessages(convId: String): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :convId")
    suspend fun countMessages(convId: String): Int

    @Query("DELETE FROM messages WHERE conversationId = :convId")
    suspend fun deleteMessages(convId: String)

    // ========== SEARCH ==========

    /**
     * Búsqueda full-text simple con LIKE.
     * Para búsquedas más avanzadas ver FTS4 (no implementado aún).
     * Índice en `content` acelera las búsquedas prefix.
     */
    @Query("""
        SELECT m.*, c.title as convTitle
        FROM messages m
        INNER JOIN conversations c ON m.conversationId = c.id
        WHERE m.content LIKE '%' || :query || '%'
        ORDER BY m.timestamp DESC
        LIMIT :limit
    """)
    suspend fun searchMessages(query: String, limit: Int = 100): List<SearchResult>

    @Transaction
    suspend fun saveConversationWithMessages(conv: ConversationEntity, messages: List<MessageEntity>) {
        upsertConversation(conv)
        // Borrar mensajes anteriores y reinsertar (más simple que diff)
        deleteMessages(conv.id)
        if (messages.isNotEmpty()) {
            insertMessages(messages)
        }
    }
}

/**
 * Resultado de búsqueda con metadata de la conversación.
 */
data class SearchResult(
    val id: Long,
    val conversationId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val seq: Int,
    val convTitle: String
)
