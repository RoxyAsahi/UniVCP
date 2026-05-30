package me.rerere.rikkahub.data.datastore

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.ai.prompts.UNIVCP_RENDERING_PROMPT
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.PromptInjection
import kotlin.uuid.Uuid

private const val TAG = "DevProviderSeeder"

private val DEV_PROVIDER_ID = Uuid.parse("d7724cf2-5d6b-4e23-a90d-a2993aa3133a")
private val DEV_MODEL_ID = Uuid.parse("7dc42a2a-d2b1-4d97-a1d4-6329a8d77afd")

suspend fun SettingsStore.seedUniVcpDevProviderIfNeeded() {
    if (!BuildConfig.DEBUG || !BuildConfig.UNIVCP_DEV_PROVIDER_ENABLED) return

    val apiKey = BuildConfig.UNIVCP_DEV_PROVIDER_API_KEY.trim()
    val baseUrl = BuildConfig.UNIVCP_DEV_PROVIDER_BASE_URL.trim().trimEnd('/')
    val modelName = BuildConfig.UNIVCP_DEV_PROVIDER_MODEL.trim()
    val chatPath = BuildConfig.UNIVCP_DEV_PROVIDER_CHAT_PATH.trim().ifBlank { "/chat/completions" }

    if (apiKey.isBlank() || baseUrl.isBlank() || modelName.isBlank()) {
        Log.w(TAG, "UniVCP dev provider is enabled but missing required local.properties values")
        return
    }

    val devModel = Model(
        id = DEV_MODEL_ID,
        modelId = modelName,
        displayName = modelName,
        inputModalities = listOf(Modality.TEXT),
        outputModalities = listOf(Modality.TEXT),
    )
    val devProvider = ProviderSetting.OpenAI(
        id = DEV_PROVIDER_ID,
        enabled = true,
        name = BuildConfig.UNIVCP_DEV_PROVIDER_NAME.ifBlank { "UniVCP Dev Relay" },
        models = listOf(devModel),
        apiKey = apiKey,
        baseUrl = baseUrl,
        chatCompletionsPath = chatPath,
        useResponseApi = false,
    )

    val current = settingsFlowRaw.firstUsable()
    val providers = buildList {
        add(devProvider)
        current.providers
            .filterNot { it.id == DEV_PROVIDER_ID }
            .forEach(::add)
    }
    val next = current.copy(
        providers = providers,
        chatModelId = DEV_MODEL_ID,
    ).withUniVcpVisualPromptConfig()

    val currentDevProvider = current.providers.firstOrNull { it.id == DEV_PROVIDER_ID } as? ProviderSetting.OpenAI
    val alreadySeeded =
        currentDevProvider?.enabled == devProvider.enabled &&
            currentDevProvider.name == devProvider.name &&
            currentDevProvider.apiKey == devProvider.apiKey &&
            currentDevProvider.baseUrl == devProvider.baseUrl &&
            currentDevProvider.chatCompletionsPath == devProvider.chatCompletionsPath &&
            currentDevProvider.models == devProvider.models &&
            current.chatModelId == DEV_MODEL_ID &&
            current == next

    if (!alreadySeeded) {
        update(next)
        Log.i(TAG, "Seeded UniVCP dev OpenAI-compatible provider: ${devProvider.name} / $modelName")
    }
}

private suspend fun Flow<Settings>.firstUsable(): Settings {
    return first { !it.init }
}

private fun Settings.withUniVcpVisualPromptConfig(): Settings {
    val visualPromptInjection = PromptInjection.ModeInjection(
        id = UNIVCP_RENDERING_MODE_INJECTION_ID,
        content = UNIVCP_RENDERING_PROMPT,
        position = InjectionPosition.AFTER_SYSTEM_PROMPT,
        priority = 100,
        name = "UniVCP Visual Bubble",
    )
    val modeInjections = buildList {
        add(visualPromptInjection)
        this@withUniVcpVisualPromptConfig.modeInjections
            .filterNot { it.id == UNIVCP_RENDERING_MODE_INJECTION_ID }
            .forEach(::add)
    }
    val targetAssistantId = assistantId.takeIf { id -> assistants.any { it.id == id } }
        ?: DEFAULT_ASSISTANT_ID
    val assistants = assistants.map { assistant ->
        if (assistant.id == targetAssistantId) {
            assistant.copy(
                modeInjectionIds = assistant.modeInjectionIds + UNIVCP_RENDERING_MODE_INJECTION_ID,
            )
        } else {
            assistant
        }
    }
    return copy(
        modeInjections = modeInjections,
        assistants = assistants,
    )
}
