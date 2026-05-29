package me.rerere.rikkahub.ui.components.message

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Video01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.richtext.LocalRichRenderScrollState
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.RichHtmlBubbleBlock
import me.rerere.rikkahub.ui.components.richtext.RichRenderDecisionInput
import me.rerere.rikkahub.ui.components.richtext.RichRenderHeightCache
import me.rerere.rikkahub.ui.components.richtext.RichRenderOrchestrator
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.richtext.buildMarkdownPreviewHtml
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.ui.components.ui.Favicon
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.theme.rememberChatFontFamily
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.base64Encode
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.urlDecode
import kotlin.time.Duration.Companion.milliseconds

@Composable
internal fun ChatRenderCellItem(
    cell: ChatRenderCell,
    cellIndex: Int,
    modifier: Modifier = Modifier,
    onRegenerate: (UIMessage) -> Unit,
    onEdit: (UIMessage) -> Unit,
    onForkMessage: (UIMessage) -> Unit,
    onDelete: (UIMessage) -> Unit,
    onShare: (ChatRenderMessageMeta) -> Unit,
    onUpdateMessage: (me.rerere.rikkahub.data.model.MessageNode) -> Unit,
    onTranslate: ((UIMessage, java.util.Locale) -> Unit)?,
    onClearTranslation: (UIMessage) -> Unit,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onToggleFavorite: ((me.rerere.rikkahub.data.model.MessageNode) -> Unit)? = null,
    onBubbleInput: (String) -> Unit = {},
) {
    when (cell) {
        ChatRenderCell.BottomSpacerCell -> Spacer(
            modifier = modifier
                .fillMaxWidth()
                .height(5.dp)
        )

        is ChatRenderCell.AvatarCell -> ChatMessageTextStyleProvider(cell.meta) {
            MessageGenerationHapticEffect(cell.meta)
            ChatCellContainer(cell = cell, modifier = modifier) {
                AvatarCellContent(cell)
            }
        }

        is ChatRenderCell.UserBubbleCell -> ChatMessageTextStyleProvider(cell.meta) {
            ChatCellContainer(cell = cell, modifier = modifier) {
                UserBubbleCellContent(cell = cell, onEdit = onEdit)
            }
        }

        is ChatRenderCell.MarkdownCell -> ChatMessageTextStyleProvider(cell.meta) {
            ChatCellContainer(cell = cell, modifier = modifier) {
                AssistantBubbleFrame(position = cell.bubblePosition) {
                    MarkdownBlock(
                        content = cell.text,
                        onClickCitation = rememberMessageCitationClickHandler(cell.meta.message.parts),
                    )
                }
            }
        }

        is ChatRenderCell.RichHtmlCell -> ChatMessageTextStyleProvider(cell.meta) {
            ChatCellContainer(cell = cell, modifier = modifier) {
                AssistantBubbleFrame(position = cell.bubblePosition) {
                    RichHtmlCellContent(
                        cell = cell,
                        cellIndex = cellIndex,
                        onBubbleInput = onBubbleInput,
                    )
                }
            }
        }

        is ChatRenderCell.ProtocolCell -> ChatMessageTextStyleProvider(cell.meta) {
            ChatCellContainer(cell = cell, modifier = modifier) {
                AssistantBubbleFrame(position = cell.bubblePosition) {
                    ProtocolTextBlock(block = cell.block)
                }
            }
        }

        is ChatRenderCell.ThinkingCell -> ChatMessageTextStyleProvider(cell.meta) {
            ChatCellContainer(cell = cell, modifier = modifier) {
                ThinkingCellContent(
                    cell = cell,
                    onToolApproval = onToolApproval,
                    onToolAnswer = onToolAnswer,
                )
            }
        }

        is ChatRenderCell.ToolCell -> ChatMessageTextStyleProvider(cell.meta) {
            ChatCellContainer(cell = cell, modifier = modifier) {
                ToolCellContent(
                    cell = cell,
                    onToolApproval = onToolApproval,
                    onToolAnswer = onToolAnswer,
                )
            }
        }

        is ChatRenderCell.AttachmentCell -> ChatMessageTextStyleProvider(cell.meta) {
            ChatCellContainer(cell = cell, modifier = modifier) {
                AttachmentCellContent(cell)
            }
        }

        is ChatRenderCell.TranslationCell -> ChatMessageTextStyleProvider(cell.meta) {
            ChatCellContainer(cell = cell, modifier = modifier) {
                CollapsibleTranslationText(content = cell.text, onClickCitation = {})
            }
        }

        is ChatRenderCell.AnnotationCell -> ChatMessageTextStyleProvider(cell.meta) {
            ChatCellContainer(cell = cell, modifier = modifier) {
                AnnotationCellContent(cell.annotations)
            }
        }

        is ChatRenderCell.ActionsCell -> ChatMessageTextStyleProvider(cell.meta) {
            ChatCellContainer(cell = cell, modifier = modifier) {
                ActionsCellContent(
                    cell = cell,
                    onRegenerate = onRegenerate,
                    onEdit = onEdit,
                    onForkMessage = onForkMessage,
                    onDelete = onDelete,
                    onShare = onShare,
                    onUpdateMessage = onUpdateMessage,
                    onTranslate = onTranslate,
                    onClearTranslation = onClearTranslation,
                    onToggleFavorite = onToggleFavorite,
                )
            }
        }
    }
}

