package me.rerere.rikkahub.data.sync.chat

import android.util.Log
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
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
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import okhttp3.OkHttpClient
import kotlin.uuid.Uuid

private const val TAG = "ChatSyncManager"
private const val VCPCHAT_PRESENCE_STALE_MS = 90_000L
private const val VCPCHAT_PRESENCE_POLL_MS = 15_000L

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
            loadCachedEmoticonLibrary()
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
                    provider = ChatSyncProvider.FIREBASE_RTDB,
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
        val store = createStoreOrNull(config) ?: return ChatSyncRunResult(skippedReason = "${config.provider.displayName()} is not configured")
        val mode = config.mode
        val allowsPush = mode.allowsPush()
        val allowsPull = mode.allowsPull()

        return withContext(Dispatchers.IO) {
            when (direction) {
                ChatSyncDirection.PUSH -> {
                    if (!allowsPush) {
                        return@withContext ChatSyncRunResult(skippedReason = "当前模式仅允许拉取")
                    }
                    val pushed = pushAllLocal(store, config)
                    ChatSyncRunResult(pushed = pushed)
                }

                ChatSyncDirection.PULL -> {
                    if (!allowsPull) {
                        return@withContext ChatSyncRunResult(skippedReason = "当前模式仅允许推送")
                    }
                    val imported = pullRemoteOnce(store, config)
                    ChatSyncRunResult(imported = imported)
                }

                ChatSyncDirection.BOTH -> {
                    val skipped = mutableListOf<String>()
                    val imported = if (allowsPull) {
                        pullRemoteOnce(store, config)
                    } else {
                        skipped += "拉取已由当前模式关闭"
                        0
                    }
                    val pushed = if (allowsPush) {
                        pushAllLocal(store, config)
                    } else {
                        skipped += "推送已由当前模式关闭"
                        0
                    }
                    ChatSyncRunResult(
                        pushed = pushed,
                        imported = imported,
                        skippedReason = skipped.takeIf { it.isNotEmpty() }?.joinToString("；"),
                    )
                }
            }
        }
    }

    suspend fun repairNow(): ChatSyncRepairResult {
        val config = settingsStore.settingsFlow.value.chatSyncConfig
        if (!config.mode.allowsPull()) {
            return ChatSyncRepairResult(skippedReason = "当前模式不允许拉取，无法修复")
        }
        val store = createStoreOrNull(config)
            ?: return ChatSyncRepairResult(skippedReason = "${config.provider.displayName()} is not configured")

        return withContext(Dispatchers.IO) {
            val result = store.pullChanges(ChatSyncCursor(updatedAfter = 0L))
            var repaired = 0
            result.conversations.forEach { conversation ->
                if (applyRemoteConversation(conversation, config, forceRepair = true)) {
                    repaired += 1
                }
            }
            if (config.incrementalPull) {
                result.nextCursor?.let { advanceRemoteCursor(config, it) }
            }
            ChatSyncRepairResult(checked = result.conversations.size, repaired = repaired)
        }
    }

    suspend fun testConnection() {
        val config = settingsStore.settingsFlow.value.chatSyncConfig
        val store = createStoreOrNull(config) ?: error("${config.provider.displayName()} is not configured")
        withContext(Dispatchers.IO) {
            if (store is VcpChatLanSyncRemoteStore) {
                store.getDiagnostics()
            }
            store.pullChanges(ChatSyncCursor(updatedAfter = Long.MAX_VALUE))
        }
    }

    suspend fun listVcpChatLanTargets(): VcpChatLanTargetsResult {
        val config = settingsStore.settingsFlow.value.chatSyncConfig
        val store = createStoreOrNull(config) as? VcpChatLanSyncRemoteStore
            ?: error("VCPChat LAN is not configured")
        return withContext(Dispatchers.IO) {
            store.listTargets()
        }
    }

    suspend fun syncVcpChatEmoticonLibrary(regenerate: Boolean = false): VcpChatEmoticonLibrarySnapshot {
        val config = settingsStore.settingsFlow.value.chatSyncConfig
        val store = createStoreOrNull(config) as? VcpChatLanSyncRemoteStore
            ?: error("VCPChat LAN is not configured")
        return withContext(Dispatchers.IO) {
            val snapshot = store.fetchEmoticonLibrary(regenerate = regenerate)
            settingsStore.updateVcpChatEmoticonLibrary(snapshot)
            VcpChatEmoticonLibraryRegistry.update(snapshot)
            snapshot
        }
    }

    private suspend fun loadCachedEmoticonLibrary() {
        val snapshot = settingsStore.getVcpChatEmoticonLibrary()
        if (snapshot.items.isNotEmpty()) {
            VcpChatEmoticonLibraryRegistry.update(snapshot)
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
                    lastError = "${config.provider.displayName()} is not configured",
                )
            }
            return
        }

        remoteCursor = if (config.incrementalPull) loadRemoteCursor(config) else null
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

            if (config.importRemoteOnStart && config.mode.allowsPull()) {
                runCatching {
                    pullRemoteOnce(store, config)
                }.onFailure { error ->
                    recordError("initialPull", error)
                }
            }

            coroutineScope {
                if (config.pushOnStart && config.mode.allowsPush()) {
                    launch {
                        runCatching {
                            pushAllLocal(store, config)
                        }.onFailure { error ->
                            recordError("pushOnStart", error)
                        }
                    }
                }

                if (config.mode.allowsPull()) {
                    launch {
                        observeRemoteLoop(store, config)
                    }
                }
                if (config.mode.allowsPush()) {
                    launch {
                        observeLocalChanges(store, config)
                    }
                }
                launch {
                    observeVcpChatPresenceLoop(store)
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
                store.observeChanges(ensureRemoteCursor(config)).collect { event ->
                    applyRemoteConversation(event.conversation, config)
                    if (config.incrementalPull) {
                        advanceRemoteCursor(config, event.cursor)
                    }
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
            // Deletions are intentionally local-only: never emit tombstones or remote delete writes from Android.
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

    private suspend fun observeVcpChatPresenceLoop(store: ChatSyncRemoteStore) {
        while (currentCoroutineContext().isActive) {
            runCatching {
                refreshVcpChatPresence(store)
            }.onFailure { error ->
                Log.w(TAG, "presence check failed: ${error.message}")
                setState { it.copy(vcpChatPresence = ChatSyncPeerStatus.Unknown) }
            }
            delay(VCPCHAT_PRESENCE_POLL_MS)
        }
    }

    private suspend fun refreshVcpChatPresence(store: ChatSyncRemoteStore) {
        val peers = store.getPresence()
        val latestVcpChat = peers
            .filter { peer -> peer.app == CHAT_SYNC_APP_VCPCHAT || peer.deviceId.startsWith("vcpchat") }
            .maxByOrNull { it.updatedAt }

        val status = when {
            latestVcpChat == null -> ChatSyncPeerStatus.Unknown
            latestVcpChat.status == "offline" -> ChatSyncPeerStatus.Offline(latestVcpChat.deviceId, latestVcpChat.updatedAt)
            System.currentTimeMillis() - latestVcpChat.updatedAt <= VCPCHAT_PRESENCE_STALE_MS ->
                ChatSyncPeerStatus.Online(
                    deviceId = latestVcpChat.deviceId,
                    updatedAt = latestVcpChat.updatedAt,
                    direction = latestVcpChat.direction,
                    version = latestVcpChat.version,
                )

            else -> ChatSyncPeerStatus.Offline(latestVcpChat.deviceId, latestVcpChat.updatedAt)
        }
        setState { it.copy(vcpChatPresence = status) }
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

    private suspend fun pushConversation(
        store: ChatSyncRemoteStore,
        config: ChatSyncConfig,
        conversationId: String,
    ): Boolean {
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
        val result = store.pullChanges(ensureRemoteCursor(config))
        var imported = 0
        result.conversations.forEach { conversation ->
            if (applyRemoteConversation(conversation, config)) {
                imported += 1
            }
        }
        if (config.incrementalPull) {
            result.nextCursor?.let { advanceRemoteCursor(config, it) }
        }
        Log.i(TAG, "Pulled ${result.conversations.size} remote conversations, imported=$imported")
        return imported
    }

    private suspend fun ensureRemoteCursor(config: ChatSyncConfig): ChatSyncCursor? {
        if (!config.incrementalPull) return null
        return remoteCursor ?: loadRemoteCursor(config).also { remoteCursor = it }
    }

    private suspend fun loadRemoteCursor(config: ChatSyncConfig): ChatSyncCursor? {
        val updatedAfter = settingsStore.getChatSyncRemoteCursor(config.remoteCursorKey())
        return ChatSyncCursor(updatedAfter = updatedAfter).takeIf { updatedAfter > 0L }
    }

    private suspend fun advanceRemoteCursor(config: ChatSyncConfig, cursor: ChatSyncCursor) {
        val updatedAfter = cursor.updatedAfter ?: return
        val current = remoteCursor?.updatedAfter ?: 0L
        if (updatedAfter <= current) return
        remoteCursor = cursor
        settingsStore.updateChatSyncRemoteCursor(config.remoteCursorKey(), updatedAfter)
    }

    private suspend fun applyRemoteConversation(
        syncConversation: SyncConversation,
        config: ChatSyncConfig,
        forceRepair: Boolean = false,
    ): Boolean {
        if (!syncConversation.hasConsistentVcpScope()) {
            recordError(
                "applyRemoteConversation",
                IllegalStateException(
                    "Remote VCPChat conversation has mixed Agent/Topic sources: ${syncConversation.id}"
                )
            )
            return false
        }
        val scopedConversation = syncConversation.withScopedIdentity()
        val localId = scopedConversation.id.toLocalConversationId()
        val targetAssistantId = resolveImportAssistantId(scopedConversation, config)
        val existing = conversationRepository.getConversationById(localId)
        val imported = UniVcpChatSyncMapper.importConversation(
            syncConversation = scopedConversation,
            assistantId = targetAssistantId,
        )

        if (existing != null) {
            val merged = existing.mergeAdditiveRemote(imported, targetAssistantId)
            if (merged == existing) {
                Log.i(TAG, "Skipped remote conversation title=${scopedConversation.title}, no additive changes")
                return false
            }

            saveImportedConversation(merged)
            Log.i(
                TAG,
                "Merged remote conversation title=${merged.title}, " +
                    "localNodes=${existing.messageNodes.size}, mergedNodes=${merged.messageNodes.size}, " +
                    "forceRepair=$forceRepair"
            )
            return true
        }

        if (imported.messageNodes.isEmpty()) {
            Log.i(TAG, "Skipped empty remote conversation title=${imported.title}")
            return false
        }
        saveImportedConversation(imported)
        Log.i(TAG, "Imported remote conversation title=${imported.title}, messages=${imported.messageNodes.size}")
        return true
    }

    private suspend fun saveImportedConversation(imported: Conversation) {
        suppressLocalWrite(imported.id.toString())
        chatService.saveConversation(imported.id, imported)
        setState {
            it.copy(
                importedCount = it.importedCount + 1,
                lastImportAt = System.currentTimeMillis(),
                lastError = null,
            )
        }
    }

    private fun Conversation.mergeAdditiveRemote(
        remote: Conversation,
        targetAssistantId: Uuid,
    ): Conversation {
        val knownMessageIds = messageNodes
            .flatMap { node -> node.messages.map { message -> message.id } }
            .toMutableSet()
        val missingRemoteNodes = remote.messageNodes.filter { node ->
            node.messages.any { message -> knownMessageIds.add(message.id) }
        }

        return copy(
            assistantId = targetAssistantId,
            title = title.ifBlank { remote.title },
            messageNodes = messageNodes + missingRemoteNodes,
            chatSuggestions = (chatSuggestions + remote.chatSuggestions).distinct(),
            isPinned = isPinned || remote.isPinned,
            createAt = minOf(createAt, remote.createAt),
            updateAt = maxOf(updateAt, remote.updateAt),
        )
    }

    private suspend fun resolveImportAssistantId(syncConversation: SyncConversation, config: ChatSyncConfig): Uuid {
        config.targetAssistantId?.let { return it }
        if (syncConversation.source.app == CHAT_SYNC_APP_VCPCHAT) {
            syncConversation.source.agentId?.takeIf { it.isNotBlank() }?.let { agentId ->
                val assistant = syncConversation.assistant
                return upsertSyncedAssistant(
                    SyncAssistant(
                        id = agentId,
                        name = assistant?.name.orEmpty().ifBlank { agentId },
                        systemPrompt = assistant?.systemPrompt.orEmpty(),
                        source = (assistant?.source ?: syncConversation.source).copy(
                            app = CHAT_SYNC_APP_VCPCHAT,
                            agentId = agentId,
                            itemType = syncConversation.source.itemType,
                        ),
                        metadata = assistant?.metadata ?: emptyJsonObject(),
                    )
                )
            }
        }
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
        return when (config.provider) {
            ChatSyncProvider.FIREBASE_RTDB -> {
                if (!config.isFirebaseConfigured()) return null
                FirebaseRtdbChatSyncRemoteStore(
                    client = httpClient,
                    config = FirebaseRtdbChatSyncConfig(
                        databaseUrl = config.firebaseDatabaseUrl,
                        roomId = config.roomId.ifBlank { "default" },
                        deviceId = config.resolvedDeviceId(),
                        authToken = config.firebaseAuthToken.takeIf { it.isNotBlank() },
                        incrementalPull = config.incrementalPull,
                    ),
                    json = json,
                )
            }

            ChatSyncProvider.VCPCHAT_LAN -> {
                if (!config.isVcpChatLanConfigured()) return null
                VcpChatLanSyncRemoteStore(
                    client = httpClient,
                    config = VcpChatLanSyncConfig(
                        baseUrl = config.vcpChatLanBaseUrl,
                        authToken = config.vcpChatLanToken.takeIf { it.isNotBlank() },
                        deviceId = config.resolvedDeviceId(),
                        pollIntervalMs = config.vcpChatLanPollIntervalMs,
                        realtimeEvents = config.vcpChatLanRealtimeEvents,
                        targetItemType = config.vcpChatLanTargetItemType.ifBlank { "agent" },
                        targetItemId = config.vcpChatLanTargetItemId.takeIf { it.isNotBlank() },
                        targetTopicId = config.vcpChatLanTargetTopicId.takeIf { it.isNotBlank() },
                    ),
                    json = json,
                )
            }
        }
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
        if (error is CancellationException) return
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

    private fun SyncConversation.withScopedIdentity(): SyncConversation {
        if (source.app != CHAT_SYNC_APP_VCPCHAT) return this

        val agentId = source.agentId?.takeIf { it.isNotBlank() }
            ?: messages.mapNotNull { it.source?.agentId?.takeIf(String::isNotBlank) }.singleDistinctOrNull()
            ?: return this
        val topicId = source.topicId?.takeIf { it.isNotBlank() }
            ?: messages.mapNotNull { it.source?.topicId?.takeIf(String::isNotBlank) }.singleDistinctOrNull()
            ?: return this
        val scopedId = "vcpchat:$agentId:$topicId"
        val scopedSource = source.copy(
            app = CHAT_SYNC_APP_VCPCHAT,
            agentId = agentId,
            topicId = topicId,
        )
        val scopedMessages = messages.map { message ->
            val messageSource = message.source
            if (messageSource?.app == CHAT_SYNC_APP_VCPCHAT) {
                message.copy(
                    source = messageSource.copy(
                        app = CHAT_SYNC_APP_VCPCHAT,
                        agentId = agentId,
                        topicId = topicId,
                        itemType = messageSource.itemType ?: scopedSource.itemType,
                    )
                )
            } else {
                message
            }
        }

        if (id != scopedId) {
            Log.w(TAG, "Repaired VCPChat conversation id from $id to $scopedId")
        }
        return copy(id = scopedId, source = scopedSource, messages = scopedMessages)
    }

    private fun SyncConversation.hasConsistentVcpScope(): Boolean {
        if (source.app != CHAT_SYNC_APP_VCPCHAT) return true

        val sourceAgentId = source.agentId?.takeIf(String::isNotBlank)
        val sourceTopicId = source.topicId?.takeIf(String::isNotBlank)
        val messageAgentIds = messages.mapNotNull { it.source?.agentId?.takeIf(String::isNotBlank) }.distinct()
        val messageTopicIds = messages.mapNotNull { it.source?.topicId?.takeIf(String::isNotBlank) }.distinct()

        val agentMatches = sourceAgentId?.let { agentId ->
            messageAgentIds.isEmpty() || messageAgentIds.all { it == agentId }
        } ?: (messageAgentIds.size <= 1)

        val topicMatches = sourceTopicId?.let { topicId ->
            messageTopicIds.isEmpty() || messageTopicIds.all { it == topicId }
        } ?: (messageTopicIds.size <= 1)

        return agentMatches && topicMatches
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

    private fun List<String>.singleDistinctOrNull(): String? {
        val distinct = distinct()
        return distinct.singleOrNull()
    }
}

private fun ChatSyncConfig.remoteCursorKey(): String {
    return when (provider) {
        ChatSyncProvider.FIREBASE_RTDB -> listOf(
            provider.name,
            firebaseDatabaseUrl.trim().trimEnd('/'),
            roomId.ifBlank { "default" },
        )

        ChatSyncProvider.VCPCHAT_LAN -> listOf(
            provider.name,
            vcpChatLanBaseUrl.trim().trimEnd('/'),
        )
    }.joinToString("|")
}

private fun ChatSyncProvider.displayName(): String {
    return when (this) {
        ChatSyncProvider.FIREBASE_RTDB -> "Firebase Realtime Database"
        ChatSyncProvider.VCPCHAT_LAN -> "VCPChat LAN"
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
    val vcpChatPresence: ChatSyncPeerStatus = ChatSyncPeerStatus.Unknown,
)

sealed interface ChatSyncPeerStatus {
    data object Unknown : ChatSyncPeerStatus

    data class Online(
        val deviceId: String,
        val updatedAt: Long,
        val direction: String?,
        val version: String? = null,
    ) : ChatSyncPeerStatus

    data class Offline(
        val deviceId: String,
        val updatedAt: Long,
    ) : ChatSyncPeerStatus
}

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

data class ChatSyncRepairResult(
    val checked: Int = 0,
    val repaired: Int = 0,
    val skippedReason: String? = null,
)
