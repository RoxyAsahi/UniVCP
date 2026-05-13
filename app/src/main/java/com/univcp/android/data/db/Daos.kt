package com.univcp.android.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AgentEntity)

    @Query("SELECT * FROM agent WHERE id = :id LIMIT 1")
    suspend fun find(id: String): AgentEntity?
}

@Dao
interface TopicDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TopicEntity)

    @Query("SELECT * FROM topic WHERE id = :id LIMIT 1")
    suspend fun find(id: String): TopicEntity?
}

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: MessageEntity)

    @Update
    suspend fun update(entity: MessageEntity)

    @Query("SELECT * FROM message WHERE topic_id = :topicId ORDER BY created_at ASC")
    fun observeForTopic(topicId: String): Flow<List<MessageEntity>>

    @Query("DELETE FROM message WHERE topic_id = :topicId")
    suspend fun clearTopic(topicId: String)
}
