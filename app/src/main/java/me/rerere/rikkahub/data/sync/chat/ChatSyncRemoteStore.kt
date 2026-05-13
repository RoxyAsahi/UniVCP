package me.rerere.rikkahub.data.sync.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.Serializable

interface ChatSyncRemoteStore {
    suspend fun pullChanges(since: ChatSyncCursor? = null): ChatSyncPullResult

    suspend fun pushChanges(conversations: List<SyncConversation>): ChatSyncPushResult

    fun observeChanges(since: ChatSyncCursor? = null): Flow<ChatSyncRemoteEvent> = emptyFlow()
}

data class ChatSyncCursor(
    val updatedAfter: Long? = null,
    val pageToken: String? = null,
)

data class ChatSyncPullResult(
    val conversations: List<SyncConversation>,
    val nextCursor: ChatSyncCursor? = null,
)

data class ChatSyncPushResult(
    val pushedConversationIds: List<String>,
    val rejected: List<ChatSyncRejection> = emptyList(),
)

data class ChatSyncRejection(
    val conversationId: String,
    val reason: String,
)

data class ChatSyncRemoteEvent(
    val conversation: SyncConversation,
    val cursor: ChatSyncCursor,
    val sourceDeviceId: String? = null,
)

@Serializable
data class ChatSyncRemoteEnvelope(
    val schemaVersion: Int = CHAT_SYNC_SCHEMA_VERSION,
    val id: String,
    val updatedAt: Long,
    val sourceDeviceId: String? = null,
    val conversation: SyncConversation,
)
