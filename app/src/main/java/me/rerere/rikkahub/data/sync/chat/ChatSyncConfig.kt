package me.rerere.rikkahub.data.sync.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class ChatSyncConfig(
    val enabled: Boolean = false,
    val provider: ChatSyncProvider = ChatSyncProvider.VCPCHAT_LAN,
    val firebaseDatabaseUrl: String = "",
    val firebaseAuthToken: String = "",
    val vcpChatLanBaseUrl: String = "",
    val vcpChatLanToken: String = "",
    val vcpChatLanPollIntervalMs: Long = 15_000L,
    val vcpChatLanRealtimeEvents: Boolean = true,
    val vcpChatLanTargetItemType: String = "agent",
    val vcpChatLanTargetItemId: String = "",
    val vcpChatLanTargetTopicId: String = "",
    val roomId: String = "default",
    val deviceId: String = "",
    val targetAssistantId: Uuid? = null,
    val mode: ChatSyncMode = ChatSyncMode.PULL_ONLY,
    val pushOnStart: Boolean = false,
    val importRemoteOnStart: Boolean = true,
    val currentPathOnly: Boolean = true,
    val incrementalPull: Boolean = false,
    val debounceMs: Long = 800L,
)

@Serializable
enum class ChatSyncProvider {
    @SerialName("firebase_rtdb")
    FIREBASE_RTDB,

    @SerialName("vcpchat_lan")
    VCPCHAT_LAN,
}

@Serializable
enum class ChatSyncMode {
    BOTH,
    PUSH_ONLY,
    PULL_ONLY,
}

fun ChatSyncConfig.isFirebaseConfigured(): Boolean {
    return enabled && provider == ChatSyncProvider.FIREBASE_RTDB && firebaseDatabaseUrl.isNotBlank()
}

fun ChatSyncConfig.isVcpChatLanConfigured(): Boolean {
    return enabled && provider == ChatSyncProvider.VCPCHAT_LAN && vcpChatLanBaseUrl.isNotBlank()
}

fun ChatSyncMode.allowsPush(): Boolean {
    return this == ChatSyncMode.BOTH || this == ChatSyncMode.PUSH_ONLY
}

fun ChatSyncMode.allowsPull(): Boolean {
    return this == ChatSyncMode.BOTH || this == ChatSyncMode.PULL_ONLY
}
