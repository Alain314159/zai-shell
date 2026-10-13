package com.zai.shell.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Representa una conversación guardada en el historial offline.
 * Tabla: conversations
 */
@Entity(
    tableName = "conversations",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["url"]),
        Index(value = ["title"])
    ]
)
data class ConversationEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val url: String,
    val timestamp: Long,
    val messageCount: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
