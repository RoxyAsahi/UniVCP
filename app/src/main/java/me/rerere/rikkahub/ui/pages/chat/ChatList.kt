package me.rerere.rikkahub.ui.pages.chat

import android.os.SystemClock
import android.view.Choreographer
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.ArrowDownDouble
import me.rerere.hugeicons.stroke.ArrowUpDouble
import me.rerere.hugeicons.stroke.CursorPointer01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Cancel01
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalScrollCaptureInProgress
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtLeast
import androidx.compose.ui.zIndex
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.ui.components.message.ChatMessage
import me.rerere.rikkahub.ui.components.message.ChatRenderCell
import me.rerere.rikkahub.ui.components.message.ChatRenderCellItem
import me.rerere.rikkahub.ui.components.message.RichHtmlRenderTelemetry
import me.rerere.rikkahub.ui.components.message.inlineDynamicWebViewActiveCount
import me.rerere.rikkahub.ui.components.message.messageMetaOrNull
import me.rerere.rikkahub.ui.components.richtext.LocalRichRenderScrollState
import me.rerere.rikkahub.ui.components.richtext.RichHtmlPrewarmTarget
import me.rerere.rikkahub.ui.components.richtext.RichHtmlRenderScheduler
import me.rerere.rikkahub.ui.components.richtext.RichRenderScrollDirection
import me.rerere.rikkahub.ui.components.richtext.RichRenderScrollState
import me.rerere.rikkahub.ui.components.ui.ErrorCardsDisplay
import me.rerere.rikkahub.ui.components.ui.RabbitLoadingIndicator
import me.rerere.rikkahub.ui.components.ui.Tooltip
import me.rerere.rikkahub.ui.hooks.ImeLazyListAutoScroller
import me.rerere.rikkahub.utils.plus
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

private const val TAG = "ChatList"
private const val LoadingIndicatorKey = "LoadingIndicator"
private const val ScrollBottomKey = "ScrollBottomKey"
private val ChatListContentPadding = 16.dp

@Composable
internal fun ChatList(
    innerPadding: PaddingValues,
    conversation: Conversation,
    chatRenderCells: List<ChatRenderCell>,
    state: LazyListState,
    loading: Boolean,
    processingStatus: String? = null,
    previewMode: Boolean,
    settings: Settings,
    hazeState: HazeState,
    errors: List<ChatError> = emptyList(),
    onDismissError: (Uuid) -> Unit = {},
    onClearAllErrors: () -> Unit = {},
    onRegenerate: (UIMessage) -> Unit = {},
    onEdit: (UIMessage) -> Unit = {},
    onForkMessage: (UIMessage) -> Unit = {},
    onDelete: (UIMessage) -> Unit = {},
    onUpdateMessage: (MessageNode) -> Unit = {},
    onClickSuggestion: (String) -> Unit = {},
    onTranslate: ((UIMessage, java.util.Locale) -> Unit)? = null,
    onClearTranslation: (UIMessage) -> Unit = {},
    onJumpToMessage: (Int) -> Unit = {},
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onToggleFavorite: ((MessageNode) -> Unit)? = null,
    onBubbleInput: (String) -> Unit = {},
) {
    AnimatedContent(
        targetState = previewMode,
        label = "ChatListMode",
        transitionSpec = {
            (fadeIn() + scaleIn(initialScale = 0.8f) togetherWith fadeOut() + scaleOut(targetScale = 0.8f))
        }
    ) { target ->
        if (target) {
            ChatListPreview(
                innerPadding = innerPadding,
                conversation = conversation,
                settings = settings,
                hazeState = hazeState,
                onJumpToMessage = onJumpToMessage,
                animatedVisibilityScope = this@AnimatedContent,
            )
        } else {
            ChatListNormal(
                innerPadding = innerPadding,
                conversation = conversation,
                chatRenderCells = chatRenderCells,
                state = state,
                loading = loading,
                processingStatus = processingStatus,
                settings = settings,
                hazeState = hazeState,
                errors = errors,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                onRegenerate = onRegenerate,
                onEdit = onEdit,
                onForkMessage = onForkMessage,
                onDelete = onDelete,
                onUpdateMessage = onUpdateMessage,
                onClickSuggestion = onClickSuggestion,
                onTranslate = onTranslate,
                onClearTranslation = onClearTranslation,
                animatedVisibilityScope = this@AnimatedContent,
                onToolApproval = onToolApproval,
                onToolAnswer = onToolAnswer,
                onToggleFavorite = onToggleFavorite,
                onBubbleInput = onBubbleInput,
            )
        }
    }
}