@Composable
private fun ChatMessageTextStyleProvider(
    meta: ChatRenderMessageMeta,
    content: @Composable () -> Unit,
) {
    val settings = LocalSettings.current.displaySetting
    val chatFontFamily = rememberChatFontFamily(settings)
    val textStyle = LocalTextStyle.current.copy(
        fontSize = LocalTextStyle.current.fontSize * settings.fontSizeRatio,
        lineHeight = LocalTextStyle.current.lineHeight * settings.fontSizeRatio,
        fontFamily = chatFontFamily,
    )
    key(meta.message.id, settings.fontSizeRatio, settings.chatFontFamily, settings.chatCustomFontPath) {
        ProvideTextStyle(textStyle) {
            content()
        }
    }
}

@Composable
private fun ChatCellContainer(
    cell: ChatRenderCell,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val meta = cell.messageMetaOrNull()
    val role = meta?.message?.role
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = topPaddingFor(cell)),
        horizontalAlignment = if (role == MessageRole.USER) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

@Composable
private fun AvatarCellContent(cell: ChatRenderCell.AvatarCell) {
    val settings = LocalSettings.current.displaySetting
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) {
        ChatMessageAssistantAvatar(
            message = cell.meta.message,
            model = cell.meta.model,
            assistant = cell.meta.assistant,
            loading = cell.meta.loading,
            modifier = Modifier.weight(1f),
        )
        ChatMessageUserAvatar(
            message = cell.meta.message,
            avatar = settings.userAvatar,
            nickname = settings.userNickname,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun UserBubbleCellContent(
    cell: ChatRenderCell.UserBubbleCell,
    onEdit: (UIMessage) -> Unit,
) {
    SelectionContainer {
        Surface(
            modifier = Modifier.animateContentSize(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            onClick = { onEdit(cell.meta.message) },
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                MarkdownBlock(
                    content = cell.text,
                    onClickCitation = rememberMessageCitationClickHandler(cell.meta.message.parts),
                )
            }
        }
    }
}

@Composable
private fun AssistantBubbleFrame(
    position: AssistantBubblePosition,
    content: @Composable () -> Unit,
) {
    val showBubble = LocalSettings.current.displaySetting.showAssistantBubble &&
        position != AssistantBubblePosition.None
    if (!showBubble) {
        content()
        return
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = assistantBubbleShape(position),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(
            modifier = Modifier.padding(
                start = 8.dp,
                end = 8.dp,
                top = if (position == AssistantBubblePosition.Bottom) 4.dp else 8.dp,
                bottom = if (position == AssistantBubblePosition.Top) 4.dp else 8.dp,
            )
        ) {
            content()
        }
    }
}

@Composable
private fun RichHtmlCellContent(
    cell: ChatRenderCell.RichHtmlCell,
    cellIndex: Int,
    onBubbleInput: (String) -> Unit,
) {
    val navController = LocalNavController.current
    val block = cell.block
    val analysis = cell.analysis

    if (block.partial) {
        val previewHtml = block.previewHtml
        val previewAnalysis = remember(previewHtml) {
            previewHtml?.let(::analyzeRichHtml)
        }
        when (previewAnalysis?.kind) {
            RichHtmlRenderKind.NativeStatic,
            RichHtmlRenderKind.InteractiveStatic -> {
                if (previewHtml != null) {
                    RichHtmlBubbleBlock(
                        html = previewHtml,
                        onSendInput = onBubbleInput,
                        transientCache = true,
                        enableSnapshot = false,
                        renderCellIndex = cellIndex,
                        renderRisk = cell.renderRisk,
                        heightContentType = cell.contentType.name,
                        renderPlan = buildRichRenderPlan(
                            html = previewHtml,
                            analysis = previewAnalysis,
                            risk = RenderRiskScore.fromHtml(previewHtml, previewAnalysis),
                            includeStructuralReport = false,
                        ),
                        renderFallback = {
                            StreamingRichHtmlPlaceholder(
                                previewText = previewAnalysis.previewText.ifBlank { analysis.previewText },
                            )
                        },
                    )
                } else {
                    StreamingRichHtmlPlaceholder(previewText = analysis.previewText)
                }
            }

            RichHtmlRenderKind.ComplexDynamic,
            null -> StreamingRichHtmlPlaceholder(
                previewText = previewAnalysis?.previewText ?: analysis.previewText,
            )
        }
        return
    }

    when (analysis.kind) {
        RichHtmlRenderKind.NativeStatic,
        RichHtmlRenderKind.InteractiveStatic -> {
            val onOpen = {
                navController.navigate(Screen.WebView(content = block.html.base64Encode()))
            }
            RichHtmlBubbleBlock(
                html = block.html,
                onSendInput = onBubbleInput,
                onOpenPreview = onOpen,
                renderCellIndex = cellIndex,
                renderRisk = cell.renderRisk,
                heightContentType = cell.contentType.name,
                renderPlan = cell.renderPlan,
                renderFallback = {
                    DynamicRichHtmlPreviewBlock(
                        previewText = analysis.previewText,
                        onOpen = onOpen,
                    )
                },
            )
        }

        RichHtmlRenderKind.ComplexDynamic -> {
            val onOpen = {
                navController.navigate(Screen.WebView(content = block.html.base64Encode()))
            }
            if (cell.renderRisk.route == RichContentRoute.Snapshot) {
                RichHtmlBubbleBlock(
                    html = block.html,
                    onSendInput = onBubbleInput,
                    onOpenPreview = onOpen,
                    renderCellIndex = cellIndex,
                    renderRisk = cell.renderRisk,
                    heightContentType = cell.contentType.name,
                    renderPlan = cell.renderPlan,
                    renderFallback = {
                        DynamicRichHtmlPreviewBlock(
                            previewText = analysis.previewText,
                            onOpen = onOpen,
                        )
                    },
                )
            } else {
                val scrollState = LocalRichRenderScrollState.current
                val density = LocalDensity.current
                val context = LocalContext.current
                val dark = isSystemInDarkTheme()
                remember(context) {
                    RichRenderHeightCache.initialize(context.applicationContext)
                    true
                }
                val inlineHeightKey = remember(
                    block.html,
                    scrollState.viewportWidthDp,
                    density.fontScale,
                    density.density,
                    dark,
                ) {
                    RichRenderHeightCache.key(
                        id = cell.renderPlan.id,
                        viewportWidthDp = scrollState.viewportWidthDp,
                        fontScale = density.fontScale,
                        density = density.density,
                        themeBucket = if (dark) "dark" else "light",
                        contentType = "InlineDynamicWebView",
                    )
                }
                val inlineHeightEntry = remember(inlineHeightKey) {
                    RichRenderHeightCache.getEntry(inlineHeightKey)
                }
                val inlinePlan = remember(cell.renderPlan) {
                    cell.renderPlan.withRoute(
                        route = RichRenderPlanRoute.InlineWebView,
                        reason = "ComplexDynamicInlineWebView",
                    )
                }
                val inlineDecision = remember(
                    inlinePlan,
                    analysis,
                    cell.renderRisk,
                    scrollState,
                    cellIndex,
                    inlineHeightEntry,
                ) {
                    RichRenderOrchestrator.decide(
                        RichRenderDecisionInput(
                            plan = inlinePlan,
                            analysis = analysis,
                            risk = cell.renderRisk,
                            scrollState = scrollState,
                            cellIndex = cellIndex,
                            cachedModelAvailable = false,
                            heightEntry = inlineHeightEntry,
                            alreadyRendered = false,
                            transient = false,
                        )
                    )
                }
                LaunchedEffect(inlinePlan, inlineDecision) {
                    RichHtmlRenderTelemetry.recordRichRenderPlan(
                        inlinePlan
                    )
                    RichHtmlRenderTelemetry.recordOrchestratorDecision(
                        id = inlinePlan.id,
                        route = inlineDecision.route.name,
                        reason = inlineDecision.reason,
                        placeholderHeightPx = inlineDecision.placeholderHeightPx,
                        placeholderSource = inlineHeightEntry?.let { "Cache:${it.confidence.name}" }
                            ?: "InlineDeferred",
                        nativeAdmissionAllowed = inlineDecision.nativeAdmissionAllowed,
                        shouldPrewarm = inlineDecision.shouldPrewarm,
                        alreadyRendered = false,
                        cachedModelAvailable = false,
                        transient = false,
                        heightConfidence = inlineHeightEntry?.confidence?.name,
                    )
                    RichHtmlRenderTelemetry.recordRouteClosure(
                        id = inlinePlan.id,
                        report = buildRichRouteClosureReport(
                            plannedRoute = cell.renderPlan.route.name,
                            actualRoute = RichRenderPlanRoute.InlineWebView.name,
                            actualDecisionSource = RichActualDecisionSource.Orchestrator,
                        ),
                    )
                }
                InlineDynamicWebViewBlock(
                    html = block.html,
                    previewText = analysis.previewText,
                    cellIndex = cellIndex,
                    onOpen = onOpen,
                )
            }
        }
    }
}

@Composable
private fun ThinkingCellContent(
    cell: ChatRenderCell.ThinkingCell,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)?,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)?,
) {
    val isReasoningOnlyBlock = cell.steps.all { it is ThinkingStep.ReasoningStep }
    ChainOfThought(
        modifier = Modifier.animateContentSize(),
        steps = cell.steps,
        collapsedAdaptiveWidth = isReasoningOnlyBlock,
    ) { step ->
        when (step) {
            is ThinkingStep.ReasoningStep -> key(step.reasoning.createdAt) {
                ChatMessageReasoningStep(
                    reasoning = step.reasoning,
                    model = cell.meta.model,
                    assistant = cell.meta.assistant,
                    collapsedAdaptiveWidth = isReasoningOnlyBlock,
                )
            }

            is ThinkingStep.ToolStep -> key(step.tool.toolCallId.ifBlank { step.hashCode().toString() }) {
                ChatMessageToolStep(
                    tool = step.tool,
                    loading = cell.meta.loading && !step.tool.isExecuted,
                    onToolApproval = onToolApproval,
                    onToolAnswer = onToolAnswer,
                )
            }
        }
    }
}

