package com.univcp.android.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        AgentEntity::class,
        TopicEntity::class,
        MessageEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun agentDao(): AgentDao
    abstract fun topicDao(): TopicDao
    abstract fun messageDao(): MessageDao
}
