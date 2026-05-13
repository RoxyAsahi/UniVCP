package me.rerere.rikkahub.data.sync.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class ChatSyncConfig(
    val enabled: Boolean = false,
    val provider: ChatSyncProvider = ChatSyncProvider.FIREBASE_RTDB,
    val firebaseDatabaseUrl: String = "",
    val firebaseAuthToken: String = "",
    val roomId: String = "default",
    val deviceId: String = "",
    val targetAssistantId: Uuid? = null,
    val pushOnStart: Boolean = false,
    val importRemoteOnStart: Boolean = true,
    val currentPathOnly: Boolean = true,
    val debounceMs: Long = 800L,
)

@Serializable
enum class ChatSyncProvider {
    @SerialName("firebase_rtdb")
    FIREBASE_RTDB,
}

fun ChatSyncConfig.isFirebaseConfigured(): Boolean {
    return enabled && provider == ChatSyncProvider.FIREBASE_RTDB && firebaseDatabaseUrl.isNotBlank()
}