@Composable
private fun ToolCellContent(
    cell: ChatRenderCell.ToolCell,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)?,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)?,
) {
    ChainOfThought(
        modifier = Modifier.animateContentSize(),
        steps = listOf(ThinkingStep.ToolStep(cell.tool)),
        collapsedAdaptiveWidth = false,
    ) { _ ->
        ChatMessageToolStep(
            tool = cell.tool,
            loading = cell.meta.loading && !cell.tool.isExecuted,
            onToolApproval = onToolApproval,
            onToolAnswer = onToolAnswer,
        )
    }
}

@Composable
private fun AttachmentCellContent(cell: ChatRenderCell.AttachmentCell) {
    val context = LocalContext.current
    when (val part = cell.part) {
        is UIMessagePart.Video -> VideoAttachment(context, part)
        is UIMessagePart.Audio -> AudioAttachment(context, part)
        is UIMessagePart.Image -> ImageAttachment(part)
        is UIMessagePart.Document -> DocumentAttachment(context, part)
        else -> Unit
    }
}

@Composable
private fun VideoAttachment(
    context: Context,
    part: UIMessagePart.Video,
) {
    Surface(
        tonalElevation = 2.dp,
        onClick = {
            openMessageAttachment(context = context, url = part.url, mimeType = "video/*")
        },
        shape = RoundedCornerShape(8.dp),
    ) {
        Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
            Icon(HugeIcons.Video01, null)
        }
    }
}

