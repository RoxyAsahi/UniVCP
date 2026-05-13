package com.univcp.android.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "agent")
data class AgentEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo("system_prompt") val systemPrompt: String,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("updated_at") val updatedAt: Long
)

@Entity(
    tableName = "topic",
    foreignKeys = [
        ForeignKey(
            entity = AgentEntity::class,
            parentColumns = ["id"],
            childColumns = ["agent_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("agent_id")]
)
data class TopicEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("agent_id") val agentId: String,
    val title: String,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("updated_at") val updatedAt: Long
)

@Entity(
    tableName = "message",
    foreignKeys = [
        ForeignKey(
            entity = TopicEntity::class,
            parentColumns = ["id"],
            childColumns = ["topic_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("topic_id"), Index(value = ["topic_id", "created_at"])]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("topic_id") val topicId: String,
    val role: String,
    val content: String,
    @ColumnInfo("render_mode") val renderMode: String,
    val language: String = "",
    @ColumnInfo("allow_script") val allowScript: Boolean = false,
    @ColumnInfo("is_streaming") val isStreaming: Boolean = false,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("updated_at") val updatedAt: Long
)
