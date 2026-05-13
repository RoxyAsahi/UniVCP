package me.rerere.rikkahub.data.sync.chat

import android.util.Log
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import okhttp3.OkHttpClient
import kotlin.uuid.Uuid

private const val TAG = "ChatSyncManager"

class ChatSyncManager(
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val conversationDAO: ConversationDAO,
    private val conversationRepository: ConversationRepository,
    private val chatService: ChatService,
    private val httpClient: OkHttpClient,
    private val json: Json,
) {
    private val _state = MutableStateFlow(ChatSyncState())
    val state: StateFlow<ChatSyncState> = _state

    private var settingsJob: Job? = null
    private var runtimeJob: Job? = null
    private var remoteCursor: ChatSyncCursor? = null
    private val pendingPushJobs = ConcurrentHashMap<String, Job>()
    private val suppressedLocalWrites = ConcurrentHashMap<String, Long>()

    fun start() {
        if (settingsJob != null) return
        settingsJob = appScope.launch(Dispatchers.IO) {
            seedDebugConfigIfNeeded()
            settingsStore.settingsFlow
                .map { it.chatSyncConfig }
                .distinctUntilChanged()
                .collect { config ->
                    restartRuntime(config)
                }
        }
    }

    private suspend fun seedDebugConfigIfNeeded() {
        if (!BuildConfig.DEBUG || !BuildConfig.UNIVCP_CHAT_SYNC_ENABLED) return
        val databaseUrl = BuildConfig.UNIVCP_CHAT_SYNC_FIREBASE_DATABASE_URL.trim()
        if (databaseUrl.isBlank()) return

        val roomId = BuildConfig.UNIVCP_CHAT_SYNC_ROOM_ID.ifBlank { "default" }
        val currentSettings = settingsStore.settingsFlowRaw.first { !it.init }
        val current = currentSettings.chatSyncConfig
        if (current.enabled && current.firebaseDatabaseUrl == databaseUrl && current.roomId == roomId) {
            return
        }

        settingsStore.update(
            currentSettings.copy(
                chatSyncConfig = current.copy(
                    enabled = true,
                    firebaseDatabaseUrl = databaseUrl,
                    firebaseAuthToken = BuildConfig.UNIVCP_CHAT_SYNC_FIREBASE_AUTH_TOKEN.trim(),
                    roomId = roomId,
                    deviceId = BuildConfig.UNIVCP_CHAT_SYNC_DEVICE_ID.trim().ifBlank { current.deviceId },
                    importRemoteOnStart = true,
                    pushOnStart = current.pushOnStart,
                )
            )
        )
        Log.i(TAG, "Seeded debug chat sync config for room=$roomId")
    }

    fun stop() {
        settingsJob?.cancel()
        settingsJob = null
        appScope.launch(Dispatchers.IO) {
            stopRuntime()
            setState { ChatSyncState() }
        }
    }

    suspend fun syncNow(direction: ChatSyncDirection = ChatSyncDirection.BOTH): ChatSyncRunResult {
        val config = settingsStore.settingsFlow.value.chatSyncConfig
        val store = createStoreOrNull(config) ?: return ChatSyncRunResult(skippedReason = "Firebase is not configured")

        return withContext(Dispatchers.IO) {
            when (direction) {
                ChatSyncDirection.PUSH -> {
                    val pushed = pushAllLocal(store, config)
                    ChatSyncRunResult(pushed = pushed)
                }

                ChatSyncDirection.PULL -> {
                    val imported = pullRemoteOnce(store, config)
                    ChatSyncRunResult(imported = imported)
                }

                ChatSyncDirection.BOTH -> {
                    val imported = pullRemoteOnce(store, config)
                    val pushed = pushAllLocal(store, config)
                    ChatSyncRunResult(pushed = pushed, imported = imported)
                }
            }
        }
    }

    suspend fun testConnection() {
        val config = settingsStore.settingsFlow.value.chatSyncConfig
        val store = createStoreOrNull(config) ?: error("Firebase is not configured")
        withContext(Dispatchers.IO) {
            store.pullChanges(ChatSyncCursor(updatedAfter = Long.MAX_VALUE))
        }
    }

    private suspend fun restartRuntime(config: ChatSyncConfig) {
        stopRuntime()

        if (config.enabled && config.deviceId.isBlank()) {
            val generatedConfig = config.copy(deviceId = "univcp-${UUID.randomUUID()}")
            settingsStore.update { settings ->
                settings.copy(chatSyncConfig = generatedConfig)
            }
            return
        }

        if (!config.enabled) {
            remoteCursor = null
            setState {
                it.copy(
                    enabled = false,
                    running = false,
                    provider = config.provider.name,
                    roomId = config.roomId,
                    deviceId = config.resolvedDeviceId(),
                    lastError = null,
                )
            }
            return
        }

        val store = createStoreOrNull(config)
        if (store == null) {
            setState {
                it.copy(
                    enabled = true,
                    running = false,
                    provider = config.provider.name,
                    roomId = config.roomId,
                    deviceId = config.resolvedDeviceId(),
                    lastError = "Firebase Realtime Database URL is empty",
                )
            }
            return
        }

        remoteCursor = null
        val runtimeSupervisor = SupervisorJob()
        runtimeJob = appScope.launch(Dispatchers.IO + runtimeSupervisor) {
            setState {
                it.copy(
                    enabled = true,
                    running = true,
                    provider = config.provider.name,
                    roomId = config.roomId,
                    deviceId = config.resolvedDeviceId(),
                    lastError = null,
                )
            }

            if (config.importRemoteOnStart) {
                runCatching {
                    pullRemoteOnce(store, config)
                }.onFailure { error ->
                    recordError("initialPull", error)
                }
            }

            coroutineScope {
                if (config.pushOnStart) {
                    launch {
                        runCatching {
                            pushAllLocal(store, config)
                        }.onFailure { error ->
                            recordError("pushOnStart", error)
                        }
                    }
                }

                launch {
                    observeRemoteLoop(store, config)
                }
                launch {
                    observeLocalChanges(store, config)
                }
            }
        }
    }

    private suspend fun stopRuntime() {
        pendingPushJobs.values.forEach { it.cancel() }
        pendingPushJobs.clear()
        runtimeJob?.cancelAndJoin()
        runtimeJob = null
        setState { it.copy(running = false) }
    }

    private suspend fun observeRemoteLoop(store: ChatSyncRemoteStore, config: ChatSyncConfig) {
        while (currentCoroutineContext().isActive) {
            runCatching {
                store.observeChanges(remoteCursor).collect { event ->
                    remoteCursor = event.cursor
                    applyRemoteConversation(event.conversation, config)
                }
            }.onFailure { error ->
                if (currentCoroutineContext().isActive) {
                    recordError("observeRemote", error)
                    delay(5000)
                }
            }
        }
    }

    private suspend fun observeLocalChanges(store: ChatSyncRemoteStore, config: ChatSyncConfig) {
        val knownUpdateTimes = mutableMapOf<String, Long>()
        var initialized = false

        conversationDAO.getAll().collect { conversations ->
            val currentIds = conversations.map { it.id }.toSet()
            knownUpdateTimes.keys.removeAll { it !in currentIds }

            if (!initialized) {
                conversations.forEach { knownUpdateTimes[it.id] = it.updateAt }
                initialized = true
                return@collect
            }

            conversations.forEach { entity ->
                val previousUpdateAt = knownUpdateTimes[entity.id]
                knownUpdateTimes[entity.id] = entity.updateAt
                if (previousUpdateAt != null && previousUpdateAt == entity.updateAt) return@forEach
                if (isSuppressedLocalWrite(entity.id)) return@forEach
                schedulePush(store, config, entity.id)
            }
        }
    }

    private fun schedulePush(store: ChatSyncRemoteStore, config: ChatSyncConfig, conversationId: String) {
        pendingPushJobs.remove(conversationId)?.cancel()
        pendingPushJobs[conversationId] = appScope.launch(Dispatchers.IO) {
            delay(config.debounceMs.coerceAtLeast(0L))
            pendingPushJobs.remove(conversationId)
            runCatching {
                pushConversation(store, config, conversationId)
            }.onFailure { error ->
                recordError("pushConversation", error)
            }
        }
    }

    private suspend fun pushAllLocal(store: ChatSyncRemoteStore, config: ChatSyncConfig): Int {
        val ids = conversationDAO.getAllIds()
        var pushed = 0
        ids.forEach { id ->
            if (pushConversation(store, config, id)) {
                pushed += 1
            }
        }
        return pushed
    }

    private suspend fun pushConversation(store: ChatSyncRemoteStore, config: ChatSyncConfig, conversationId: String): Boolean {
        val id = runCatching { Uuid.parse(conversationId) }.getOrNull() ?: return false
        val conversation = conversationRepository.getConversationById(id) ?: return false
        val settings = settingsStore.settingsFlow.value
        val result = store.pushChanges(
            listOf(
                UniVcpChatSyncMapper.exportConversation(
                    conversation = conversation,
                    assistant = settings.getAssistantById(conversation.assistantId),
                    deviceId = config.resolvedDeviceId(),
                    currentPathOnly = config.currentPathOnly,
                )
            )
        )
        if (result.rejected.isNotEmpty()) {
            error(result.rejected.joinToString { "${it.conversationId}: ${it.reason}" })
        }
        setState {
            it.copy(
                pushedCount = it.pushedCount + result.pushedConversationIds.size,
                lastPushAt = System.currentTimeMillis(),
                lastError = null,
            )
        }
        return result.pushedConversationIds.isNotEmpty()
    }

    private suspend fun pullRemoteOnce(store: ChatSyncRemoteStore, config: ChatSyncConfig): Int {
        val result = store.pullChanges(remoteCursor)
        remoteCursor = result.nextCursor ?: remoteCursor
        var imported = 0
        result.conversations.forEach { conversation ->
            if (applyRemoteConversation(conversation, config)) {
                imported += 1
            }
        }
        Log.i(TAG, "Pulled ${result.conversations.size} remote conversations, imported=$imported")
        return imported
    }

    private suspend fun applyRemoteConversation(syncConversation: SyncConversation, config: ChatSyncConfig): Boolean {
        val localId = syncConversation.id.toLocalConversationId()
        val targetAssistantId = resolveImportAssistantId(syncConversation, config)
        val existing = conversationRepository.getConversationById(localId)
        if (existing != null && existing.updateAt.toEpochMilli() >= syncConversation.updatedAt) {
            if (existing.assistantId != targetAssistantId) {
                suppressLocalWrite(existing.id.toString())
                chatService.saveConversation(existing.id, existing.copy(assistantId = targetAssistantId))
                Log.i(TAG, "Moved remote conversation title=${syncConversation.title} to imported assistant")
                return true
            }
            Log.i(TAG, "Skipped remote conversation title=${syncConversation.title}, local is up-to-date")
            return false
        }

        val imported = UniVcpChatSyncMapper.importConversation(
            syncConversation = syncConversation,
            assistantId = targetAssistantId,
        )

        suppressLocalWrite(imported.id.toString())
        chatService.saveConversation(imported.id, imported)
        Log.i(TAG, "Imported remote conversation title=${imported.title}, messages=${imported.messageNodes.size}")

        setState {
            it.copy(
                importedCount = it.importedCount + 1,
                lastImportAt = System.currentTimeMillis(),
                lastError = null,
            )
        }
        return true
    }

    private suspend fun resolveImportAssistantId(syncConversation: SyncConversation, config: ChatSyncConfig): Uuid {
        config.targetAssistantId?.let { return it }
        syncConversation.assistant?.let { return upsertSyncedAssistant(it) }
        syncConversation.source.assistantId?.toUuidOrNull()?.let { return it }
        return settingsStore.settingsFlow.value.getCurrentAssistant().id
    }

    private suspend fun upsertSyncedAssistant(syncAssistant: SyncAssistant): Uuid {
        val assistantId = UniVcpChatSyncMapper.stableAssistantId(syncAssistant)
        val nextName = syncAssistant.name.ifBlank { syncAssistant.id }
        val nextPrompt = syncAssistant.systemPrompt
        var created = false
        var changed = false

        settingsStore.update { settings ->
            val existing = settings.assistants.find { it.id == assistantId }
            val nextAssistant = if (existing == null) {
                created = true
                settings.getCurrentAssistant().copy(
                    id = assistantId,
                    name = nextName,
                    systemPrompt = nextPrompt,
                )
            } else {
                existing.copy(
                    name = nextName,
                    systemPrompt = nextPrompt,
                )
            }

            if (existing == nextAssistant) {
                settings
            } else {
                changed = true
                settings.copy(
                    assistants = settings.assistants
                        .filterNot { it.id == assistantId }
                        .plus(nextAssistant)
                )
            }
        }

        when {
            created -> Log.i(TAG, "Imported assistant name=$nextName")
            changed -> Log.i(TAG, "Updated imported assistant name=$nextName")
        }
        return assistantId
    }

    private fun createStoreOrNull(config: ChatSyncConfig): ChatSyncRemoteStore? {
        if (!config.isFirebaseConfigured()) return null
        return FirebaseRtdbChatSyncRemoteStore(
            client = httpClient,
            config = FirebaseRtdbChatSyncConfig(
                databaseUrl = config.firebaseDatabaseUrl,
                roomId = config.roomId.ifBlank { "default" },
                deviceId = config.resolvedDeviceId(),
                authToken = config.firebaseAuthToken.takeIf { it.isNotBlank() },
            ),
            json = json,
        )
    }

    private fun suppressLocalWrite(conversationId: String) {
        suppressedLocalWrites[conversationId] = System.currentTimeMillis() + 5000L
    }

    private fun isSuppressedLocalWrite(conversationId: String): Boolean {
        val expiresAt = suppressedLocalWrites[conversationId] ?: return false
        if (expiresAt > System.currentTimeMillis()) return true
        suppressedLocalWrites.remove(conversationId)
        return false
    }

    private fun recordError(scope: String, error: Throwable) {
        Log.e(TAG, "$scope failed", error)
        setState {
            it.copy(lastError = "[$scope] ${error.message ?: error::class.simpleName.orEmpty()}")
        }
    }

    private fun setState(update: (ChatSyncState) -> ChatSyncState) {
        _state.value = update(_state.value)
    }

    private fun ChatSyncConfig.resolvedDeviceId(): String {
        return deviceId.ifBlank { "univcp-android" }
    }

    private fun String.toLocalConversationId(): Uuid {
        return runCatching {
            Uuid.parse(this)
        }.getOrElse {
            Uuid.parse(UUID.nameUUIDFromBytes("univcp-sync:$this".toByteArray(StandardCharsets.UTF_8)).toString())
        }
    }

    private fun String.toUuidOrNull(): Uuid? {
        return runCatching { Uuid.parse(this) }.getOrNull()
    }
}

data class ChatSyncState(
    val enabled: Boolean = false,
    val running: Boolean = false,
    val provider: String = "",
    val roomId: String = "default",
    val deviceId: String = "",
    val pushedCount: Int = 0,
    val importedCount: Int = 0,
    val lastPushAt: Long? = null,
    val lastImportAt: Long? = null,
    val lastError: String? = null,
)

enum class ChatSyncDirection {
    PUSH,
    PULL,
    BOTH,
}

data class ChatSyncRunResult(
    val pushed: Int = 0,
    val imported: Int = 0,
    val skippedReason: String? = null,
)
