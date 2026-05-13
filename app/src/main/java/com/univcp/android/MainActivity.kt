package com.univcp.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.univcp.android.data.ApiKeyStore
import com.univcp.android.data.ChatRepository
import com.univcp.android.data.ModelSettings
import com.univcp.android.data.SettingsStore
import com.univcp.android.data.db.MessageEntity
import com.univcp.bubble.BubblePayload
import com.univcp.bubble.BubbleRenderMode
import com.univcp.bubble.BubbleTheme
import com.univcp.bubble.BubbleWebView
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    private val chatRepository by inject<ChatRepository>()
    private val settingsStore by inject<SettingsStore>()
    private val apiKeyStore by inject<ApiKeyStore>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = if (dark) darkColorScheme() else lightColorScheme()
            ) {
                UniVcpAppScreen(
                    chatRepository = chatRepository,
                    settingsStore = settingsStore,
                    apiKeyStore = apiKeyStore
                )
            }
        }
    }
}

@Composable
private fun UniVcpAppScreen(
    chatRepository: ChatRepository,
    settingsStore: SettingsStore,
    apiKeyStore: ApiKeyStore
) {
    val scope = rememberCoroutineScope()
    val messages by chatRepository.observeMessages().collectAsState(initial = emptyList())
    val settings by settingsStore.settingsFlow.collectAsState(initial = ModelSettings())
    val savedApiKey by apiKeyStore.apiKeyFlow.collectAsState(initial = "")
    val listState = rememberLazyListState()

    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var modelId by remember { mutableStateOf(settings.modelId) }
    var apiKey by remember { mutableStateOf(savedApiKey) }
    var input by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        chatRepository.bootstrap()
    }
    LaunchedEffect(settings) {
        baseUrl = settings.baseUrl
        modelId = settings.modelId
    }
    LaunchedEffect(savedApiKey) {
        apiKey = savedApiKey
    }
    LaunchedEffect(messages.size, messages.lastOrNull()?.updatedAt) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .imePadding()
    ) {
        Header(
            baseUrl = baseUrl,
            modelId = modelId,
            apiKey = apiKey,
            onBaseUrlChange = { baseUrl = it },
            onModelIdChange = { modelId = it },
            onApiKeyChange = { apiKey = it },
            onSave = {
                scope.launch {
                    settingsStore.save(settings.copy(baseUrl = baseUrl.trim(), modelId = modelId.trim()))
                    apiKeyStore.saveApiKey(apiKey.trim())
                }
            },
            onSamples = {
                scope.launch { chatRepository.addRenderSamples() }
            },
            onClear = {
                scope.launch { chatRepository.clear() }
            }
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                MessageBubble(message = message)
            }
            item { Spacer(Modifier.height(4.dp)) }
        }

        ComposerBar(
            input = input,
            onInputChange = { input = it },
            onSend = {
                val text = input
                input = ""
                scope.launch { chatRepository.sendUserMessage(text) }
            }
        )
    }
}

@Composable
private fun Header(
    baseUrl: String,
    modelId: String,
    apiKey: String,
    onBaseUrlChange: (String) -> Unit,
    onModelIdChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onSave: () -> Unit,
    onSamples: () -> Unit,
    onClear: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("UniVCP", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Kotlin native shell + WebView learning bubble renderer",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(onClick = onSamples) { Text("样例") }
                OutlinedButton(onClick = onClear) { Text("清空") }
            }
            OutlinedTextField(
                value = baseUrl,
                onValueChange = onBaseUrlChange,
                label = { Text("OpenAI-compatible Base URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = modelId,
                    onValueChange = onModelIdChange,
                    label = { Text("Model") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = onApiKeyChange,
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.weight(1f)
                )
            }
            Button(
                onClick = onSave,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("保存设置")
            }
        }
    }
}

@Composable
private fun MessageBubble(message: MessageEntity) {
    val isUser = message.role == "user"
    val shape = RoundedCornerShape(
        topStart = 14.dp,
        topEnd = 14.dp,
        bottomStart = if (isUser) 14.dp else 4.dp,
        bottomEnd = if (isUser) 4.dp else 14.dp
    )
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        if (isUser || message.renderMode == "PLAIN") {
            Surface(
                shape = shape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.widthIn(max = 330.dp)
            ) {
                Text(
                    text = message.content,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            AssistantRenderBubble(message = message, shape = shape)
        }
    }
}

@Composable
private fun AssistantRenderBubble(message: MessageEntity, shape: RoundedCornerShape) {
    val colorScheme = MaterialTheme.colorScheme
    val interactiveEnabled = remember { mutableStateMapOf<String, Boolean>() }
    val mode = remember(message.renderMode) {
        runCatching { BubbleRenderMode.valueOf(message.renderMode) }.getOrElse { BubbleRenderMode.MARKDOWN }
    }
    val requiresRun = mode == BubbleRenderMode.CODE_PREVIEW || mode == BubbleRenderMode.THREE
    val hasRun = interactiveEnabled[message.id] == true

    Surface(
        shape = shape,
        color = colorScheme.surface,
        contentColor = colorScheme.onSurface,
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colorScheme.outlineVariant, shape)
            .clip(shape)
    ) {
        Column {
            if (requiresRun && !hasRun) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("交互预览已折叠", fontWeight = FontWeight.SemiBold)
                    Text(
                        message.content.take(260),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                    Button(onClick = { interactiveEnabled[message.id] = true }) {
                        Text("运行预览")
                    }
                }
            } else {
                BubbleWebView(
                    payload = BubblePayload(
                        id = message.id,
                        rawContent = message.content,
                        renderMode = mode,
                        language = message.language,
                        theme = BubbleTheme(
                            dark = isSystemInDarkTheme(),
                            background = colorScheme.background.toCssHex(),
                            onBackground = colorScheme.onBackground.toCssHex(),
                            surface = colorScheme.surfaceVariant.toCssHex(),
                            onSurface = colorScheme.onSurface.toCssHex(),
                            primary = colorScheme.primary.toCssHex(),
                            outline = colorScheme.outlineVariant.toCssHex()
                        ),
                        isStreaming = message.isStreaming,
                        allowScript = message.allowScript && (!requiresRun || hasRun)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun ComposerBar(
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Surface(
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                label = { Text("提问或让 AI 输出一个学习气泡") },
                minLines = 1,
                maxLines = 4,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = onSend,
                enabled = input.isNotBlank(),
                modifier = Modifier.size(width = 76.dp, height = 56.dp)
            ) {
                Text("发送")
            }
        }
    }
}

private fun Color.toCssHex(): String {
    val color = toArgb()
    return "#%02X%02X%02X".format(
        android.graphics.Color.red(color),
        android.graphics.Color.green(color),
        android.graphics.Color.blue(color)
    )
}
