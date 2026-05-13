package com.univcp.android.data

import com.univcp.android.data.db.AgentDao
import com.univcp.android.data.db.AgentEntity
import com.univcp.android.data.db.MessageDao
import com.univcp.android.data.db.MessageEntity
import com.univcp.android.data.db.TopicDao
import com.univcp.android.data.db.TopicEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.util.UUID

private const val DEFAULT_AGENT_ID = "default-agent"
private const val DEFAULT_TOPIC_ID = "default-topic"

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
class ChatRepository(
    private val agentDao: AgentDao,
    private val topicDao: TopicDao,
    private val messageDao: MessageDao,
    private val settingsStore: SettingsStore,
    private val apiKeyStore: ApiKeyStore,
    private val providerManager: ProviderManager
) {
    fun observeMessages(): Flow<List<MessageEntity>> = messageDao.observeForTopic(DEFAULT_TOPIC_ID)

    suspend fun bootstrap() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (agentDao.find(DEFAULT_AGENT_ID) == null) {
            agentDao.upsert(
                AgentEntity(
                    id = DEFAULT_AGENT_ID,
                    name = "UniVCP 学习助手",
                    systemPrompt = DEFAULT_RENDERING_PROMPT,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
        if (topicDao.find(DEFAULT_TOPIC_ID) == null) {
            topicDao.upsert(
                TopicEntity(
                    id = DEFAULT_TOPIC_ID,
                    agentId = DEFAULT_AGENT_ID,
                    title = "学习气泡预研",
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        messageDao.clearTopic(DEFAULT_TOPIC_ID)
    }

    suspend fun addRenderSamples() = withContext(Dispatchers.IO) {
        bootstrap()
        val now = System.currentTimeMillis()
        messageDao.insert(
            assistantMessage(
                content = """
                    下面是一个离线渲染样例，包含 **Markdown**、表格、公式和 Mermaid：

                    | 能力 | 状态 |
                    | --- | --- |
                    | Markdown | ready |
                    | KaTeX | ${'$'}E = mc^2${'$'} |
                    | Mermaid | ready |

                    ```mermaid
                    flowchart LR
                      A[用户问题] --> B[模型输出]
                      B --> C{气泡类型}
                      C -->|Markdown| D[原生增强]
                      C -->|HTML/SVG| E[WebView 沙箱]
                      E --> F[高度回传]
                    ```
                """.trimIndent(),
                renderMode = "MARKDOWN",
                createdAt = now
            )
        )
        messageDao.insert(
            assistantMessage(
                content = """
                    <style>
                      .study-card { border: 1px solid #93c5fd; border-radius: 10px; padding: 14px; background: linear-gradient(135deg,#eff6ff,#ffffff); }
                      .study-card h3 { margin: 0 0 8px; color: #1d4ed8; }
                      .study-card .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
                      .study-card .pill { background: #dbeafe; border-radius: 999px; padding: 6px 10px; text-align: center; }
                    </style>
                    <section class="study-card">
                      <h3>圆锥体积速记</h3>
                      <p>同底等高时，圆锥体积是圆柱的三分之一。</p>
                      <div class="grid">
                        <div class="pill">圆柱：V = πr²h</div>
                        <div class="pill">圆锥：V = 1/3πr²h</div>
                      </div>
                    </section>
                """.trimIndent(),
                renderMode = "RICH_HTML",
                createdAt = now + 1
            )
        )
        messageDao.insert(
            assistantMessage(
                content = """
                    <svg viewBox="0 0 360 180" xmlns="http://www.w3.org/2000/svg">
                      <rect width="360" height="180" rx="14" fill="#f8fafc"/>
                      <circle cx="96" cy="92" r="46" fill="#bfdbfe" stroke="#2563eb" stroke-width="3"/>
                      <path d="M210 40 L290 92 L210 144 Z" fill="#bbf7d0" stroke="#16a34a" stroke-width="3"/>
                      <text x="96" y="100" text-anchor="middle" font-size="18" fill="#1e3a8a">概念</text>
                      <text x="244" y="100" text-anchor="middle" font-size="18" fill="#14532d">迁移</text>
                    </svg>
                """.trimIndent(),
                renderMode = "CODE_PREVIEW",
                language = "svg",
                allowScript = true,
                createdAt = now + 2
            )
        )
        messageDao.insert(
            assistantMessage(
                content = """
                    const scene = new THREE.Scene();
                    scene.background = new THREE.Color(0x0f172a);
                    const camera = new THREE.PerspectiveCamera(65, 1.6, 0.1, 100);
                    camera.position.z = 4;
                    const renderer = new THREE.WebGLRenderer({ antialias: true });
                    renderer.setSize(520, 320);
                    const geometry = new THREE.BoxGeometry(1.4, 1.4, 1.4);
                    const material = new THREE.MeshNormalMaterial();
                    const cube = new THREE.Mesh(geometry, material);
                    scene.add(cube);
                    function animate() {
                      cube.rotation.x += 0.012;
                      cube.rotation.y += 0.018;
                      renderer.render(scene, camera);
                      requestAnimationFrame(animate);
                    }
                    animate();
                """.trimIndent(),
                renderMode = "THREE",
                language = "javascript",
                allowScript = true,
                createdAt = now + 3
            )
        )
    }

    suspend fun sendUserMessage(input: String) = withContext(Dispatchers.IO) {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return@withContext
        bootstrap()
        val now = System.currentTimeMillis()
        messageDao.insert(
            MessageEntity(
                id = UUID.randomUUID().toString(),
                topicId = DEFAULT_TOPIC_ID,
                role = "user",
                content = trimmed,
                renderMode = "PLAIN",
                createdAt = now,
                updatedAt = now
            )
        )

        val assistant = assistantMessage(content = "", renderMode = "MARKDOWN", isStreaming = true, createdAt = now + 1)
        messageDao.insert(assistant)

        val settings = settingsStore.settingsFlow.first()
        val apiKey = apiKeyStore.apiKeyFlow.first()
        if (apiKey.isBlank()) {
            val fallback = """
                <style>
                  .uvcp-local { padding: 12px; border: 1px solid #a7f3d0; border-radius: 10px; background: #ecfdf5; }
                  .uvcp-local strong { color: #047857; }
                </style>
                <div class="uvcp-local">
                  <strong>本地预研模式</strong>
                  <p>你刚刚输入：${escapeForHtml(trimmed)}</p>
                  <p>配置 OpenAI-compatible API Key 后，这里会变成真实流式回复。当前先用这个 HTML 学习气泡验证渲染链路。</p>
                </div>
            """.trimIndent()
            messageDao.update(
                assistant.copy(
                    content = fallback,
                    renderMode = "RICH_HTML",
                    isStreaming = false,
                    updatedAt = System.currentTimeMillis()
                )
            )
            return@withContext
        }

        runCatching {
            val model = Model(modelId = settings.modelId, displayName = settings.modelId)
            val providerSetting = ProviderSetting.OpenAI(
                apiKey = apiKey,
                baseUrl = settings.baseUrl.trimEnd('/'),
                models = listOf(model)
            )
            val provider = providerManager.getProviderByType(providerSetting)
            val messages = listOf(
                UIMessage.system(settings.systemPrompt),
                UIMessage.user(trimmed)
            )
            var accumulated = ""
            var lastFlush = 0L
            provider.streamText(
                providerSetting = providerSetting,
                messages = messages,
                params = TextGenerationParams(model = model)
            ).collect { chunk ->
                accumulated += chunk.deltaText()
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastFlush >= 130L) {
                    lastFlush = nowMs
                    messageDao.update(
                        assistant.copy(
                            content = accumulated,
                            renderMode = inferRenderMode(accumulated),
                            isStreaming = true,
                            updatedAt = nowMs
                        )
                    )
                }
            }
            messageDao.update(
                assistant.copy(
                    content = accumulated.ifBlank { "(empty response)" },
                    renderMode = inferRenderMode(accumulated),
                    isStreaming = false,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }.onFailure { error ->
            messageDao.update(
                assistant.copy(
                    content = "请求失败：${error.message ?: error::class.java.simpleName}",
                    renderMode = "MARKDOWN",
                    isStreaming = false,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    private fun assistantMessage(
        content: String,
        renderMode: String,
        language: String = "",
        allowScript: Boolean = false,
        isStreaming: Boolean = false,
        createdAt: Long
    ): MessageEntity {
        return MessageEntity(
            id = UUID.randomUUID().toString(),
            topicId = DEFAULT_TOPIC_ID,
            role = "assistant",
            content = content,
            renderMode = renderMode,
            language = language,
            allowScript = allowScript,
            isStreaming = isStreaming,
            createdAt = createdAt,
            updatedAt = createdAt
        )
    }

    private fun MessageChunk.deltaText(): String {
        return choices.joinToString("") { choice ->
            val message = choice.delta ?: choice.message
            message?.parts.orEmpty().joinToString("") { part ->
                when (part) {
                    is UIMessagePart.Text -> part.text
                    is UIMessagePart.Reasoning -> ""
                    else -> ""
                }
            }
        }
    }

    private fun inferRenderMode(text: String): String {
        val trimmed = text.trim()
        return if (Regex("</?[a-zA-Z][\\s\\S]*>").containsMatchIn(trimmed)) {
            "RICH_HTML"
        } else {
            "MARKDOWN"
        }
    }

    private fun escapeForHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#039;")
    }
}