@Composable
private fun AudioAttachment(
    context: Context,
    part: UIMessagePart.Audio,
) {
    Surface(
        tonalElevation = 2.dp,
        onClick = {
            openMessageAttachment(context = context, url = part.url, mimeType = "audio/*")
        },
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        ProvideTextStyle(MaterialTheme.typography.labelSmall) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.MusicNote03,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun ImageAttachment(part: UIMessagePart.Image) {
    val isImageLoading = part.url.isBlank() || part.url.matches(Regex("^data:image/[^;]*;base64,\\s*$"))
    if (isImageLoading) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .shimmer(isLoading = true)
        )
    } else {
        ZoomableAsyncImage(
            model = part.url,
            contentDescription = null,
            modifier = Modifier
                .clip(MaterialTheme.shapes.medium)
                .height(72.dp),
        )
    }
}

@Composable
private fun DocumentAttachment(
    context: Context,
    part: UIMessagePart.Document,
) {
    Surface(
        tonalElevation = 2.dp,
        onClick = {
            openMessageAttachment(context = context, url = part.url, mimeType = part.mime)
        },
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        ProvideTextStyle(MaterialTheme.typography.labelSmall) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (part.mime) {
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> {
                        Icon(
                            painter = painterResource(R.drawable.docx),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    "application/pdf" -> {
                        Icon(
                            painter = painterResource(R.drawable.pdf),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    else -> {
                        Icon(
                            imageVector = HugeIcons.File02,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                Text(
                    text = part.fileName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 200.dp),
                )
            }
        }
    }
}

@Composable
private fun AnnotationCellContent(annotations: List<UIMessageAnnotation>) {
    val contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
    Column(modifier = Modifier.animateContentSize()) {
        var expand by remember { mutableStateOf(false) }
        if (expand) {
            ProvideTextStyle(
                MaterialTheme.typography.labelMedium.copy(
                    color = MaterialTheme.extendColors.gray8.copy(alpha = 0.65f),
                )
            ) {
                Column(
                    modifier = Modifier
                        .drawAnnotationRail(contentColor)
                        .padding(start = 16.dp)
                        .padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    annotations.forEachIndexed { index, annotation ->
                        when (annotation) {
                            is UIMessageAnnotation.UrlCitation -> {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Favicon(annotation.url, modifier = Modifier.size(20.dp))
                                    Text(
                                        text = buildAnnotatedString {
                                            append("${index + 1}. ")
                                            withLink(LinkAnnotation.Url(annotation.url)) {
                                                append(annotation.title.urlDecode())
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        androidx.compose.material3.TextButton(onClick = { expand = !expand }) {
            Text(androidx.compose.ui.res.stringResource(R.string.citations_count, annotations.size))
        }
    }
}

private fun Modifier.drawAnnotationRail(contentColor: Color): Modifier {
    return this.then(
        Modifier.drawWithContent {
            drawContent()
            drawRoundRect(
                color = contentColor.copy(alpha = 0.2f),
                size = Size(width = 10f, height = size.height),
            )
        }
    )
}

@Composable
private fun ColumnScope.ActionsCellContent(
    cell: ChatRenderCell.ActionsCell,
    onRegenerate: (UIMessage) -> Unit,
    onEdit: (UIMessage) -> Unit,
    onForkMessage: (UIMessage) -> Unit,
    onDelete: (UIMessage) -> Unit,
    onShare: (ChatRenderMessageMeta) -> Unit,
    onUpdateMessage: (me.rerere.rikkahub.data.model.MessageNode) -> Unit,
    onTranslate: ((UIMessage, java.util.Locale) -> Unit)?,
    onClearTranslation: (UIMessage) -> Unit,
    onToggleFavorite: ((me.rerere.rikkahub.data.model.MessageNode) -> Unit)?,
) {
    val meta = cell.meta
    val message = meta.message
    var showActionsSheet by remember { mutableStateOf(false) }
    var showSelectCopySheet by remember { mutableStateOf(false) }
    val navController = LocalNavController.current
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme

    AnimatedVisibility(
        visible = true,
        enter = slideInVertically { it / 2 } + fadeIn(),
        exit = slideOutVertically { it / 2 } + fadeOut(),
    ) {
        Column(modifier = Modifier.animateContentSize()) {
            ChatMessageActionButtons(
                message = message,
                onRegenerate = { onRegenerate(message) },
                node = meta.node,
                onUpdate = onUpdateMessage,
                onOpenActionSheet = { showActionsSheet = true },
                onTranslate = onTranslate,
                onClearTranslation = onClearTranslation,
            )
        }
    }

    ChatMessageNerdLine(message = message)

    if (showActionsSheet) {
        ChatMessageActionsSheet(
            message = message,
            onEdit = { onEdit(message) },
            onDelete = { onDelete(message) },
            onShare = { onShare(meta) },
            onFork = { onForkMessage(message) },
            model = meta.model,
            onSelectAndCopy = { showSelectCopySheet = true },
            isFavorite = meta.node.isFavorite,
            onToggleFavorite = { onToggleFavorite?.invoke(meta.node) },
            onWebViewPreview = {
                val textContent = message.parts
                    .filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()
                if (textContent.isNotBlank()) {
                    val htmlContent = buildMarkdownPreviewHtml(
                        context = context,
                        markdown = textContent,
                        colorScheme = colorScheme,
                    )
                    navController.navigate(Screen.WebView(content = htmlContent.base64Encode()))
                }
            },
            onDismissRequest = { showActionsSheet = false },
        )
    }

    if (showSelectCopySheet) {
        ChatMessageCopySheet(
            message = message,
            onDismissRequest = { showSelectCopySheet = false },
        )
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun MessageGenerationHapticEffect(meta: ChatRenderMessageMeta) {
    val hapticFeedback = LocalHapticFeedback.current
    val settings = LocalSettings.current
    val partsState by rememberUpdatedState(meta.message.parts)
    LaunchedEffect(settings.displaySetting, meta.loading) {
        snapshotFlow { partsState }
            .debounce(50.milliseconds)
            .collect { parts ->
                if (parts.isNotEmpty() && meta.loading && settings.displaySetting.enableMessageGenerationHapticEffect) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                }
            }
    }
}

@Composable
private fun rememberMessageCitationClickHandler(parts: List<UIMessagePart>): (String) -> Unit {
    val context = LocalContext.current
    val partsState by rememberUpdatedState(parts)
    return remember(context) {
        handler@{ citationId ->
            partsState.forEach { part ->
                if (part is UIMessagePart.Tool && part.toolName == "search_web" && part.isExecuted) {
                    val outputText = part.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
                    val items =
                        runCatching { JsonInstant.parseToJsonElement(outputText).jsonObject["items"]?.jsonArray }.getOrNull()
                            ?: return@forEach
                    items.forEach { item ->
                        val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: return@forEach
                        val url = item.jsonObject["url"]?.jsonPrimitive?.content ?: return@forEach
                        if (citationId == id) {
                            context.openUrl(url)
                            return@handler
                        }
                    }
                }
            }
        }
    }
}

private fun topPaddingFor(cell: ChatRenderCell): Dp {
    val meta = cell.messageMetaOrNull()
    return when (cell) {
        is ChatRenderCell.AvatarCell -> if (meta?.messageIndex == 0) 0.dp else 12.dp
        is ChatRenderCell.MarkdownCell -> bubbleTopPadding(cell.bubblePosition)
        is ChatRenderCell.RichHtmlCell -> bubbleTopPadding(cell.bubblePosition)
        is ChatRenderCell.ProtocolCell -> bubbleTopPadding(cell.bubblePosition)
        is ChatRenderCell.ActionsCell -> 2.dp
        is ChatRenderCell.AnnotationCell,
        is ChatRenderCell.TranslationCell -> 4.dp
        is ChatRenderCell.UserBubbleCell,
        is ChatRenderCell.ThinkingCell,
        is ChatRenderCell.ToolCell,
        is ChatRenderCell.AttachmentCell -> 6.dp
        ChatRenderCell.BottomSpacerCell -> 0.dp
    }
}

private fun bubbleTopPadding(position: AssistantBubblePosition): Dp {
    return when (position) {
        AssistantBubblePosition.Middle,
        AssistantBubblePosition.Bottom -> 1.dp
        AssistantBubblePosition.Single,
        AssistantBubblePosition.Top,
        AssistantBubblePosition.None -> 6.dp
    }
}

private fun assistantBubbleShape(position: AssistantBubblePosition): RoundedCornerShape {
    val large = 16.dp
    val small = 6.dp
    return when (position) {
        AssistantBubblePosition.Single,
        AssistantBubblePosition.None -> RoundedCornerShape(large)
        AssistantBubblePosition.Top -> RoundedCornerShape(
            topStart = large,
            topEnd = large,
            bottomStart = small,
            bottomEnd = small,
        )
        AssistantBubblePosition.Middle -> RoundedCornerShape(small)
        AssistantBubblePosition.Bottom -> RoundedCornerShape(
            topStart = small,
            topEnd = small,
            bottomStart = large,
            bottomEnd = large,
        )
    }
}