@Composable
private fun ChatListNormal(
    innerPadding: PaddingValues,
    conversation: Conversation,
    chatRenderCells: List<ChatRenderCell>,
    state: LazyListState,
    loading: Boolean,
    processingStatus: String? = null,
    settings: Settings,
    hazeState: HazeState,
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
    onRegenerate: (UIMessage) -> Unit,
    onEdit: (UIMessage) -> Unit,
    onForkMessage: (UIMessage) -> Unit,
    onDelete: (UIMessage) -> Unit,
    onUpdateMessage: (MessageNode) -> Unit,
    onClickSuggestion: (String) -> Unit,
    onTranslate: ((UIMessage, java.util.Locale) -> Unit)?,
    onClearTranslation: (UIMessage) -> Unit,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onToggleFavorite: ((MessageNode) -> Unit)? = null,
    onBubbleInput: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val loadingState by rememberUpdatedState(loading)
    var isRecentScroll by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val activity = LocalContext.current as? me.rerere.rikkahub.RouteActivity
    val renderCells = remember(chatRenderCells) {
        chatRenderCells.filterNot { it is ChatRenderCell.BottomSpacerCell }
    }
    val cellPipelineCounts = remember(renderCells) {
        RichCellPipelineCounts(
            totalCells = renderCells.size,
            richCells = renderCells.count { it is ChatRenderCell.RichHtmlCell },
            highRiskCells = renderCells.count { it.renderRisk.score >= 50 },
        )
    }

    DisposableEffect(Unit) {
        val listener: (Boolean) -> Boolean = { isVolumeUp ->
            if (settings.displaySetting.enableVolumeKeyScroll) {
                val bottomPaddingPx = with(density) {
                    (32.dp + innerPadding.calculateBottomPadding()).toPx()
                }
                val scrollAmount = (state.layoutInfo.viewportSize.height - bottomPaddingPx) *
                    settings.displaySetting.volumeKeyScrollRatio
                scope.launch { state.scrollBy(if (isVolumeUp) -scrollAmount else scrollAmount) }
                true
            } else false
        }
        activity?.volumeKeyListeners?.add(listener)
        onDispose {
            activity?.volumeKeyListeners?.remove(listener)
        }
    }

    fun List<LazyListItemInfo>.isAtBottom(): Boolean {
        val lastItem = lastOrNull() ?: return false
        val inputBarHeight = with(density) { innerPadding.calculateBottomPadding().toPx() }
        val lastPos = lastItem.offset + lastItem.size
        val inputPos = (state.layoutInfo.viewportEndOffset - inputBarHeight.roundToInt())
        // println("lastPos = $lastPos, inputPos = $inputPos  | ${lastPos <= inputPos - 8}")
        return lastPos <= inputPos - 8
    }

    // 聊天选择
    val selectedItems = remember { mutableStateListOf<Uuid>() }
    var selecting by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }

    // 自动跟随键盘滚动
    ImeLazyListAutoScroller(lazyListState = state)

    // 对话大小警告对话框
    val sizeInfo = rememberConversationSizeInfo(conversation)
    var showSizeWarningDialog by rememberSaveable(conversation.id) { mutableStateOf(true) }
    if (sizeInfo.showWarning && showSizeWarningDialog) {
        ConversationSizeWarningDialog(
            sizeInfo = sizeInfo,
            onDismiss = { showSizeWarningDialog = false }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize(),
    ) {
        // 自动滚动到底部
        if (settings.displaySetting.enableAutoScroll) {
            LaunchedEffect(state) {
                snapshotFlow { state.layoutInfo.visibleItemsInfo }.collect { visibleItemsInfo ->
                    // println("is bottom = ${visibleItemsInfo.isAtBottom()}, scroll = ${state.isScrollInProgress}, can_scroll = ${state.canScrollForward}, loading = $loading")
                    if (!state.isScrollInProgress && loadingState) {
                        if (visibleItemsInfo.isAtBottom()) {
                            state.requestScrollToItem((state.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
                            // Log.i(TAG, "ChatList: scroll to ${conversationUpdated.messageNodes.lastIndex}")
                        }
                    }
                }
            }
        }

        // 判断最近是否滚动
        LaunchedEffect(state.isScrollInProgress) {
            if (state.isScrollInProgress) {
                isRecentScroll = true
                delay(1500)
                isRecentScroll = false
            } else {
                delay(180)
                delay(1320)
                isRecentScroll = false
            }
        }

        var richRenderScrollState by remember { mutableStateOf(RichRenderScrollState()) }
        LaunchedEffect(state, renderCells.size) {
            var previousFirstVisible = state.firstVisibleItemIndex
            var previousFirstVisibleOffset = state.firstVisibleItemScrollOffset
            var previousSampleAtMs = SystemClock.elapsedRealtime()
            snapshotFlow {
                val visible = state.layoutInfo.visibleItemsInfo
                    .map { it.index }
                    .filter { it in renderCells.indices }
                val firstVisible = visible.minOrNull() ?: -1
                val lastVisible = visible.maxOrNull() ?: -1
                val currentFirstVisible = state.firstVisibleItemIndex
                val currentFirstVisibleOffset = state.firstVisibleItemScrollOffset
                val nowMs = SystemClock.elapsedRealtime()
                val elapsedMs = (nowMs - previousSampleAtMs).coerceAtLeast(1L)
                val direction = when {
                    currentFirstVisible > previousFirstVisible ||
                        (currentFirstVisible == previousFirstVisible &&
                            currentFirstVisibleOffset > previousFirstVisibleOffset) -> RichRenderScrollDirection.Down
                    currentFirstVisible < previousFirstVisible ||
                        (currentFirstVisible == previousFirstVisible &&
                            currentFirstVisibleOffset < previousFirstVisibleOffset) -> RichRenderScrollDirection.Up
                    else -> RichRenderScrollDirection.Idle
                }
                val fastScrolling = state.isScrollInProgress && (
                    abs(currentFirstVisible - previousFirstVisible) >= 3 ||
                        (abs(currentFirstVisibleOffset - previousFirstVisibleOffset) * 1000f / elapsedMs) > 2_600f
                    )
                previousFirstVisible = currentFirstVisible
                previousFirstVisibleOffset = currentFirstVisibleOffset
                previousSampleAtMs = nowMs
                val prewarmBehind = when (direction) {
                    RichRenderScrollDirection.Down -> 3
                    RichRenderScrollDirection.Up -> 10
                    RichRenderScrollDirection.Idle -> 6
                }
                val prewarmAhead = when (direction) {
                    RichRenderScrollDirection.Down -> 10
                    RichRenderScrollDirection.Up -> 3
                    RichRenderScrollDirection.Idle -> 6
                }
                val nearStart = (firstVisible - prewarmBehind).coerceAtLeast(0)
                val nearEnd = (lastVisible + prewarmAhead).coerceAtMost((renderCells.size - 1).coerceAtLeast(0))
                val viewportWidthDp = with(density) {
                    val horizontalContentPaddingPx = ChatListContentPadding.roundToPx() * 2
                    (state.layoutInfo.viewportSize.width - horizontalContentPaddingPx)
                        .coerceAtLeast(1)
                        .toDp()
                        .value
                }.takeIf { it > 0f } ?: 360f
                RichRenderScrollState(
                    scrolling = isRecentScroll || state.isScrollInProgress,
                    scrollInProgress = state.isScrollInProgress,
                    fastScrolling = fastScrolling,
                    visibleCellRange = if (firstVisible >= 0 && lastVisible >= firstVisible) {
                        firstVisible..lastVisible
                    } else {
                        0..-1
                    },
                    nearViewportRange = if (firstVisible >= 0 && lastVisible >= firstVisible) {
                        nearStart..nearEnd
                    } else {
                        0..-1
                    },
                    scrollDirection = direction,
                    viewportWidthDp = viewportWidthDp,
                )
            }.distinctUntilChanged().collect {
                richRenderScrollState = it
            }
        }

        LaunchedEffect(cellPipelineCounts, richRenderScrollState.visibleCellRange) {
            RichHtmlRenderTelemetry.recordCellPipeline(
                totalCells = cellPipelineCounts.totalCells,
                visibleCellRange = richRenderScrollState.visibleCellRange,
                richCells = cellPipelineCounts.richCells,
                highRiskCells = cellPipelineCounts.highRiskCells,
            )
        }

        LaunchedEffect(renderCells, richRenderScrollState, settings.displaySetting.enableChatCellPipeline) {
            val targets = if (settings.displaySetting.enableChatCellPipeline) {
                buildRichHtmlPrewarmTargets(renderCells, richRenderScrollState)
            } else {
                emptyList()
            }
            RichHtmlRenderScheduler.updatePrewarmTargets(targets)
        }
        DisposableEffect(Unit) {
            onDispose {
                RichHtmlRenderScheduler.updatePrewarmTargets(emptyList())
            }
        }

        ChatFramePressureTelemetry(richRenderScrollState)

        CompositionLocalProvider(
            LocalRichRenderScrollState provides richRenderScrollState
        ) {
            LazyColumn(
                state = state,
                contentPadding = PaddingValues(ChatListContentPadding) +
                    PaddingValues(bottom = 32.dp + innerPadding.calculateBottomPadding()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState)
                    .padding(top = innerPadding.calculateTopPadding())
                    .semantics { testTagsAsResourceId = true }
                    .testTag("rich-chat-feed"),
            ) {
                if (settings.displaySetting.enableChatCellPipeline) {
                    itemsIndexed(
                        items = renderCells,
                        key = { _, item -> item.stableKey },
                        contentType = { _, item -> item.lazyReuseContentType() },
                    ) { cellIndex, cell ->
                        SelectableChatCellFrame(
                            cell = cell,
                            selecting = selecting,
                            selectedItems = selectedItems,
                            onSelectionChange = { nodeId ->
                                if (!selectedItems.contains(nodeId)) {
                                    selectedItems.add(nodeId)
                                } else {
                                    selectedItems.remove(nodeId)
                                }
                            }
                        ) {
                            ChatRenderCellItem(
                                cell = cell,
                                cellIndex = cellIndex,
                                onRegenerate = onRegenerate,
                                onEdit = onEdit,
                                onForkMessage = onForkMessage,
                                onDelete = onDelete,
                                onShare = { meta ->
                                    selecting = true
                                    selectedItems.clear()
                                    selectedItems.addAll(
                                        conversation.messageNodes
                                            .take(meta.messageIndex + 1)
                                            .map { it.id }
                                    )
                                },
                                onUpdateMessage = onUpdateMessage,
                                onTranslate = onTranslate,
                                onClearTranslation = onClearTranslation,
                                onToolApproval = onToolApproval,
                                onToolAnswer = onToolAnswer,
                                onToggleFavorite = onToggleFavorite,
                                onBubbleInput = onBubbleInput,
                            )
                        }
                    }
                } else {
                    itemsIndexed(
                        items = conversation.messageNodes,
                        key = { _, node -> node.id },
                        contentType = { _, node -> node.currentMessage.role.name },
                    ) { messageIndex, node ->
                        SelectableMessageFrame(
                            node = node,
                            selecting = selecting,
                            selectedItems = selectedItems,
                            onSelectionChange = { nodeId ->
                                if (!selectedItems.contains(nodeId)) {
                                    selectedItems.add(nodeId)
                                } else {
                                    selectedItems.remove(nodeId)
                                }
                            },
                        ) {
                            val message = node.currentMessage
                            ChatMessage(
                                node = node,
                                modifier = Modifier.padding(top = if (messageIndex == 0) 0.dp else 12.dp),
                                loading = loading && messageIndex == conversation.messageNodes.lastIndex,
                                model = message.modelId?.let { settings.findModelById(it) },
                                assistant = settings.getAssistantById(conversation.assistantId),
                                lastMessage = messageIndex == conversation.messageNodes.lastIndex,
                                onFork = { onForkMessage(message) },
                                onRegenerate = { onRegenerate(message) },
                                onEdit = { onEdit(message) },
                                onShare = {
                                    selecting = true
                                    selectedItems.clear()
                                    selectedItems.addAll(
                                        conversation.messageNodes
                                            .take(messageIndex + 1)
                                            .map { it.id }
                                    )
                                },
                                onDelete = { onDelete(message) },
                                onUpdate = onUpdateMessage,
                                isFavorite = node.isFavorite,
                                onToggleFavorite = { onToggleFavorite?.invoke(node) },
                                onTranslate = onTranslate,
                                onClearTranslation = onClearTranslation,
                                onToolApproval = onToolApproval,
                                onToolAnswer = onToolAnswer,
                                onBubbleInput = onBubbleInput,
                            )
                        }
                    }
                }

                if (loading) {
                    item(LoadingIndicatorKey) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RabbitLoadingIndicator(
                                modifier = Modifier.size(28.dp)
                            )
                            AnimatedVisibility(
                                visible = processingStatus != null,
                            ) {
                                Text(
                                    text = processingStatus ?: "",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                // 为了能正确滚动到这
                item(ScrollBottomKey) {
                    ChatRenderCellItem(
                        cell = ChatRenderCell.BottomSpacerCell,
                        cellIndex = renderCells.size,
                        onRegenerate = onRegenerate,
                        onEdit = onEdit,
                        onForkMessage = onForkMessage,
                        onDelete = onDelete,
                        onShare = {},
                        onUpdateMessage = onUpdateMessage,
                        onTranslate = onTranslate,
                        onClearTranslation = onClearTranslation,
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // 错误消息卡片
            ErrorCardsDisplay(
                errors = errors,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(5f)
            )

            // 完成选择
            AnimatedVisibility(
                visible = selecting,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -(48).dp),
                enter = slideInVertically(
                    initialOffsetY = { it * 2 },
                ),
                exit = slideOutVertically(
                    targetOffsetY = { it * 2 },
                ),
            ) {
                HorizontalFloatingToolbar(
                    expanded = true,
                ) {
                    Tooltip(
                        tooltip = {
                            Text("Clear selection")
                        }
                    ) {
                        IconButton(
                            onClick = {
                                selecting = false
                                selectedItems.clear()
                            }
                        ) {
                            Icon(HugeIcons.Cancel01, null)
                        }
                    }
                    Tooltip(
                        tooltip = {
                            Text("Select all")
                        }
                    ) {
                        IconButton(
                            onClick = {
                                if (selectedItems.isNotEmpty()) {
                                    selectedItems.clear()
                                } else {
                                    selectedItems.addAll(conversation.messageNodes.map { it.id })
                                }
                            }
                        ) {
                            Icon(HugeIcons.CursorPointer01, null)
                        }
                    }
                    Tooltip(
                        tooltip = {
                            Text("Confirm")
                        }
                    ) {
                        FilledIconButton(
                            onClick = {
                                selecting = false
                                val messages = conversation.messageNodes.filter { it.id in selectedItems }
                                if (messages.isNotEmpty()) {
                                    showExportSheet = true
                                }
                            }
                        ) {
                            Icon(HugeIcons.Tick01, null)
                        }
                    }
                }
            }

            // 导出对话框
            ChatExportSheet(
                visible = showExportSheet,
                onDismissRequest = {
                    showExportSheet = false
                    selectedItems.clear()
                },
                conversation = conversation,
                selectedMessages = conversation.messageNodes.filter { it.id in selectedItems }
                    .map { it.currentMessage }
            )

            val captureProgress = LocalScrollCaptureInProgress.current

            // 消息快速跳转
            MessageJumper(
                show = isRecentScroll && !state.isScrollInProgress && settings.displaySetting.showMessageJumper && !captureProgress,
                onLeft = settings.displaySetting.messageJumperOnLeft,
                scope = scope,
                state = state
            )

            // Suggestion
            if (conversation.chatSuggestions.isNotEmpty() && !captureProgress) {
                ChatSuggestionsRow(
                    conversation = conversation,
                    onClickSuggestion = onClickSuggestion,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

/**
 * 提取包含搜索词的文本片段，确保匹配词在开头可见
 */
private fun extractMatchingSnippet(
    text: String,
    query: String
): String {
    if (query.isBlank()) {
        return text
    }

    val matchIndex = text.indexOf(query, ignoreCase = true)
    if (matchIndex == -1) {
        return text
    }

    // 直接从匹配词开始显示，确保匹配词在最前面
    val snippet = text.substring(matchIndex)

    // 只在前面有内容时添加省略号
    return if (matchIndex > 0) {
        "...$snippet"
    } else {
        snippet
    }
}

private fun buildHighlightedText(
    text: String,
    query: String,
    highlightColor: Color
): AnnotatedString {
    if (query.isBlank()) {
        return AnnotatedString(text)
    }

    return buildAnnotatedString {
        var startIndex = 0
        var index = text.indexOf(query, startIndex, ignoreCase = true)

        while (index >= 0) {
            // 添加高亮前的文本
            append(text.substring(startIndex, index))

            // 添加高亮文本
            withStyle(
                style = SpanStyle(
                    background = highlightColor,
                    color = Color.Black
                )
            ) {
                append(text.substring(index, index + query.length))
            }

            startIndex = index + query.length
            index = text.indexOf(query, startIndex, ignoreCase = true)
        }

        // 添加剩余文本
        if (startIndex < text.length) {
            append(text.substring(startIndex))
        }
    }
}

private fun buildRichHtmlPrewarmTargets(
    renderCells: List<ChatRenderCell>,
    scrollState: RichRenderScrollState,
): List<RichHtmlPrewarmTarget> {
    if (scrollState.nearViewportRange.isEmptyRange()) return emptyList()
    if (scrollState.fastScrolling) return emptyList()
    val visibleRange = scrollState.visibleCellRange
    val candidateIndexes = scrollState.nearViewportRange
        .asSequence()
        .filter { it in renderCells.indices }
        .toList()
    val nearIndexes = candidateIndexes
        .filterNot { visibleRange.isNotEmptyRange() && it in visibleRange }
        .let { indexes ->
            when (scrollState.scrollDirection) {
                RichRenderScrollDirection.Down -> indexes.sorted()
                RichRenderScrollDirection.Up -> indexes.sortedDescending()
                RichRenderScrollDirection.Idle -> indexes.sortedBy { distanceToRange(it, visibleRange) }
            }
        }
    val orderedIndexes = nearIndexes
    return orderedIndexes.mapNotNull { index ->
        val cell = renderCells[index] as? ChatRenderCell.RichHtmlCell ?: return@mapNotNull null
        if (cell.block.partial) return@mapNotNull null
        RichHtmlPrewarmTarget(
            html = cell.block.html,
            cellIndex = index,
            viewportWidthDp = scrollState.viewportWidthDp,
            risk = cell.renderRisk,
            contentType = cell.contentType.name,
        )
    }
}

private fun distanceToRange(index: Int, range: IntRange): Int {
    if (range.isEmptyRange()) return 0
    return when {
        index < range.first -> range.first - index
        index > range.last -> index - range.last
        else -> 0
    }
}

private fun IntRange.isEmptyRange(): Boolean = first > last

private fun IntRange.isNotEmptyRange(): Boolean = first <= last

private fun ChatRenderCell.lazyReuseContentType(): Any = when (this) {
    is ChatRenderCell.RichHtmlCell -> contentType
    else -> contentType
}

private data class RichCellPipelineCounts(
    val totalCells: Int,
    val richCells: Int,
    val highRiskCells: Int,
)

@Composable
private fun SelectableChatCellFrame(
    cell: ChatRenderCell,
    selecting: Boolean,
    selectedItems: List<Uuid>,
    onSelectionChange: (Uuid) -> Unit,
    content: @Composable () -> Unit,
) {
    val meta = cell.messageMetaOrNull()
    if (!selecting || meta == null) {
        content()
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (cell is ChatRenderCell.AvatarCell) {
            Checkbox(
                checked = meta.node.id in selectedItems,
                onCheckedChange = { onSelectionChange(meta.node.id) },
            )
        } else {
            Spacer(modifier = Modifier.width(48.dp))
        }
        Box(modifier = Modifier.weight(1f)) {
            content()
        }
    }
}

@Composable
private fun SelectableMessageFrame(
    node: MessageNode,
    selecting: Boolean,
    selectedItems: List<Uuid>,
    onSelectionChange: (Uuid) -> Unit,
    content: @Composable () -> Unit,
) {
    if (!selecting) {
        content()
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(
            checked = node.id in selectedItems,
            onCheckedChange = { onSelectionChange(node.id) },
        )
        Box(modifier = Modifier.weight(1f)) {
            content()
        }
    }
}

@Composable
private fun ChatFramePressureTelemetry(scrollState: RichRenderScrollState) {
    val currentScrollState by rememberUpdatedState(scrollState)
    DisposableEffect(Unit) {
        val choreographer = Choreographer.getInstance()
        var lastFrameNanos = 0L
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (lastFrameNanos > 0L) {
                    val state = currentScrollState
                    if (state.scrollInProgress) {
                        val frameMs = (frameTimeNanos - lastFrameNanos) / 1_000_000f
                        RichHtmlRenderTelemetry.recordFramePressure(
                            frameMs = frameMs,
                            direction = state.scrollDirection.name,
                            visibleCellRange = state.visibleCellRange,
                            fastScrolling = state.fastScrolling,
                            inlineActiveCount = inlineDynamicWebViewActiveCount(),
                        )
                    }
                }
                lastFrameNanos = frameTimeNanos
                choreographer.postFrameCallback(this)
            }
        }
        choreographer.postFrameCallback(callback)
        onDispose {
            choreographer.removeFrameCallback(callback)
        }
    }
}

@Composable
private fun ChatListPreview(
    innerPadding: PaddingValues,
    conversation: Conversation,
    settings: Settings,
    hazeState: HazeState,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onJumpToMessage: (Int) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    // 过滤消息，同时保留原始 index 避免后续 O(n) indexOf 查找
    val filteredMessages = remember(conversation.messageNodes, searchQuery) {
        if (searchQuery.isBlank()) {
            conversation.messageNodes.mapIndexed { index, node -> index to node }
        } else {
            conversation.messageNodes.mapIndexed { index, node -> index to node }
                .filter { (_, node) -> node.currentMessage.toText().contains(searchQuery, ignoreCase = true) }
        }
    }

    Column(
        modifier = Modifier
            .padding(top = innerPadding.calculateTopPadding())
            .fillMaxSize()
            .hazeSource(state = hazeState),
    ) {
        // 搜索框
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.history_page_search)) },
            leadingIcon = {
                Icon(
                    imageVector = HugeIcons.Search01,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = "Clear",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            },
            singleLine = true,
            shape = CircleShape,
            maxLines = 1,
        )

        // 消息预览
        LazyColumn(
            contentPadding = PaddingValues(16.dp) + PaddingValues(bottom = 32.dp + innerPadding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            itemsIndexed(
                items = filteredMessages,
                key = { index, item -> item.second.id },
                contentType = { _, item -> item.second.currentMessage.role.name },
            ) { _, (originalIndex, node) ->
                val message = node.currentMessage
                val isUser = message.role == me.rerere.ai.core.MessageRole.USER
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (!isUser) Modifier.padding(end = 24.dp) else Modifier
                        ),
                    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Row(
                            modifier = Modifier
                                .clickable {
                                    onJumpToMessage(originalIndex)
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val highlightColor = MaterialTheme.colorScheme.tertiaryContainer
                            val highlightedText = remember(searchQuery, message) {
                                val fullText = message.toText().trim().ifBlank { "[...]" }
                                val messageText = extractMatchingSnippet(
                                    text = fullText,
                                    query = searchQuery
                                )
                                buildHighlightedText(
                                    text = messageText,
                                    query = searchQuery,
                                    highlightColor = highlightColor
                                )
                            }
                            Text(
                                text = highlightedText,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatSuggestionsRow(
    modifier: Modifier = Modifier,
    conversation: Conversation,
    onClickSuggestion: (String) -> Unit
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(conversation.chatSuggestions) { suggestion ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable {
                        onClickSuggestion(suggestion)
                    }
                    .background(MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
                    .padding(vertical = 4.dp, horizontal = 8.dp),
            ) {
                Text(
                    text = suggestion,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun BoxScope.MessageJumper(
    show: Boolean,
    onLeft: Boolean,
    scope: CoroutineScope,
    state: LazyListState
) {
    AnimatedVisibility(
        visible = show,
        modifier = Modifier.align(if (onLeft) Alignment.CenterStart else Alignment.CenterEnd),
        enter = slideInHorizontally(
            initialOffsetX = { if (onLeft) -it * 2 else it * 2 },
        ),
        exit = slideOutHorizontally(
            targetOffsetX = { if (onLeft) -it * 2 else it * 2 },
        )
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                onClick = {
                    scope.launch {
                        state.scrollToItem(0)
                    }
                },
                shape = CircleShape,
                tonalElevation = 4.dp,
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(
                    4.dp
                ).copy(alpha = 0.65f)
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowUpDouble,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
            Surface(
                onClick = {
                    scope.launch {
                        state.animateScrollToItem(
                            (state.firstVisibleItemIndex - 1).fastCoerceAtLeast(
                                0
                            )
                        )
                    }
                },
                shape = CircleShape,
                tonalElevation = 4.dp,
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(
                    4.dp
                ).copy(alpha = 0.65f)
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowUp01,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
            Surface(
                onClick = {
                    scope.launch {
                        state.animateScrollToItem(state.firstVisibleItemIndex + 1)
                    }
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(
                    4.dp
                ).copy(alpha = 0.65f)
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowDown01,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
            Surface(
                onClick = {
                    scope.launch {
                        state.scrollToItem(state.layoutInfo.totalItemsCount - 1)
                    }
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(
                    4.dp
                ).copy(alpha = 0.65f),
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowDownDouble,
                    contentDescription = stringResource(R.string.chat_page_scroll_to_bottom),
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
        }
    }
}
