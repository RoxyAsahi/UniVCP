package com.univcp.android.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("univcp_settings")

data class ModelSettings(
    val baseUrl: String = "https://api.openai.com/v1",
    val modelId: String = "gpt-4.1-mini",
    val systemPrompt: String = DEFAULT_RENDERING_PROMPT
)

class SettingsStore(private val context: Context) {
    private val baseUrlKey = stringPreferencesKey("model_base_url")
    private val modelIdKey = stringPreferencesKey("model_id")
    private val systemPromptKey = stringPreferencesKey("system_prompt")

    val settingsFlow: Flow<ModelSettings> = context.settingsDataStore.data.map { prefs ->
        ModelSettings(
            baseUrl = prefs[baseUrlKey] ?: "https://api.openai.com/v1",
            modelId = prefs[modelIdKey] ?: "gpt-4.1-mini",
            systemPrompt = prefs[systemPromptKey] ?: DEFAULT_RENDERING_PROMPT
        )
    }

    suspend fun save(settings: ModelSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[baseUrlKey] = settings.baseUrl
            prefs[modelIdKey] = settings.modelId
            prefs[systemPromptKey] = settings.systemPrompt
        }
    }
}

const val DEFAULT_RENDERING_PROMPT = """
你是 UniVCP 的移动学习助手。回答可以使用 Markdown、LaTeX、Mermaid，也可以在需要结构化呈现时直接输出裸 HTML/SVG 片段。
当输出 HTML 时不要包含完整 html/head/body 外壳；系统会把片段放入受控学习气泡中渲染。优先用清晰、可复习、适合手机屏幕的学习卡片。
"""
