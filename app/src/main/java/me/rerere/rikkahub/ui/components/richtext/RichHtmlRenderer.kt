package me.rerere.rikkahub.ui.components.richtext

import android.graphics.DashPathEffect
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.ExperimentalFlexBoxApi
import androidx.compose.foundation.layout.FlexAlignContent
import androidx.compose.foundation.layout.FlexAlignItems
import androidx.compose.foundation.layout.FlexAlignSelf
import androidx.compose.foundation.layout.FlexBasis
import androidx.compose.foundation.layout.FlexBox
import androidx.compose.foundation.layout.FlexBoxScope
import androidx.compose.foundation.layout.FlexDirection
import androidx.compose.foundation.layout.FlexJustifyContent
import androidx.compose.foundation.layout.FlexWrap
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import coil3.compose.rememberAsyncImagePainter
import com.univcp.bubble.BubblePayload
import com.univcp.bubble.BubbleRenderMode
import com.univcp.bubble.BubbleSnapshotRenderer
import com.univcp.bubble.BubbleSnapshotRequest
import com.univcp.bubble.BubbleSnapshotResult
import com.univcp.bubble.BubbleTheme
import kotlinx.coroutines.delay
import me.rerere.rikkahub.ui.components.message.RichHtmlRenderTelemetry
import me.rerere.rikkahub.ui.components.table.DataTableCellStyle
import me.rerere.rikkahub.ui.components.table.SpannedDataTable
import me.rerere.rikkahub.utils.toCssHex
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private val LocalRichRenderColorDefaults = staticCompositionLocalOf { RichRenderColorDefaults.Fallback }
private val LocalRichEffectiveBackground = staticCompositionLocalOf<Color?> { null }
private val LocalRichAnimationHostId = staticCompositionLocalOf { "rich-html" }
private val LocalRichNativeAnimationsEnabled = staticCompositionLocalOf { true }
private val LocalRichFlexTextAlignOverride = staticCompositionLocalOf<TextAlign?> { null }
private val MAX_SAFE_CSS_BLUR_RADIUS = 12.dp

@OptIn(ExperimentalLayoutApi::class, ExperimentalFlexBoxApi::class)
@Composable
internal fun RichHtmlRenderer(
    model: RichHtmlRenderModel,
    modifier: Modifier = Modifier,
    onSendInput: (String) -> Unit = {},
    nativeAnimationsEnabled: Boolean = true,
    animationHostId: String = model.id,
) {
    val defaults = MaterialTheme.colorScheme.toRichRenderColorDefaults().harmonized()
    CompositionLocalProvider(
        LocalRichRenderColorDefaults provides defaults,
        LocalRichEffectiveBackground provides defaults.surface,
        LocalContentColor provides defaults.text,
        LocalRichNativeAnimationsEnabled provides nativeAnimationsEnabled,
        LocalRichAnimationHostId provides animationHostId,
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .testTag("rich-html-renderer"),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            model.blocks.forEach { block ->
                androidx.compose.runtime.key(block.blockId) {
                    RichBlockView(
                        block = block,
                        onSendInput = onSendInput,
                        modifier = Modifier.testTag("rich-html-block-${block.blockId}"),
                        root = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun RichBlockView(
    block: RichBlock,
    onSendInput: (String) -> Unit,
    modifier: Modifier = Modifier,
    root: Boolean = false,
    inline: Boolean = false,
) {
    when (block) {
        is RichTextBlock -> RichTextBlockView(block, modifier, onSendInput, inline)
        is RichTextFlowBlock -> RichTextFlowBlockView(block, modifier, onSendInput)
        is RichContainerBlock -> RichContainerBlockView(block, modifier, onSendInput, root, inline)
        is RichImageBlock -> RichImageBlockView(block, modifier)
        is RichTableBlock -> RichTableBlockView(block, modifier)
        is RichSvgBlock -> RichSvgBlockView(block, modifier)
        is RichMathBlock -> RichMathBlockView(block, modifier)
        is RichButtonBlock -> RichButtonBlockView(block, modifier, onSendInput)
        is RichDetailsBlock -> RichDetailsBlockView(block, modifier, onSendInput)
        is RichSnapshotIslandBlock -> RichSnapshotIslandBlockView(block, modifier, onSendInput)
        is RichUnsupportedBlock -> RichUnsupportedBlockView(block, modifier)
    }
}

@Composable
private fun RichContainerBlockView(
    block: RichContainerBlock,
    modifier: Modifier,
    onSendInput: (String) -> Unit,
    root: Boolean,
    inline: Boolean = false,
) {
    StyledContainer(block.style, modifier = modifier, root = root, animationKey = block.blockId, inline = inline) {
        ProvideTextStyle(LocalTextStyle.current.merge(block.style.toTextStyle(LocalContentColor.current))) {
            val positionedChildren = block.children.filter { it.style.isPositionedOverlay() }
                .sortedBy { it.style.zIndex }
            val flowChildren = block.children.filterNot { it.style.isPositionedOverlay() }
                .sortedWith(compareBy<RichBlock> { it.style.order }.thenBy { block.children.indexOf(it) })
            val backgroundPositionedChildren = positionedChildren.filter { it.style.zIndex <= 0f }
            val foregroundPositionedChildren = positionedChildren.filter { it.style.zIndex > 0f }
            val content: @Composable () -> Unit = {
                RichContainerFlowChildren(
                    children = flowChildren,
                    parentStyle = block.style,
                    onSendInput = onSendInput,
                )
            }
            when {
                positionedChildren.isEmpty() -> content()
                else -> {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        backgroundPositionedChildren.forEach { child ->
                            RichBlockView(
                                block = child,
                                onSendInput = onSendInput,
                                modifier = positionedChildModifier(child.style),
                            )
                        }
                        content()
                        foregroundPositionedChildren.forEach { child ->
                            RichBlockView(
                                block = child,
                                onSendInput = onSendInput,
                                modifier = positionedChildModifier(child.style),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RichContainerFlowChildren(
    children: List<RichBlock>,
    parentStyle: ComputedStyle,
    onSendInput: (String) -> Unit,
) {
    when {
        parentStyle.display.isFlexContainer() -> {
            RichFlexBoxChildren(children = children, parentStyle = parentStyle, onSendInput = onSendInput)
        }
        parentStyle.display.isGridContainer() -> {
            RichGridChildren(children = children, parentStyle = parentStyle, onSendInput = onSendInput)
        }
        else -> {
            Column(
                modifier = if (parentStyle.display.isBlockFilling()) Modifier.fillMaxWidth() else Modifier,
                verticalArrangement = Arrangement.spacedBy(parentStyle.rowGap),
            ) {
                children.forEach { child ->
                    RichBlockView(child, onSendInput, blockFlowModifier(child.style))
                }
            }
        }
    }
}

@Composable
private fun RichGridChildren(
    children: List<RichBlock>,
    parentStyle: ComputedStyle,
    onSendInput: (String) -> Unit,
) {
    Layout(
        modifier = if (parentStyle.display == RichDisplay.Grid) Modifier.fillMaxWidth() else Modifier,
        content = {
            children.forEach { child ->
                androidx.compose.runtime.key(child.blockId) {
                    RichBlockView(
                        block = child,
                        onSendInput = onSendInput,
                        modifier = Modifier
                            .richGridItemConstraints(child.style, parentStyle)
                            .zIndex(child.style.zIndex),
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val layoutWidth = constraints.maxWidth.takeIf { it != Constraints.Infinity }
            ?: constraints.minWidth
        val columnGapPx = parentStyle.columnGap.roundToPx().coerceAtLeast(0)
        val rowGapPx = parentStyle.rowGap.roundToPx().coerceAtLeast(0)
        val columnCount = gridColumnCount(parentStyle, layoutWidth, columnGapPx, children.size)
        val columnWidths = distributeGridTrackWidths(layoutWidth, columnCount, columnGapPx)
        val placements = mutableListOf<RichGridMeasuredItem>()
        val occupied = mutableSetOf<Long>()

        measurables.forEachIndexed { index, measurable ->
            val style = children[index].style
            val columnSpan = style.gridColumnSpan.coerceIn(1, columnCount)
            val rowSpan = style.gridRowSpan.coerceIn(1, 8)
            val cell = findRichGridCell(
                occupied = occupied,
                columnCount = columnCount,
                rowSpan = rowSpan,
                columnSpan = columnSpan,
                columnStart = style.gridColumnStart,
                rowStart = style.gridRowStart,
            )
            markRichGridOccupied(occupied, cell.row, cell.column, rowSpan, columnSpan)
            val itemWidth = columnWidths.spannedSize(cell.column, columnSpan, columnGapPx)
            val stretchWidth = parentStyle.alignItems == RichAlign.Stretch || style.alignSelf == RichAlign.Stretch
            val placeable = measurable.measure(
                Constraints(
                    minWidth = if (stretchWidth) itemWidth else 0,
                    maxWidth = itemWidth,
                    minHeight = 0,
                    maxHeight = Constraints.Infinity,
                )
            )
            placements += RichGridMeasuredItem(
                placeable = placeable,
                row = cell.row,
                column = cell.column,
                rowSpan = rowSpan,
                columnSpan = columnSpan,
            )
        }

        val rowCount = placements.maxOfOrNull { it.row + it.rowSpan } ?: 0
        val rowHeights = MutableList(rowCount) { 0 }
        placements.forEach { item ->
            val contentHeight = (item.placeable.height - rowGapPx * (item.rowSpan - 1)).coerceAtLeast(0)
            val currentHeight = (item.row until item.row + item.rowSpan).sumOf { rowHeights[it] }
            if (contentHeight > currentHeight) {
                var remaining = contentHeight - currentHeight
                for (row in item.row until item.row + item.rowSpan) {
                    val delta = remaining / (item.row + item.rowSpan - row)
                    rowHeights[row] += delta
                    remaining -= delta
                }
            }
        }

        val contentHeight = rowHeights.sum() + rowGapPx * (rowCount - 1).coerceAtLeast(0)
        val layoutHeight = contentHeight.constrainDimension(constraints.minHeight, constraints.maxHeight)
        val columnOffsets = columnWidths.runningOffsets(columnGapPx)
        val rowOffsets = rowHeights.runningOffsets(rowGapPx)
        layout(layoutWidth.constrainDimension(constraints.minWidth, constraints.maxWidth), layoutHeight) {
            placements.forEach { item ->
                item.placeable.placeRelative(
                    x = columnOffsets.getOrElse(item.column) { 0 },
                    y = rowOffsets.getOrElse(item.row) { 0 },
                )
            }
        }
    }
}

@OptIn(ExperimentalFlexBoxApi::class)
@Composable
private fun RichFlexBoxChildren(
    children: List<RichBlock>,
    parentStyle: ComputedStyle,
    onSendInput: (String) -> Unit,
) {
    FlexBox(
        modifier = if (parentStyle.display == RichDisplay.Flex) Modifier.fillMaxWidth() else Modifier,
        config = {
            direction(parentStyle.toFlexDirection())
            wrap(parentStyle.toFlexWrap())
            justifyContent(parentStyle.toFlexJustifyContent())
            alignItems(parentStyle.toFlexAlignItems())
            alignContent(parentStyle.toFlexAlignContent())
            gap(parentStyle.rowGap, parentStyle.columnGap)
        },
    ) {
        children.forEach { child ->
            CompositionLocalProvider(
                LocalRichFlexTextAlignOverride provides parentStyle.flexTextAlignOverride(child, children.size),
            ) {
                RichBlockView(
                    block = child,
                    onSendInput = onSendInput,
                    modifier = Modifier
                        .richFlexItemConstraints(child.style, parentStyle)
                        .flex {
                            order(child.style.order)
                            grow(child.style.flexGrow.coerceAtLeast(0f))
                            shrink(child.style.flexShrink.coerceAtLeast(0f))
                            child.style.toEffectiveFlexBasis()?.let(::basis)
                            child.style.alignSelf?.let { alignSelf(it.toFlexAlignSelf()) }
                        },
                )
            }
        }
    }
}

@Composable
private fun RichTextFlowBlockView(
    block: RichTextFlowBlock,
    modifier: Modifier,
    onSendInput: (String) -> Unit,
) {
    StyledContainer(block.style, modifier = modifier, root = false, animationKey = block.blockId) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(block.style.rowGap),
        ) {
            block.paragraphs.forEachIndexed { index, paragraph ->
                androidx.compose.runtime.key("${block.blockId}-p$index") {
                    RichTextFlowParagraphView(
                        paragraph = paragraph,
                        onSendInput = onSendInput,
                    )
                }
            }
        }
    }
}

@Composable
private fun RichTextFlowParagraphView(
    paragraph: RichTextFlowParagraph,
    onSendInput: (String) -> Unit,
) {
    val textClipBrush = paragraph.style.textClipBrush()
    val textStyle = LocalTextStyle.current.merge(
        paragraph.style.toTextStyle(if (textClipBrush != null) Color.White else LocalContentColor.current)
    )
    val inline = buildInlineRichText(paragraph.content, paragraph.inlineMath, emptyList(), textStyle, onSendInput)
    val inlinePaints = if (paragraph.inlineMath.isEmpty()) paragraph.inlinePaints else emptyList()
    var textLayout by remember(inline.text, inlinePaints) { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = inline.text,
        modifier = Modifier
            .then(paragraph.style.marginModifier())
            .padding(paragraph.style.padding.toPaddingValues(root = false))
            .inlineTextPaints(inlinePaints, textLayout)
            .textClipBrushMask(textClipBrush),
        inlineContent = inline.inlineContent,
        style = textStyle,
        maxLines = if (paragraph.style.whiteSpace == RichWhiteSpace.NoWrap) 1 else Int.MAX_VALUE,
        overflow = if (paragraph.style.textOverflow == RichTextOverflow.Ellipsis) {
            TextOverflow.Ellipsis
        } else {
            TextOverflow.Clip
        },
        onTextLayout = { textLayout = it },
    )
}

@Composable
private fun RichTextBlockView(
    block: RichTextBlock,
    modifier: Modifier,
    onSendInput: (String) -> Unit,
    inline: Boolean = false,
) {
    StyledContainer(block.style, modifier = modifier, root = false, animationKey = block.blockId, inline = inline) {
        val textClipBrush = block.style.textClipBrush()
        val flexTextAlignOverride = LocalRichFlexTextAlignOverride.current
        val textStyle = LocalTextStyle.current.merge(
            block.style.toTextStyle(if (textClipBrush != null) Color.White else LocalContentColor.current)
        ).withFlexTextAlignOverride(block.style, flexTextAlignOverride)
        val text = block.content
        val marker = block.listMarker
        val markerImage = block.style.listStyleImage
        if (marker != null && markerImage != null && block.inlineMath.isEmpty() && text.text.startsWith(marker)) {
            Row(
                modifier = if (block.style.listStylePosition == RichListStylePosition.Inside) Modifier else Modifier.padding(start = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(if (block.style.listStylePosition == RichListStylePosition.Inside) 4.dp else 6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                RichListImageMarker(
                    url = markerImage,
                    fallbackMarker = marker,
                    textStyle = textStyle,
                )
                Text(
                    text = text.subSequence(marker.length, text.length),
                    modifier = Modifier.textClipBrushMask(textClipBrush),
                    style = textStyle,
                    maxLines = if (block.style.whiteSpace == RichWhiteSpace.NoWrap) 1 else Int.MAX_VALUE,
                    overflow = if (block.style.textOverflow == RichTextOverflow.Ellipsis) TextOverflow.Ellipsis else TextOverflow.Clip,
                )
            }
            return@StyledContainer
        }
        val inline = buildInlineRichText(text, block.inlineMath, block.inlineBoxes, textStyle, onSendInput)
        val inlinePaints = if (block.inlineMath.isEmpty()) block.inlinePaints else emptyList()
        if (block.rendersAsInlineBoxFlow() && textClipBrush == null) {
            RichInlineBoxFlow(
                block = block,
                onSendInput = onSendInput,
            )
            return@StyledContainer
        }
        var textLayout by remember(inline.text, inlinePaints) { mutableStateOf<TextLayoutResult?>(null) }
        Text(
            text = inline.text,
            modifier = Modifier
                .inlineTextPaints(inlinePaints, textLayout)
                .textClipBrushMask(textClipBrush),
            inlineContent = inline.inlineContent,
            style = textStyle,
            maxLines = if (block.style.whiteSpace == RichWhiteSpace.NoWrap) 1 else Int.MAX_VALUE,
            overflow = if (block.style.textOverflow == RichTextOverflow.Ellipsis) TextOverflow.Ellipsis else TextOverflow.Clip,
            onTextLayout = { textLayout = it },
        )
    }
}

@Composable
private fun RichInlineBoxFlow(
    block: RichTextBlock,
    onSendInput: (String) -> Unit,
) {
    val horizontalAlignment = block.style.inlineFlowHorizontalAlignment()
    val lineGap = block.style.inlineFlowLineGap()
    Layout(
        content = {
            block.inlineBoxes.forEach { run ->
                androidx.compose.runtime.key(run.block.blockId) {
                    RichBlockView(
                        block = run.block,
                        onSendInput = onSendInput,
                        modifier = Modifier,
                        inline = true,
                    )
                }
            }
        },
    ) { measurables, constraints ->
        if (measurables.isEmpty()) {
            return@Layout layout(constraints.minWidth, constraints.minHeight) {}
        }
        val maxWidth = constraints.maxWidth.takeIf { it != Constraints.Infinity }
            ?: measurables.sumOf { measurable ->
                measurable.minIntrinsicWidth(Constraints.Infinity)
            }.coerceAtLeast(constraints.minWidth)
        val childConstraints = Constraints(
            minWidth = 0,
            maxWidth = maxWidth,
            minHeight = 0,
            maxHeight = Constraints.Infinity,
        )
        val placeables = measurables.map { it.measure(childConstraints) }
        val lines = mutableListOf<InlineBoxFlowLine>()
        var current = mutableListOf<Placeable>()
        var lineWidth = 0
        var lineHeight = 0

        fun flushLine() {
            if (current.isEmpty()) return
            lines += InlineBoxFlowLine(
                placeables = current,
                width = lineWidth,
                height = lineHeight,
            )
            current = mutableListOf()
            lineWidth = 0
            lineHeight = 0
        }

        placeables.forEach { placeable ->
            val childWidth = placeable.width.coerceAtMost(maxWidth)
            if (current.isNotEmpty() && lineWidth + childWidth > maxWidth) {
                flushLine()
            }
            current += placeable
            lineWidth += childWidth
            lineHeight = max(lineHeight, placeable.height)
        }
        flushLine()

        val gapPx = lineGap.roundToPx().coerceAtLeast(0)
        val contentHeight = lines.sumOf { it.height } + gapPx * (lines.size - 1).coerceAtLeast(0)
        val layoutWidth = maxWidth.constrainDimension(constraints.minWidth, constraints.maxWidth)
        val layoutHeight = contentHeight.constrainDimension(constraints.minHeight, constraints.maxHeight)
        layout(layoutWidth, layoutHeight) {
            var y = 0
            lines.forEach { line ->
                val x = horizontalAlignment.align(
                    size = line.width.coerceAtMost(layoutWidth),
                    space = layoutWidth,
                    layoutDirection = layoutDirection,
                )
                var childX = x
                line.placeables.forEach { placeable ->
                    placeable.placeRelative(childX, y + (line.height - placeable.height) / 2)
                    childX += placeable.width
                }
                y += line.height + gapPx
            }
        }
    }
}

private data class InlineBoxFlowLine(
    val placeables: List<Placeable>,
    val width: Int,
    val height: Int,
)

private fun RichTextBlock.rendersAsInlineBoxFlow(): Boolean {
    if (inlineBoxes.isEmpty() || inlineMath.isNotEmpty() || inlinePaints.isNotEmpty()) return false
    val placeholderCount = content.text.count { it == INLINE_RICH_BOX_PLACEHOLDER_CHAR }
    if (placeholderCount != inlineBoxes.size) return false
    return content.text.all { it == INLINE_RICH_BOX_PLACEHOLDER_CHAR || it.isWhitespace() }
}

private fun ComputedStyle.inlineFlowHorizontalAlignment(): Alignment.Horizontal = when (textAlign) {
    TextAlign.Center -> Alignment.CenterHorizontally
    TextAlign.End,
    TextAlign.Right -> Alignment.End
    else -> Alignment.Start
}

private fun ComputedStyle.inlineFlowLineGap(): Dp {
    return when {
        rowGap > 0.dp -> rowGap
        gap > 0.dp -> gap
        else -> 6.dp
    }
}

private const val INLINE_RICH_BOX_PLACEHOLDER_CHAR = '\uFFFC'

private fun Modifier.inlineTextPaints(
    paints: List<InlineTextPaintRun>,
    layout: TextLayoutResult?,
): Modifier {
    if (paints.isEmpty() || layout == null) return this
    return drawBehind {
        paints.forEach { paint ->
            val start = paint.start.coerceIn(0, layout.layoutInput.text.length)
            val end = paint.end.coerceIn(start, layout.layoutInput.text.length)
            if (end <= start) return@forEach
            val startLine = layout.getLineForOffset(start)
            val endLine = layout.getLineForOffset((end - 1).coerceAtLeast(start))
            for (line in startLine..endLine) {
                val lineStart = maxOf(start, layout.getLineStart(line))
                val lineEnd = minOf(end, layout.getLineEnd(line, visibleEnd = true))
                if (lineEnd <= lineStart) continue
                val left = layout.getBoundingBox(lineStart).left.coerceAtLeast(0f)
                val right = layout.getBoundingBox((lineEnd - 1).coerceAtLeast(lineStart)).right.coerceAtMost(size.width)
                if (right <= left) continue
                val lineTop = layout.getLineTop(line)
                val lineBottom = layout.getLineBottom(line)
                val lineHeight = (lineBottom - lineTop).coerceAtLeast(1f)
                val requestedHeight = lineHeight * paint.heightFraction.coerceIn(0.02f, 1f)
                val height = maxOf(requestedHeight, paint.minHeight.toPx()).coerceAtMost(lineHeight)
                val top = (lineTop + lineHeight * paint.topFraction.coerceIn(0f, 1f))
                    .coerceAtMost(lineBottom - height)
                val radius = paint.cornerRadius.toPx()
                drawRoundRect(
                    color = paint.color,
                    topLeft = Offset(left, top),
                    size = Size(right - left, height),
                    cornerRadius = CornerRadius(radius, radius),
                )
            }
        }
    }
}

@Composable
private fun RichListImageMarker(
    url: String,
    fallbackMarker: String,
    textStyle: TextStyle,
) {
    var failed by remember(url) { mutableStateOf(false) }
    val mediaRequest = remember(url) {
        RichMediaRequest.fromSource(url, kind = RichMediaKind.ListStyleImage, widthPx = 16, heightPx = 16)
    }
    val unsafe = RichMediaLoader.safety(mediaRequest) != RichMediaSafety.Safe
    if (failed || unsafe) {
        Box(
            modifier = Modifier
                .width(18.dp)
                .height(20.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(fallbackMarker, style = textStyle)
        }
    } else {
        Box(
            modifier = Modifier
                .width(18.dp)
                .height(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = RichMediaLoader.safeData(mediaRequest),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                contentScale = ContentScale.Fit,
                onError = { failed = true },
            )
        }
    }
}

@Composable
private fun RichImageBlockView(block: RichImageBlock, modifier: Modifier) {
    StyledContainer(block.style, modifier = modifier, root = false, animationKey = block.blockId) {
        ZoomableAsyncImage(
            model = block.src,
            contentDescription = block.alt,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = block.style.maxHeight ?: block.style.height.dpOrNull() ?: 420.dp),
            contentScale = block.style.objectFit.toContentScale(),
            enforceRichMediaSafety = true,
            richMediaKind = RichMediaKind.Image,
        )
    }
}

@Composable
private fun RichTableBlockView(block: RichTableBlock, modifier: Modifier) {
    StyledContainer(
        block.style.withoutNestedTableScrollOverflow(),
        modifier = modifier,
        root = false,
        animationKey = block.blockId,
    ) {
        val defaults = LocalRichRenderColorDefaults.current
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val caption: @Composable () -> Unit = {
                block.caption?.let { caption ->
                    Text(
                        caption,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.labelMedium.merge(block.captionStyle?.toTextStyle(LocalContentColor.current) ?: TextStyle.Default),
                        textAlign = block.captionStyle?.textAlign?.takeIf { it != TextAlign.Unspecified } ?: TextAlign.Center,
                    )
                }
            }
            if (block.style.captionSide == RichCaptionSide.Top) caption()
            val sections = block.sections.takeIf { it.isNotEmpty() } ?: legacyTableSections(block)
            val headerRows = sections.filter { it.type == RichTableSectionType.Head }.flatMap { it.rows }
            val bodySections = sections.filterNot { it.type == RichTableSectionType.Head }
            val bodyRows = bodySections.flatMap { it.rows }
            val headerCells = headerRows.firstOrNull().orEmpty()
            val headers = headerCells.map { cell ->
                @Composable { TableCellContent(cell) }
            }
            val tableRows = headerRows.drop(1) + bodyRows
            val tableRowSectionTypes = List(headerRows.drop(1).size) { RichTableSectionType.Head } +
                bodySections.flatMap { section -> List(section.rows.size) { section.type } }
            val rows = tableRows.map { row ->
                row.map { cell -> @Composable { TableCellContent(cell) } }
            }
            if (headers.isNotEmpty() || rows.isNotEmpty()) {
                val hasHorizontalScrollAncestor = LocalRichHorizontalScrollAncestor.current
                val columnCount = max(
                    headerCells.sumOf { it.colspan.coerceIn(1, 12) },
                    tableRows.maxOfOrNull { row -> row.sumOf { it.colspan.coerceIn(1, 12) } } ?: 0,
                )
                CompositionLocalProvider(
                    LocalRichHorizontalScrollAncestor provides true,
                ) {
                    SpannedDataTable(
                        headers = headers,
                        rows = rows,
                        modifier = Modifier.padding(vertical = 6.dp),
                        cellBorder = if (block.style.borderCollapse == RichBorderCollapse.Collapse) {
                            BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                        } else {
                            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                        },
                        headerBackground = block.style.backgroundColor ?: defaults.tableHeaderBackground,
                        columnMinWidths = List(columnCount) { 80.dp },
                        columnMaxWidths = List(columnCount) { 260.dp },
                        headerColSpans = headerCells.map { it.colspan },
                        rowColSpans = tableRows.map { row -> row.map { it.colspan } },
                        rowSpans = tableRows.map { row -> row.map { it.rowspan } },
                        headerCellStyles = headerCells.map { it.toDataTableCellStyle() },
                        rowCellStyles = tableRows.map { row -> row.map { it.toDataTableCellStyle() } },
                        rowSectionTypes = tableRowSectionTypes.map { it.toDataTableSectionType() },
                        collapseBorders = block.style.borderCollapse == RichBorderCollapse.Collapse,
                        horizontalScrollEnabled = !hasHorizontalScrollAncestor,
                    )
                }
            }
            if (block.style.captionSide == RichCaptionSide.Bottom) caption()
        }
    }
}

private fun ComputedStyle.withoutNestedTableScrollOverflow(): ComputedStyle {
    return when (overflow) {
        RichOverflow.Scroll,
        RichOverflow.Auto -> copy(overflow = RichOverflow.Visible)
        RichOverflow.Visible,
        RichOverflow.Hidden -> this
    }
}

@Composable
private fun TableCellContent(cell: RichTableCell) {
    StyledContainer(cell.style.withoutNestedTableScrollOverflow(), root = false, animationKey = null) {
        Text(
            text = cell.content,
            style = cell.style.toTextStyle(LocalContentColor.current),
            maxLines = if (cell.style.whiteSpace == RichWhiteSpace.NoWrap) 1 else Int.MAX_VALUE,
            overflow = if (cell.style.textOverflow == RichTextOverflow.Ellipsis) TextOverflow.Ellipsis else TextOverflow.Clip,
        )
    }
}

private fun RichTableCell.toDataTableCellStyle(): DataTableCellStyle {
    return DataTableCellStyle(
        background = style.backgroundColor,
        alignment = style.toTableCellAlignment(),
    )
}

private fun RichTableSectionType.toDataTableSectionType(): me.rerere.rikkahub.ui.components.table.DataTableSectionType {
    return when (this) {
        RichTableSectionType.Head -> me.rerere.rikkahub.ui.components.table.DataTableSectionType.Head
        RichTableSectionType.Body -> me.rerere.rikkahub.ui.components.table.DataTableSectionType.Body
        RichTableSectionType.Foot -> me.rerere.rikkahub.ui.components.table.DataTableSectionType.Foot
    }
}

private fun ComputedStyle.toTableCellAlignment(): Alignment {
    val horizontal = when (textAlign) {
        TextAlign.Center -> Alignment.CenterHorizontally
        TextAlign.End,
        TextAlign.Right -> Alignment.End
        else -> Alignment.Start
    }
    val vertical = when (verticalAlign) {
        RichVerticalAlign.Middle -> Alignment.CenterVertically
        RichVerticalAlign.Bottom -> Alignment.Bottom
        else -> Alignment.Top
    }
    return when (vertical) {
        Alignment.CenterVertically -> when (horizontal) {
            Alignment.CenterHorizontally -> Alignment.Center
            Alignment.End -> Alignment.CenterEnd
            else -> Alignment.CenterStart
        }
        Alignment.Bottom -> when (horizontal) {
            Alignment.CenterHorizontally -> Alignment.BottomCenter
            Alignment.End -> Alignment.BottomEnd
            else -> Alignment.BottomStart
        }
        else -> when (horizontal) {
            Alignment.CenterHorizontally -> Alignment.TopCenter
            Alignment.End -> Alignment.TopEnd
            else -> Alignment.TopStart
        }
    }
}

private fun legacyTableSections(block: RichTableBlock): List<RichTableSection> {
    return buildList {
        if (block.headers.isNotEmpty()) {
            add(RichTableSection(RichTableSectionType.Head, listOf(block.headers)))
        }
        if (block.rows.isNotEmpty()) {
            add(RichTableSection(RichTableSectionType.Body, block.rows))
        }
    }
}

@Composable
private fun RichSvgBlockView(block: RichSvgBlock, modifier: Modifier) {
    StyledContainer(block.style, modifier = modifier, root = false, animationKey = block.blockId) {
        val preparedCommands = remember(block.model) {
            RichSvgPreparedDrawCache.prepare(block.model)
        }
        Canvas(
            modifier = Modifier
                .width(block.model.width)
                .height(block.model.height),
        ) {
            val scaleX = size.width / block.model.viewBox.width.coerceAtLeast(1f)
            val scaleY = size.height / block.model.viewBox.height.coerceAtLeast(1f)
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.save()
                native.scale(scaleX, scaleY)
                native.translate(-block.model.viewBox.x, -block.model.viewBox.y)
                preparedCommands.forEach { command -> command.draw(native) }
                native.restore()
            }
        }
    }
}

@Composable
private fun RichSnapshotIslandBlockView(
    block: RichSnapshotIslandBlock,
    modifier: Modifier,
    onSendInput: (String) -> Unit,
) {
    if (block.sourceHtml.isBlank()) {
        block.fallbackBlock?.let {
            RichBlockView(block = it, onSendInput = onSendInput, modifier = modifier)
            return
        }
    }
    val context = LocalContext.current
    remember(context) {
        RichRenderHeightCache.initialize(context.applicationContext)
        true
    }
    val density = LocalDensity.current
    val scrollState = LocalRichRenderScrollState.current
    val dark = isSystemInDarkTheme()
    val colorScheme = MaterialTheme.colorScheme
    val theme = remember(colorScheme, dark) {
        BubbleTheme(
            dark = dark,
            background = colorScheme.background.toCssHex(),
            onBackground = colorScheme.onBackground.toCssHex(),
            surface = colorScheme.surfaceVariant.toCssHex(),
            onSurface = colorScheme.onSurface.toCssHex(),
            primary = colorScheme.primary.toCssHex(),
            outline = colorScheme.outlineVariant.toCssHex(),
        )
    }
    val themeSignature = remember(theme) {
        listOf(
            theme.dark,
            theme.background,
            theme.onBackground,
            theme.surface,
            theme.onSurface,
            theme.primary,
            theme.outline,
        ).joinToString("|")
    }

    var failed by remember(block.blockId, block.sourceDigest) { mutableStateOf(false) }
    if (failed && block.fallbackBlock != null) {
        RichBlockView(block = block.fallbackBlock, onSendInput = onSendInput, modifier = modifier)
        return
    }

    StyledContainer(block.style, modifier = modifier, root = false, animationKey = block.blockId) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val widthPx = remember(maxWidth, density.density) {
                val width = if (maxWidth != Dp.Infinity && maxWidth > 0.dp) maxWidth else 360.dp
                with(density) { width.roundToPx() }.coerceAtLeast(1)
            }
            val cacheKey = remember(
                block.sourceDigest,
                widthPx,
                density.density,
                density.fontScale,
                dark,
                themeSignature,
                block.reason,
            ) {
                RichHtmlSnapshotCacheKey(
                    htmlKey = block.sourceDigest,
                    widthPx = widthPx,
                    densityBucket = (density.density * 100).roundToInt(),
                    fontScaleBucket = (density.fontScale * 100).roundToInt(),
                    dark = dark,
                    themeHash = themeSignature.hashCode(),
                    scope = "snapshot-island",
                    reason = block.reason.name,
                )
            }
            val heightCacheKey = remember(
                block.sourceDigest,
                widthPx,
                density.fontScale,
                density.density,
                dark,
            ) {
                RichRenderHeightCache.key(
                    id = block.sourceDigest,
                    viewportWidthDp = with(density) { widthPx.toDp().value },
                    fontScale = density.fontScale,
                    density = density.density,
                    themeBucket = if (dark) "dark" else "light",
                    contentType = "snapshot-island",
                )
            }
            val cachedHeightEntry = remember(heightCacheKey) { RichRenderHeightCache.getEntry(heightCacheKey) }
            var entry by remember(cacheKey) { mutableStateOf(RichHtmlSnapshotCache.get(cacheKey)) }
            val placeholderHeight = remember(block.estimatedHeightPx, cachedHeightEntry, density.density) {
                val px = cachedHeightEntry?.heightPx ?: block.estimatedHeightPx ?: with(density) { 144.dp.roundToPx() }
                with(density) { px.coerceIn(48, 1_800).toDp() }
            }
            LaunchedEffect(heightCacheKey, cachedHeightEntry) {
                RichHtmlRenderTelemetry.recordHeightCache(
                    id = heightCacheKey.id,
                    contentType = "snapshot-island",
                    hit = cachedHeightEntry != null,
                    heightPx = cachedHeightEntry?.heightPx,
                    confidence = cachedHeightEntry?.confidence?.name,
                    rendererVersion = heightCacheKey.rendererVersion,
                    documentSchemaVersion = heightCacheKey.documentSchemaVersion,
                    persistent = true,
                )
            }
            LaunchedEffect(
                cacheKey,
                scrollState.scrolling,
                scrollState.scrollInProgress,
                scrollState.fastScrolling,
            ) {
                RichHtmlSnapshotCache.get(cacheKey)?.let {
                    entry = it
                    RichRenderHeightCache.put(
                        key = heightCacheKey,
                        heightPx = it.heightPx,
                        confidence = RichRenderHeightConfidence.MeasuredSnapshot,
                    )
                    RichHtmlRenderTelemetry.recordSnapshotIslandRender(
                        id = block.sourceDigest,
                        blockId = block.blockId,
                        reason = block.reason.name,
                        widthPx = it.widthPx,
                        heightPx = it.heightPx,
                        cacheHit = true,
                        renderTimeMs = it.renderTimeMs,
                        heightCacheHit = cachedHeightEntry != null,
                        styleBoundary = block.styleBoundary.telemetryName,
                        queueWaitMs = it.queueWaitMs,
                    )
                    return@LaunchedEffect
                }
                if (shouldSkipSnapshotIslandRenderForScroll(scrollState)) {
                    RichHtmlRenderTelemetry.recordSnapshotIslandRender(
                        id = block.sourceDigest,
                        blockId = block.blockId,
                        reason = block.reason.name,
                        widthPx = widthPx,
                        heightPx = null,
                        cacheHit = false,
                        renderTimeMs = null,
                        heightCacheHit = cachedHeightEntry != null,
                        fallbackReason = snapshotIslandScrollSkipReason(scrollState),
                        styleBoundary = block.styleBoundary.telemetryName,
                    )
                    return@LaunchedEffect
                }
                if (entry == null) {
                    delay(SNAPSHOT_ISLAND_RENDER_START_DELAY_MS)
                    if (shouldSkipSnapshotIslandRenderForScroll(scrollState)) {
                        RichHtmlRenderTelemetry.recordSnapshotIslandRender(
                            id = block.sourceDigest,
                            blockId = block.blockId,
                            reason = block.reason.name,
                            widthPx = widthPx,
                            heightPx = null,
                            cacheHit = false,
                            renderTimeMs = null,
                            heightCacheHit = cachedHeightEntry != null,
                            fallbackReason = snapshotIslandScrollSkipReason(scrollState),
                            styleBoundary = block.styleBoundary.telemetryName,
                        )
                        return@LaunchedEffect
                    }
                }
                val payload = BubblePayload(
                    id = "snapshot-island-${block.sourceDigest.replace(':', '-')}-${block.blockId}",
                    rawContent = block.sourceHtml,
                    renderMode = BubbleRenderMode.RICH_HTML,
                    theme = theme,
                    isStreaming = false,
                    allowScript = false,
                )
                val result = runCatching {
                    RichHtmlSnapshotCache.getOrRenderResult(cacheKey) {
                        when (val snapshot = BubbleSnapshotRenderer.render(
                            context,
                            BubbleSnapshotRequest(payload, widthPx, maxHeightPx = 1_800),
                        )) {
                            is BubbleSnapshotResult.Success -> RichHtmlSnapshotEntry(
                                bitmap = snapshot.bitmap,
                                widthPx = snapshot.widthPx,
                                heightPx = snapshot.heightPx,
                                renderTimeMs = snapshot.renderTimeMs,
                                queueWaitMs = snapshot.queueWaitMs,
                                sessionReused = snapshot.sessionReused,
                            )
                            is BubbleSnapshotResult.Failure -> null
                        }
                    }
                }.getOrNull()
                val nextEntry = result?.entry
                if (nextEntry == null) {
                    failed = true
                    RichHtmlRenderTelemetry.recordSnapshotIslandRender(
                        id = block.sourceDigest,
                        blockId = block.blockId,
                        reason = block.reason.name,
                        widthPx = widthPx,
                        heightPx = null,
                        cacheHit = false,
                        renderTimeMs = null,
                        heightCacheHit = cachedHeightEntry != null,
                        fallbackReason = "snapshot-failed",
                        styleBoundary = block.styleBoundary.telemetryName,
                    )
                } else {
                    entry = nextEntry
                    RichRenderHeightCache.put(
                        key = heightCacheKey,
                        heightPx = nextEntry.heightPx,
                        confidence = RichRenderHeightConfidence.MeasuredSnapshot,
                    )
                    RichHtmlRenderTelemetry.recordSnapshotIslandRender(
                        id = block.sourceDigest,
                        blockId = block.blockId,
                        reason = block.reason.name,
                        widthPx = nextEntry.widthPx,
                        heightPx = nextEntry.heightPx,
                        cacheHit = result.cacheHit,
                        renderTimeMs = nextEntry.renderTimeMs,
                        heightCacheHit = cachedHeightEntry != null,
                        styleBoundary = block.styleBoundary.telemetryName,
                        queueWaitMs = nextEntry.queueWaitMs,
                    )
                }
            }
            val ready = entry
            if (ready == null) {
                SnapshotIslandPlaceholder(height = placeholderHeight)
            } else {
                Image(
                    bitmap = ready.bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(density) { ready.heightPx.toDp() })
                        .testTag("rich-html-snapshot-island"),
                )
            }
        }
    }
}

internal fun shouldSkipSnapshotIslandRenderForScroll(scrollState: RichRenderScrollState): Boolean =
    scrollState.scrolling || scrollState.scrollInProgress || scrollState.fastScrolling

internal fun snapshotIslandScrollSkipReason(scrollState: RichRenderScrollState): String =
    when {
        scrollState.fastScrolling -> "fast-scroll-skip"
        scrollState.scrollInProgress -> "scroll-skip"
        else -> "recent-scroll-skip"
    }

private const val SNAPSHOT_ISLAND_RENDER_START_DELAY_MS = 96L

@Composable
private fun SnapshotIslandPlaceholder(height: Dp) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .testTag("rich-html-snapshot-island-placeholder"),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
    ) {
        Box(Modifier.fillMaxSize())
    }
}

@Composable
private fun RichMathBlockView(block: RichMathBlock, modifier: Modifier) {
    StyledContainer(block.style, modifier = modifier, root = false, animationKey = block.blockId) {
        if (block.inline) {
            MathInline(latex = block.latex, fontSize = block.style.fontSize)
        } else {
            MathBlock(
                latex = block.latex,
                modifier = Modifier.fillMaxWidth(),
                fontSize = block.style.fontSize,
            )
        }
    }
}

@Composable
private fun RichButtonBlockView(
    block: RichButtonBlock,
    modifier: Modifier,
    onSendInput: (String) -> Unit,
) {
    val click = { if (block.action.isNotBlank()) onSendInput(block.action) }
    StyledContainer(
        style = block.style,
        modifier = modifier.testTag("rich-html-button"),
        root = false,
        animationKey = block.blockId,
        onClick = click.takeIf { block.action.isNotBlank() },
    ) {
        val textStyle = LocalTextStyle.current.merge(block.style.toTextStyle(LocalContentColor.current))
        val buttonTextStyle = textStyle.withButtonTextAlignment(block.style)
        val inline = buildInlineRichText(block.label, block.inlineMath, block.inlineBoxes, textStyle, onSendInput)
        val inlinePaints = if (block.inlineMath.isEmpty()) block.inlinePaints else emptyList()
        var textLayout by remember(inline.text, inlinePaints) { mutableStateOf<TextLayoutResult?>(null) }
        ButtonContentLayout(style = block.style) {
            if (block.children.isNotEmpty()) {
                RichContainerFlowChildren(
                    children = block.children,
                    parentStyle = block.style,
                    onSendInput = onSendInput,
                )
            } else {
                Text(
                    text = inline.text,
                    modifier = Modifier.inlineTextPaints(inlinePaints, textLayout),
                    inlineContent = inline.inlineContent,
                    style = buttonTextStyle,
                    color = LocalContentColor.current,
                    onTextLayout = { textLayout = it },
                )
            }
        }
    }
}

@Composable
private fun ButtonContentLayout(
    style: ComputedStyle,
    content: @Composable () -> Unit,
) {
    val alignment = style.buttonContentAlignment()
    Layout(content = content) { measurables, constraints ->
        val measurable = measurables.singleOrNull()
        if (measurable == null) {
            layout(constraints.minWidth, constraints.minHeight) {}
        } else {
            val fillWidth = style.buttonContentFillsWidth() && constraints.maxWidth != Constraints.Infinity
            val textConstraints = constraints.copy(
                minWidth = if (fillWidth) constraints.maxWidth else 0,
                minHeight = 0,
            )
            val placeable = measurable.measure(textConstraints)
            val layoutWidth = if (fillWidth) {
                constraints.maxWidth
            } else {
                placeable.width.constrainDimension(constraints.minWidth, constraints.maxWidth)
            }
            val layoutHeight = maxOf(placeable.height, constraints.minHeight)
                .constrainDimension(constraints.minHeight, constraints.maxHeight)
            layout(layoutWidth, layoutHeight) {
                val offset = alignment.align(
                    size = IntSize(placeable.width, placeable.height),
                    space = IntSize(layoutWidth, layoutHeight),
                    layoutDirection = layoutDirection,
                )
                placeable.placeRelative(offset.x, offset.y)
            }
        }
    }
}

private fun TextStyle.withButtonTextAlignment(style: ComputedStyle): TextStyle {
    if (textAlign != TextAlign.Unspecified) return this
    val cssTextAlign = style.textAlign.takeIf { it != TextAlign.Unspecified }
    val flexTextAlign = if (style.display.isFlexContainer()) {
        when (style.justifyContent) {
            RichJustify.Center,
            RichJustify.SpaceAround,
            RichJustify.SpaceEvenly -> TextAlign.Center

            RichJustify.End -> TextAlign.End
            RichJustify.Start,
            RichJustify.SpaceBetween -> null
        }
    } else {
        null
    }
    return copy(textAlign = cssTextAlign ?: flexTextAlign ?: TextAlign.Center)
}

private fun ComputedStyle.buttonContentFillsWidth(): Boolean {
    return when {
        display.isFlexContainer() || display.isGridContainer() -> true
        flexGrow > 0f || flexBasis != RichSize.Auto -> true
        width != RichSize.Auto || minWidth != null || maxWidth != null -> true
        else -> false
    }
}

private fun ComputedStyle.buttonContentAlignment(): Alignment {
    val horizontal = when {
        display.isFlexContainer() || display.isGridContainer() -> when (justifyContent) {
            RichJustify.Center,
            RichJustify.SpaceAround,
            RichJustify.SpaceEvenly -> Alignment.CenterHorizontally

            RichJustify.End -> Alignment.End
            RichJustify.Start,
            RichJustify.SpaceBetween -> Alignment.Start
        }

        textAlign == TextAlign.Center -> Alignment.CenterHorizontally
        textAlign == TextAlign.End || textAlign == TextAlign.Right -> Alignment.End
        else -> Alignment.Start
    }
    val vertical = when {
        display.isFlexContainer() || display.isGridContainer() -> when (alignItems) {
            RichAlign.Center -> Alignment.CenterVertically
            RichAlign.End -> Alignment.Bottom
            RichAlign.Start,
            RichAlign.Stretch,
            RichAlign.Baseline -> Alignment.Top
        }

        else -> Alignment.CenterVertically
    }
    return when (horizontal) {
        Alignment.CenterHorizontally -> when (vertical) {
            Alignment.CenterVertically -> Alignment.Center
            Alignment.Bottom -> Alignment.BottomCenter
            else -> Alignment.TopCenter
        }

        Alignment.End -> when (vertical) {
            Alignment.CenterVertically -> Alignment.CenterEnd
            Alignment.Bottom -> Alignment.BottomEnd
            else -> Alignment.TopEnd
        }

        else -> when (vertical) {
            Alignment.CenterVertically -> Alignment.CenterStart
            Alignment.Bottom -> Alignment.BottomStart
            else -> Alignment.TopStart
        }
    }
}

@Composable
private fun RichDetailsBlockView(
    block: RichDetailsBlock,
    modifier: Modifier,
    onSendInput: (String) -> Unit,
) {
    var expanded by remember(block.blockId) { mutableStateOf(block.open) }
    StyledContainer(block.style, modifier = modifier, root = false, animationKey = block.blockId) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("rich-html-details")
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (expanded) "v" else ">", color = MaterialTheme.colorScheme.primary)
                Text(block.summary, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            }
            if (expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    block.children.forEach { RichBlockView(it, onSendInput) }
                }
            }
        }
    }
}

@Composable
private fun RichUnsupportedBlockView(block: RichUnsupportedBlock, modifier: Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("rich-html-unsupported"),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text = block.previewText.ifBlank { "此富内容超出聊天列表安全渲染范围" },
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StyledContainer(
    style: ComputedStyle,
    modifier: Modifier = Modifier,
    root: Boolean,
    animationKey: String?,
    inline: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = style.shape(default = 0.dp)
    val inlineBox = inline || style.display.isInlineBox()
    val defaults = LocalRichRenderColorDefaults.current
    val parentBackground = LocalRichEffectiveBackground.current
    val effectiveOpacity = style.effectiveOpacity()
    val backgroundColor = style.backgroundColor?.copy(alpha = style.backgroundColor.alpha * effectiveOpacity)
    val effectiveBackground = RichColorResolver.effectiveBackground(
        parent = parentBackground,
        declared = backgroundColor,
        fallback = defaults.surface,
    )
    val paintBoxBackground = style.backgroundClip != RichBackgroundBox.Text
    val backgroundLayers = style.backgroundLayers.takeIf { paintBoxBackground && it.isNotEmpty() }.orEmpty()
    val hasLayerBackground = backgroundLayers.isNotEmpty()
    val brush = style.backgroundBrush().takeIf { paintBoxBackground }
    val contentColor = style.resolvedContainerContentColor(
        fallback = LocalContentColor.current,
        effectiveBackground = effectiveBackground,
        effectiveOpacity = effectiveOpacity,
    )
    val surfaceBorder = style.surfaceBorderStroke()
    val drawBorder = style.hasCustomDrawBorder()
    val backdropApproximation = !inlineBox && style.backdropFilter.hasSupportedEffect
    var outer = modifier
        .then(style.marginModifier())
        .then(style.baseModifier(root))
    if (inlineBox) {
        outer = outer.clipToBounds()
    } else {
        outer = outer.then(style.richShadowModifier(shape))
    }
    outer = outer.then(style.cssClipPathModifier())
    outer = outer.then(style.cssMaskModifier())
    if (!inlineBox) outer = outer.then(style.cssBlurFilterModifier())
    outer = outer.then(style.cssColorFilterModifier())
    outer = outer.then(style.nativeAnimationModifier(animationKey))
    if (style.overflow == RichOverflow.Hidden) outer = outer.clip(shape)
    if (onClick != null) outer = outer.clickable(onClick = onClick)
    val hasHorizontalScrollAncestor = LocalRichHorizontalScrollAncestor.current
    val createsHorizontalScroll = (style.overflow == RichOverflow.Scroll || style.overflow == RichOverflow.Auto) &&
        !hasHorizontalScrollAncestor
    if (createsHorizontalScroll) {
        outer = outer.horizontalScroll(rememberScrollState())
    }

    if (backgroundColor != null || brush != null || hasLayerBackground || surfaceBorder != null || drawBorder || backdropApproximation || root || (paintBoxBackground && style.backgroundUrl != null)) {
        Surface(
            modifier = outer,
            shape = shape,
            color = if (brush == null && !hasLayerBackground && (style.backgroundUrl == null || !paintBoxBackground)) backgroundColor ?: Color.Transparent else Color.Transparent,
            contentColor = contentColor,
            border = surfaceBorder,
        ) {
            CompositionLocalProvider(
                LocalRichEffectiveBackground provides effectiveBackground,
                LocalContentColor provides contentColor,
                LocalRichHorizontalScrollAncestor provides (hasHorizontalScrollAncestor || createsHorizontalScroll),
            ) {
                Box(
                    Modifier
                        .then(if (backdropApproximation) style.backdropApproximationModifier(shape, defaults) else Modifier)
                        .then(if (hasLayerBackground) style.backgroundLayersModifier(backgroundLayers, shape, backgroundColor) else Modifier)
                        .then(if (!hasLayerBackground && brush != null) style.backgroundBrushModifier(brush, shape) else Modifier)
                        .then(if (!hasLayerBackground && paintBoxBackground && style.backgroundUrl != null && style.backgroundRepeat != RichBackgroundRepeat.NoRepeat) style.repeatedBackgroundModifier() else Modifier)
                        .then(if (paintBoxBackground && style.backgroundUrl != null) Modifier.background(Color.Transparent, shape) else Modifier)
                        .then(if (drawBorder) style.borderDrawModifier(shape) else Modifier)
                        .padding(style.padding.toPaddingValues(root))
                ) {
                    style.backgroundUrl?.takeIf { !hasLayerBackground && paintBoxBackground && style.backgroundRepeat == RichBackgroundRepeat.NoRepeat }?.let { url ->
                        val areaPadding = style.backgroundAreaPadding(style.backgroundOrigin)
                        ZoomableAsyncImage(
                            model = url,
                            contentDescription = null,
                            modifier = Modifier
                                .matchParentSize()
                                .clip(shape)
                                .padding(areaPadding.toPaddingValues(root = false)),
                            contentScale = style.backgroundSize.toContentScale(),
                            alignment = style.backgroundPosition.toAlignment(),
                            alpha = 0.45f,
                            enforceRichMediaSafety = true,
                            richMediaKind = RichMediaKind.BackgroundImage,
                            zoomEnabled = false,
                        )
                    }
                    content()
                }
            }
        }
    } else {
        CompositionLocalProvider(
            LocalRichEffectiveBackground provides effectiveBackground,
            LocalContentColor provides contentColor,
            LocalRichHorizontalScrollAncestor provides (hasHorizontalScrollAncestor || createsHorizontalScroll),
        ) {
            Box(
                outer
                    .then(if (drawBorder) style.borderDrawModifier(shape) else Modifier)
                    .padding(style.padding.toPaddingValues(root)),
            ) {
                content()
            }
        }
    }
}

private fun ComputedStyle.resolvedContainerContentColor(
    fallback: Color,
    effectiveBackground: Color?,
    effectiveOpacity: Float,
): Color {
    val requested = color
    val resolved = requested ?: run {
        RichColorResolver.resolveTextColor(
            requested = null,
            fallback = fallback,
            background = effectiveBackground,
            largeOrBold = isLargeOrBoldText(),
        )
    }
    return resolved.copy(alpha = resolved.alpha * effectiveOpacity)
}

private fun ComputedStyle.cssColorFilterModifier(): Modifier {
    val matrix = cssFilter.toAndroidColorMatrix() ?: return Modifier
    return Modifier.drawWithContent {
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        val checkpoint = drawContext.canvas.nativeCanvas.saveLayer(
            RectF(0f, 0f, size.width, size.height),
            paint,
        )
        drawContent()
        drawContext.canvas.nativeCanvas.restoreToCount(checkpoint)
    }
}

private fun ComputedStyle.cssBlurFilterModifier(): Modifier {
    val radius = cssFilter.blurRadius
        ?.takeIf { it > 0.dp && it <= MAX_SAFE_CSS_BLUR_RADIUS }
        ?: return Modifier
    return Modifier.blur(radius)
}

private fun ComputedStyle.cssMaskModifier(): Modifier {
    val brush = maskImage?.let(::richBackgroundImageBrush) ?: return Modifier
    return Modifier.drawWithContent {
        drawContext.canvas.saveLayer(Rect(Offset.Zero, size), androidx.compose.ui.graphics.Paint())
        drawContent()
        drawRect(brush = brush, blendMode = BlendMode.DstIn)
        drawContext.canvas.restore()
    }
}

private fun ComputedStyle.cssClipPathModifier(): Modifier {
    val clip = clipPath ?: return Modifier
    return Modifier.drawWithContent {
        val native = drawContext.canvas.nativeCanvas
        val checkpoint = native.save()
        when (clip) {
            is RichClipPath.Inset -> {
                val left = resolveShapeSizePx(clip.left, size.width)
                val top = resolveShapeSizePx(clip.top, size.height)
                val right = size.width - resolveShapeSizePx(clip.right, size.width)
                val bottom = size.height - resolveShapeSizePx(clip.bottom, size.height)
                val radius = clip.radius.toPx().coerceAtLeast(0f)
                val path = android.graphics.Path().apply {
                    addRoundRect(
                        RectF(left, top, right.coerceAtLeast(left), bottom.coerceAtLeast(top)),
                        radius,
                        radius,
                        android.graphics.Path.Direction.CW,
                    )
                }
                native.clipPath(path)
            }
            is RichClipPath.Circle -> {
                val centerX = resolveShapeSizePx(clip.centerX, size.width)
                val centerY = resolveShapeSizePx(clip.centerY, size.height)
                val radius = resolveShapeSizePx(clip.radius, min(size.width, size.height)).coerceAtLeast(0f)
                val path = android.graphics.Path().apply {
                    addOval(
                        RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius),
                        android.graphics.Path.Direction.CW,
                    )
                }
                native.clipPath(path)
            }
            is RichClipPath.Ellipse -> {
                val centerX = resolveShapeSizePx(clip.centerX, size.width)
                val centerY = resolveShapeSizePx(clip.centerY, size.height)
                val radiusX = resolveShapeSizePx(clip.radiusX, size.width).coerceAtLeast(0f)
                val radiusY = resolveShapeSizePx(clip.radiusY, size.height).coerceAtLeast(0f)
                val path = android.graphics.Path().apply {
                    addOval(
                        RectF(centerX - radiusX, centerY - radiusY, centerX + radiusX, centerY + radiusY),
                        android.graphics.Path.Direction.CW,
                    )
                }
                native.clipPath(path)
            }
        }
        drawContent()
        native.restoreToCount(checkpoint)
    }
}

@Composable
private fun ComputedStyle.nativeAnimationModifier(animationKey: String?): Modifier {
    val native = animation.nativeAnimation ?: return Modifier
    val enabled = LocalRichNativeAnimationsEnabled.current
    val hostId = LocalRichAnimationHostId.current
    val fullKey = remember(hostId, animationKey, native.signature) {
        animationKey?.let { "$hostId:$it:${native.signature}" }
    }
    val shouldPlay = remember(fullKey, enabled) {
        enabled && fullKey != null && RichHtmlAnimationPlaybackRegistry.markForPlayback(fullKey)
    }
    var started by remember(fullKey, shouldPlay) { mutableStateOf(!shouldPlay) }
    if (shouldPlay) {
        LaunchedEffect(fullKey) {
            started = true
        }
    }
    val progress by animateFloatAsState(
        targetValue = if (started) native.iterationCount.toFloat() else 0f,
        animationSpec = tween(
            durationMillis = if (shouldPlay) native.totalDurationMs else 0,
            delayMillis = if (shouldPlay) native.delayMs else 0,
            easing = native.easing.toComposeEasing(),
        ),
        label = "rich-html-native-css-animation",
    )
    val normalizedProgress = native.normalizedProgress(progress)
    val animatedOpacity = native.opacityAt(normalizedProgress).coerceIn(0f, 1f)
    val transform = native.transformAt(progress)
    val density = LocalDensity.current
    return Modifier.graphicsLayer {
        alpha = animatedOpacity
        translationX = with(density) { transform.translateX.toPx() }
        translationY = with(density) { transform.translateY.toPx() }
        scaleX = transform.scaleX
        scaleY = transform.scaleY
        rotationZ = transform.rotateZ
    }
}

private fun RichNativeAnimation.transformAt(progress: Float): RichTransform {
    val (start, end, fraction) = segmentAt(normalizedProgress(progress))
    return RichTransform(
        translateX = lerpDp(start.transform.translateX, end.transform.translateX, fraction),
        translateY = lerpDp(start.transform.translateY, end.transform.translateY, fraction),
        scaleX = lerpCss(start.transform.scaleX, end.transform.scaleX, fraction),
        scaleY = lerpCss(start.transform.scaleY, end.transform.scaleY, fraction),
        rotateZ = lerpCss(start.transform.rotateZ, end.transform.rotateZ, fraction),
        skewX = lerpCss(start.transform.skewX, end.transform.skewX, fraction),
        skewY = lerpCss(start.transform.skewY, end.transform.skewY, fraction),
    )
}

private fun RichNativeAnimation.opacityAt(progress: Float): Float {
    val (start, end, fraction) = segmentAt(progress)
    return lerpCss(start.opacity ?: 1f, end.opacity ?: 1f, fraction)
}

private fun RichNativeAnimation.segmentAt(progress: Float): Triple<RichNativeAnimationStop, RichNativeAnimationStop, Float> {
    val ordered = stops.sortedBy { it.progress }.ifEmpty {
        listOf(RichNativeAnimationStop(0f, fromOpacity, fromTransform), RichNativeAnimationStop(1f, toOpacity, toTransform))
    }
    if (progress <= ordered.first().progress) return Triple(ordered.first(), ordered.first(), 1f)
    if (progress >= ordered.last().progress) return Triple(ordered.last(), ordered.last(), 1f)
    val endIndex = ordered.indexOfFirst { it.progress >= progress }.coerceAtLeast(1)
    val start = ordered[endIndex - 1]
    val end = ordered[endIndex]
    val span = (end.progress - start.progress).coerceAtLeast(0.0001f)
    return Triple(start, end, ((progress - start.progress) / span).coerceIn(0f, 1f))
}

private fun RichNativeAnimation.normalizedProgress(progress: Float): Float {
    if (iterationCount <= 1) return progress.coerceIn(0f, 1f)
    if (progress >= iterationCount.toFloat()) return 1f
    return (progress % 1f).coerceIn(0f, 1f)
}

private fun RichAnimationEasing.toComposeEasing(): Easing {
    return when (this) {
        RichAnimationEasing.Linear -> LinearEasing
        RichAnimationEasing.Ease -> FastOutSlowInEasing
        RichAnimationEasing.EaseIn -> FastOutLinearInEasing
        RichAnimationEasing.EaseOut -> LinearOutSlowInEasing
        RichAnimationEasing.EaseInOut -> FastOutSlowInEasing
        is RichAnimationEasing.CubicBezier -> CubicBezierEasing(x1, y1, x2, y2)
    }
}

private object RichHtmlAnimationPlaybackRegistry {
    private const val MaxEntries = 1024
    private val played = LinkedHashSet<String>()

    @Synchronized
    fun markForPlayback(key: String): Boolean {
        if (played.contains(key)) return false
        played += key
        while (played.size > MaxEntries) {
            val first = played.firstOrNull() ?: break
            played.remove(first)
        }
        return true
    }
}

private fun lerpDp(start: Dp, stop: Dp, fraction: Float): Dp {
    return (start.value + (stop.value - start.value) * fraction.coerceIn(0f, 1f)).dp
}

private fun lerpCss(start: Float, stop: Float, fraction: Float): Float {
    return start + (stop - start) * fraction.coerceIn(0f, 1f)
}

private fun RichCssFilter.toAndroidColorMatrix(): ColorMatrix? {
    if (!hasLowCostColorEffect()) return null
    val matrix = ColorMatrix()
    grayscale?.let { amount ->
        matrix.setSaturation((1f - amount).coerceIn(0f, 1f))
    }
    brightness?.let { factor ->
        val clamped = factor.coerceAtLeast(0f)
        val brightnessMatrix = ColorMatrix(
            floatArrayOf(
                clamped, 0f, 0f, 0f, 0f,
                0f, clamped, 0f, 0f, 0f,
                0f, 0f, clamped, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        matrix.postConcat(brightnessMatrix)
    }
    return matrix
}

private data class InlineRichText(
    val text: AnnotatedString,
    val inlineContent: Map<String, InlineTextContent>,
)

private fun Modifier.textClipBrushMask(brush: Brush?): Modifier {
    if (brush == null) return this
    return drawWithContent {
        drawContext.canvas.saveLayer(Rect(Offset.Zero, size), androidx.compose.ui.graphics.Paint())
        drawContent()
        drawRect(brush = brush, blendMode = BlendMode.SrcIn)
        drawContext.canvas.restore()
    }
}

@Composable
private fun buildInlineRichText(
    source: AnnotatedString,
    mathRuns: List<InlineMathRun>,
    boxRuns: List<InlineRichBoxRun>,
    style: TextStyle,
    onSendInput: (String) -> Unit,
): InlineRichText {
    if (mathRuns.isEmpty() && boxRuns.isEmpty()) return InlineRichText(source, emptyMap())
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val inlineContent = linkedMapOf<String, InlineTextContent>()
    val fontSize = style.fontSize.takeOrElse { 14.sp }
    val text = buildAnnotatedString {
        var cursor = 0
        val runs = buildList {
            mathRuns.forEachIndexed { index, run ->
                add(InlineComposableRun.Math(index, run))
            }
            boxRuns.forEachIndexed { index, run ->
                add(InlineComposableRun.Box(index, run))
            }
        }.sortedBy { it.start }
        runs.forEach { inlineRun ->
            val start = inlineRun.start.coerceIn(0, source.length)
            val end = inlineRun.end.coerceIn(start, source.length)
            if (start > cursor) append(source.subSequence(cursor, start))
            when (inlineRun) {
                is InlineComposableRun.Math -> {
                    val run = inlineRun.run
                    val key = "math-${inlineRun.index}-${run.latex.hashCode()}"
                    val size = with(density) {
                        runCatching { assumeLatexSize(run.latex, fontSize.toPx()) }.getOrNull()
                    }
                    val width = with(density) { (size?.width()?.coerceAtLeast(12) ?: 12).toSp() }
                    val height = with(density) { (size?.height()?.coerceAtLeast(12) ?: 12).toSp() }
                    inlineContent[key] = InlineTextContent(
                        placeholder = Placeholder(width, height, PlaceholderVerticalAlign.TextCenter),
                    ) {
                        MathInline(latex = run.latex, fontSize = fontSize)
                    }
                    appendInlineContent(key, run.latex)
                }
                is InlineComposableRun.Box -> {
                    val run = inlineRun.run
                    val key = "box-${inlineRun.index}-${run.block.blockId}"
                    val widthDp = run.resolvedPlaceholderWidth(style, textMeasurer, density)
                    val heightDp = run.resolvedPlaceholderHeight(style, textMeasurer, density)
                    val width = with(density) { widthDp.toSp() }
                    val height = with(density) { heightDp.toSp() }
                    inlineContent[key] = InlineTextContent(
                        placeholder = Placeholder(width, height, PlaceholderVerticalAlign.TextCenter),
                    ) {
                        Box(
                            modifier = Modifier
                                .requiredWidth(widthDp)
                                .requiredHeight(heightDp)
                                .clipToBounds(),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            RichBlockView(
                                block = run.block,
                                onSendInput = onSendInput,
                                modifier = Modifier,
                                inline = true,
                            )
                        }
                    }
                    appendInlineContent(key, "\uFFFC")
                }
            }
            cursor = end
        }
        if (cursor < source.length) append(source.subSequence(cursor, source.length))
    }
    return InlineRichText(text, inlineContent)
}

private fun InlineRichBoxRun.resolvedPlaceholderWidth(
    textStyle: TextStyle,
    textMeasurer: TextMeasurer,
    density: Density,
): Dp {
    val style = block.style
    val contentWidth = measureInlineTextSize(textStyle, textMeasurer, density)?.width
        ?: (text.sumOf { char -> char.inlinePlaceholderWidthUnits().toDouble() }
            .toFloat() * textStyle.inlineFontSizeSp()).dp
    val measured = contentWidth +
        style.padding.horizontal() +
        style.border.horizontalWidth() +
        style.margin.horizontal() +
        INLINE_BOX_PLACEHOLDER_SAFETY
    val explicitWidth = style.width.dpOrNull()
    val width = explicitWidth
        ?.let { it + style.padding.horizontal() + style.border.horizontalWidth() + style.margin.horizontal() }
        ?: measured
    return width
        .coerceAtLeast(style.minWidth ?: 0.dp)
        .let { value -> style.maxWidth?.let(value::coerceAtMost) ?: value }
}

private fun InlineRichBoxRun.resolvedPlaceholderHeight(
    textStyle: TextStyle,
    textMeasurer: TextMeasurer,
    density: Density,
): Dp {
    val style = block.style
    val measuredTextHeight = measureInlineTextSize(textStyle, textMeasurer, density)?.height
    val font = textStyle.inlineFontSizeSp()
    val line = measuredTextHeight ?: run {
        val rawLine = when (textStyle.lineHeight.type) {
            TextUnitType.Sp -> textStyle.lineHeight.value.dp
            TextUnitType.Em -> (font * textStyle.lineHeight.value).dp
            else -> (font * 1.35f).dp
        }
        rawLine.coerceIn((font * 1.05f).dp, (font * 1.8f).dp)
    }
    val measured = line +
        style.padding.vertical() +
        style.border.verticalWidth() +
        style.margin.vertical() +
        INLINE_BOX_PLACEHOLDER_SAFETY
    val explicitHeight = style.height.dpOrNull()
    val height = explicitHeight
        ?.let { it + style.padding.vertical() + style.border.verticalWidth() + style.margin.vertical() }
        ?: measured
    return height
        .coerceAtLeast(style.minHeight ?: 0.dp)
        .let { value -> style.maxHeight?.let(value::coerceAtMost) ?: value }
}

private val INLINE_BOX_PLACEHOLDER_SAFETY = 2.dp

private data class InlineMeasuredTextSize(
    val width: Dp,
    val height: Dp,
)

private fun InlineRichBoxRun.measureInlineTextSize(
    parentTextStyle: TextStyle,
    textMeasurer: TextMeasurer,
    density: Density,
): InlineMeasuredTextSize? {
    if (text.isBlank()) return null
    val inlineTextStyle = block.primaryInlineTextStyle() ?: return null
    val measured = textMeasurer.measure(
        text = AnnotatedString(text),
        style = parentTextStyle.merge(inlineTextStyle.toTextStyle(Color.Unspecified)),
        maxLines = 1,
        softWrap = false,
    )
    return with(density) {
        InlineMeasuredTextSize(
            width = measured.size.width.toDp(),
            height = measured.size.height.toDp(),
        )
    }
}

private fun RichBlock.primaryInlineTextStyle(): ComputedStyle? = when (this) {
    is RichTextBlock -> style
    is RichButtonBlock -> style
    is RichContainerBlock -> {
        children.singleOrNull()
            ?.let { it as? RichTextBlock }
            ?.style
    }
    else -> null
}

private fun TextStyle.inlineFontSizeSp(): Float {
    return when (fontSize.type) {
        TextUnitType.Sp -> fontSize.value
        TextUnitType.Em -> 14f * fontSize.value
        else -> 14f
    }.coerceAtLeast(8f)
}

private fun Char.inlinePlaceholderWidthUnits(): Float = when {
    isWhitespace() -> 0.35f
    code in 0x2E80..0x9FFF -> 1.05f
    code >= 0x1F000 -> 1.35f
    isUpperCase() -> 0.72f
    isDigit() -> 0.62f
    else -> 0.60f
}

private fun RichSpacing.horizontal(): Dp = left + right

private fun RichSpacing.vertical(): Dp = top + bottom

private fun RichBorder.horizontalWidth(): Dp = left.width + right.width

private fun RichBorder.verticalWidth(): Dp = top.width + bottom.width

private sealed interface InlineComposableRun {
    val index: Int
    val start: Int
    val end: Int

    data class Math(
        override val index: Int,
        val run: InlineMathRun,
    ) : InlineComposableRun {
        override val start: Int = run.start
        override val end: Int = run.end
    }

    data class Box(
        override val index: Int,
        val run: InlineRichBoxRun,
    ) : InlineComposableRun {
        override val start: Int = run.start
        override val end: Int = run.end
    }
}

private fun ComputedStyle.toTextStyle(textColor: Color? = null): TextStyle {
    val effectiveOpacity = effectiveOpacity()
    return TextStyle(
        color = textColor
            ?: color?.copy(alpha = color.alpha * effectiveOpacity)
            ?: Color.Unspecified,
        fontSize = fontSize,
        fontWeight = fontWeight,
        fontStyle = fontStyle,
        fontFamily = fontFamily,
        lineHeight = lineHeight,
        textAlign = textAlign,
        letterSpacing = letterSpacing,
        shadow = textShadow?.toShadow(),
    )
}

private fun ColorScheme.toRichRenderColorDefaults(): RichRenderColorDefaults {
    return RichRenderColorDefaults(
        text = onSurface,
        weakText = onSurfaceVariant,
        surface = surface,
        surfaceLow = surfaceContainerLow,
        outline = outlineVariant,
        codeBackground = surfaceContainerHighest.copy(alpha = 0.72f),
        quoteBackground = surfaceContainerLow.copy(alpha = 0.72f),
        tableHeaderBackground = surfaceContainerHigh,
        tableBodyBackground = Color.Transparent,
        tableFooterBackground = surfaceContainerLow,
        buttonBackground = primary,
        buttonText = onPrimary,
        accent = primary,
    )
}

private fun ComputedStyle.isLargeOrBoldText(): Boolean {
    return (fontWeight?.weight ?: FontWeight.Normal.weight) >= FontWeight.SemiBold.weight ||
        (fontSize.type == TextUnitType.Sp && fontSize.value >= 18f)
}

private fun ComputedStyle.baseModifier(root: Boolean): Modifier {
    var modifier: Modifier = Modifier
    if (root || (display.isBlockFilling() && width == RichSize.Auto && maxWidth == null)) {
        modifier = modifier.fillMaxWidth()
    }
    width.dpOrNull()?.let { modifier = modifier.width(it) }
    (width as? RichSize.Fraction)?.let { modifier = modifier.fillMaxWidth(it.value) }
    height.dpOrNull()?.let { modifier = modifier.height(it) }
    minHeight?.let { modifier = modifier.heightIn(min = it) }
    maxHeight?.let { modifier = modifier.heightIn(max = it) }
    maxWidth?.let { modifier = modifier.widthIn(max = it) }
    minWidth?.let { modifier = modifier.widthIn(min = it) }
    if (!isPositionedOverlay() && (offset.left != null || offset.top != null || transform.translateX != 0.dp || transform.translateY != 0.dp)) {
        modifier = modifier.offset(
            x = (offset.left ?: 0.dp) + transform.translateX,
            y = (offset.top ?: 0.dp) + transform.translateY,
        )
    }
    val effectiveOpacity = effectiveOpacity()
    if (transform != RichTransform.None || effectiveOpacity < 1f) {
        modifier = modifier.graphicsLayer {
            alpha = effectiveOpacity
            scaleX = transform.scaleX
            scaleY = transform.scaleY
            rotationZ = transform.rotateZ
        }
    }
    if (zIndex != 0f) modifier = modifier.zIndex(zIndex)
    return modifier
}

private fun ComputedStyle.marginModifier(): Modifier {
    return if (margin == RichSpacing.Zero) Modifier else Modifier.padding(margin.toPaddingValues(root = false))
}

private fun ComputedStyle.isPositionedOverlay(): Boolean {
    return position == RichPosition.Absolute || position == RichPosition.Fixed || position == RichPosition.Sticky
}

private fun BoxScope.positionedChildModifier(style: ComputedStyle): Modifier {
    val align = when {
        style.offset.right != null && style.offset.bottom != null -> Alignment.BottomEnd
        style.offset.right != null -> Alignment.TopEnd
        style.offset.bottom != null -> Alignment.BottomStart
        else -> Alignment.TopStart
    }
    val x = (style.offset.left ?: style.offset.right?.let { -it } ?: 0.dp) + style.transform.translateX
    val y = (style.offset.top ?: style.offset.bottom?.let { -it } ?: 0.dp) + style.transform.translateY
    return Modifier
        .align(align)
        .offset(x = x, y = y)
        .zIndex(style.zIndex)
}

private fun blockFlowModifier(style: ComputedStyle): Modifier {
    var modifier: Modifier = Modifier
    (style.width as? RichSize.Fraction)?.let {
        modifier = modifier.fillMaxWidth(it.value)
    }
    style.width.dpOrNull()?.let {
        modifier = modifier.width(it)
    }
    style.maxWidth?.let {
        modifier = modifier.widthIn(max = it)
    }
    style.minWidth?.let {
        modifier = modifier.widthIn(min = it)
    }
    return modifier
}

private fun Modifier.richWidthConstraints(style: ComputedStyle): Modifier {
    var modifier = this
    (style.width as? RichSize.Fraction)?.let {
        modifier = modifier.fillMaxWidth(it.value)
    }
    style.width.dpOrNull()?.let {
        modifier = modifier.width(it)
    }
    style.maxWidth?.let {
        modifier = modifier.widthIn(max = it)
    }
    style.minWidth?.let {
        modifier = modifier.widthIn(min = it)
    }
    return modifier
}

private fun Modifier.richFlexItemConstraints(style: ComputedStyle, parentStyle: ComputedStyle): Modifier {
    var modifier = this
    val basisControlsMainAxis = style.flexBasis != RichSize.Auto || style.flexGrow > 0f || style.flexShrink != 1f
    val horizontalMainAxis = parentStyle.flexDirection.isRowAxis()
    val applyWidth = !horizontalMainAxis || !basisControlsMainAxis
    if (applyWidth) {
        modifier = modifier.richWidthConstraints(style)
    } else {
        style.maxWidth?.let { modifier = modifier.widthIn(max = it) }
        style.minWidth?.let { modifier = modifier.widthIn(min = it) }
    }
    if (parentStyle.flexDirection.isColumnAxis()) {
        style.height.dpOrNull()?.let { modifier = modifier.height(it) }
        style.minHeight?.let { modifier = modifier.heightIn(min = it) }
        style.maxHeight?.let { modifier = modifier.heightIn(max = it) }
    }
    return modifier
}

@OptIn(ExperimentalFlexBoxApi::class)
internal fun RichFlexDirection.toFlexDirection(): FlexDirection = when (this) {
    RichFlexDirection.Row -> FlexDirection.Row
    RichFlexDirection.RowReverse -> FlexDirection.RowReverse
    RichFlexDirection.Column -> FlexDirection.Column
    RichFlexDirection.ColumnReverse -> FlexDirection.ColumnReverse
}

private fun RichFlexDirection.isRowAxis(): Boolean {
    return this == RichFlexDirection.Row || this == RichFlexDirection.RowReverse
}

private fun RichFlexDirection.isColumnAxis(): Boolean {
    return this == RichFlexDirection.Column || this == RichFlexDirection.ColumnReverse
}

internal fun ComputedStyle.flexTextAlignOverride(child: RichBlock, childCount: Int): TextAlign? {
    if (childCount != 1 || justifyContent != RichJustify.Center) return null
    val text = child as? RichTextBlock ?: return null
    if (text.style.textAlign != TextAlign.Unspecified) return null
    if (text.style.width != RichSize.Auto || text.style.flexBasis != RichSize.Auto || text.style.flexGrow > 0f) return null
    return TextAlign.Center
}

private fun TextStyle.withFlexTextAlignOverride(style: ComputedStyle, override: TextAlign?): TextStyle {
    if (override == null || style.textAlign != TextAlign.Unspecified || textAlign != TextAlign.Unspecified) return this
    return copy(textAlign = override)
}

@OptIn(ExperimentalFlexBoxApi::class)
private fun ComputedStyle.toFlexDirection(): FlexDirection = flexDirection.toFlexDirection()

@OptIn(ExperimentalFlexBoxApi::class)
internal fun ComputedStyle.toFlexWrap(): FlexWrap = when (flexWrap) {
    RichFlexWrap.NoWrap -> FlexWrap.NoWrap
    RichFlexWrap.Wrap -> FlexWrap.Wrap
    RichFlexWrap.WrapReverse -> FlexWrap.WrapReverse
}

@OptIn(ExperimentalFlexBoxApi::class)
internal fun ComputedStyle.toFlexJustifyContent(): FlexJustifyContent = when (justifyContent) {
    RichJustify.Center -> FlexJustifyContent.Center
    RichJustify.End -> FlexJustifyContent.End
    RichJustify.SpaceBetween -> FlexJustifyContent.SpaceBetween
    RichJustify.SpaceAround -> FlexJustifyContent.SpaceAround
    RichJustify.SpaceEvenly -> FlexJustifyContent.SpaceEvenly
    RichJustify.Start -> FlexJustifyContent.Start
}

@OptIn(ExperimentalFlexBoxApi::class)
internal fun ComputedStyle.toFlexAlignItems(): FlexAlignItems = when (alignItems) {
    RichAlign.Center -> FlexAlignItems.Center
    RichAlign.End -> FlexAlignItems.End
    RichAlign.Stretch -> FlexAlignItems.Stretch
    RichAlign.Baseline -> FlexAlignItems.Baseline
    RichAlign.Start -> FlexAlignItems.Start
}

@OptIn(ExperimentalFlexBoxApi::class)
internal fun ComputedStyle.toFlexAlignContent(): FlexAlignContent = when (alignContent) {
    RichAlignContent.Center -> FlexAlignContent.Center
    RichAlignContent.End -> FlexAlignContent.End
    RichAlignContent.SpaceBetween -> FlexAlignContent.SpaceBetween
    RichAlignContent.SpaceAround -> FlexAlignContent.SpaceAround
    RichAlignContent.SpaceEvenly -> FlexAlignContent.SpaceAround
    RichAlignContent.Stretch -> FlexAlignContent.Stretch
    RichAlignContent.Start -> FlexAlignContent.Start
}

@OptIn(ExperimentalFlexBoxApi::class)
internal fun RichAlign.toFlexAlignSelf(): FlexAlignSelf = when (this) {
    RichAlign.Center -> FlexAlignSelf.Center
    RichAlign.End -> FlexAlignSelf.End
    RichAlign.Stretch -> FlexAlignSelf.Stretch
    RichAlign.Baseline -> FlexAlignSelf.Baseline
    RichAlign.Start -> FlexAlignSelf.Start
}

@OptIn(ExperimentalFlexBoxApi::class)
private fun ComputedStyle.toEffectiveFlexBasis(): FlexBasis? {
    return when {
        flexBasis != RichSize.Auto -> flexBasis.toFlexBasis()
        width != RichSize.Auto -> width.toFlexBasis()
        else -> null
    }
}

@OptIn(ExperimentalFlexBoxApi::class)
private fun RichSize.toFlexBasis(): FlexBasis? = when (this) {
    is RichSize.DpSize -> FlexBasis.Dp(value)
    is RichSize.Fraction -> FlexBasis.Percent(value)
    RichSize.Auto -> null
}

private data class RichGridCell(
    val row: Int,
    val column: Int,
)

private data class RichGridMeasuredItem(
    val placeable: Placeable,
    val row: Int,
    val column: Int,
    val rowSpan: Int,
    val columnSpan: Int,
)

private fun Modifier.richGridItemConstraints(style: ComputedStyle, parentStyle: ComputedStyle): Modifier {
    var modifier = this.richWidthConstraints(style)
    style.height.dpOrNull()?.let { modifier = modifier.height(it) }
    style.minHeight?.let { modifier = modifier.heightIn(min = it) }
    style.maxHeight?.let { modifier = modifier.heightIn(max = it) }
    if (style.alignSelf == RichAlign.Stretch || parentStyle.alignItems == RichAlign.Stretch) {
        modifier = modifier.fillMaxWidth()
    }
    return modifier
}

private fun MeasureScope.gridColumnCount(
    style: ComputedStyle,
    layoutWidth: Int,
    columnGapPx: Int,
    childCount: Int,
): Int {
    return when (val columns = style.gridColumns) {
        is RichGridColumns.Count -> columns.count
        is RichGridColumns.AutoFit -> {
            val minColumnWidth = columns.minColumnWidth.roundToPx().coerceAtLeast(1)
            ((layoutWidth + columnGapPx) / (minColumnWidth + columnGapPx).coerceAtLeast(1))
                .coerceAtLeast(1)
        }
        RichGridColumns.Auto -> {
            val minColumnWidth = 96.dp.roundToPx().coerceAtLeast(1)
            ((layoutWidth + columnGapPx) / (minColumnWidth + columnGapPx).coerceAtLeast(1))
                .coerceIn(1, childCount.coerceAtLeast(1))
        }
    }.coerceIn(1, 8)
}

private fun distributeGridTrackWidths(layoutWidth: Int, columnCount: Int, columnGapPx: Int): List<Int> {
    val available = (layoutWidth - columnGapPx * (columnCount - 1)).coerceAtLeast(0)
    val base = available / columnCount
    var remainder = available % columnCount
    return List(columnCount) {
        base + if (remainder-- > 0) 1 else 0
    }
}

private fun List<Int>.spannedSize(start: Int, span: Int, gapPx: Int): Int {
    return (start until (start + span).coerceAtMost(size)).sumOf { this[it] } +
        gapPx * (span - 1).coerceAtLeast(0)
}

private fun List<Int>.runningOffsets(gapPx: Int): List<Int> {
    var offset = 0
    return map { size ->
        val current = offset
        offset += size + gapPx
        current
    }
}

private fun Int.constrainDimension(min: Int, max: Int): Int {
    return if (max == Constraints.Infinity) {
        coerceAtLeast(min)
    } else {
        coerceIn(min, max)
    }
}

private fun findRichGridCell(
    occupied: Set<Long>,
    columnCount: Int,
    rowSpan: Int,
    columnSpan: Int,
    columnStart: Int?,
    rowStart: Int?,
): RichGridCell {
    val explicitColumn = columnStart?.minus(1)?.coerceIn(0, columnCount - columnSpan)
    val explicitRow = rowStart?.minus(1)?.coerceAtLeast(0)
    if (explicitColumn != null && explicitRow != null) {
        return RichGridCell(explicitRow, explicitColumn)
    }
    if (explicitColumn != null) {
        var row = 0
        while (true) {
            if (richGridFits(occupied, row, explicitColumn, rowSpan, columnSpan)) {
                return RichGridCell(row, explicitColumn)
            }
            row += 1
        }
    }
    if (explicitRow != null) {
        for (column in 0..(columnCount - columnSpan)) {
            if (richGridFits(occupied, explicitRow, column, rowSpan, columnSpan)) {
                return RichGridCell(explicitRow, column)
            }
        }
    }
    var row = 0
    while (true) {
        for (column in 0..(columnCount - columnSpan)) {
            if (richGridFits(occupied, row, column, rowSpan, columnSpan)) {
                return RichGridCell(row, column)
            }
        }
        row += 1
    }
}

private fun richGridFits(
    occupied: Set<Long>,
    row: Int,
    column: Int,
    rowSpan: Int,
    columnSpan: Int,
): Boolean {
    for (r in row until row + rowSpan) {
        for (c in column until column + columnSpan) {
            if (richGridCellKey(r, c) in occupied) return false
        }
    }
    return true
}

private fun markRichGridOccupied(
    occupied: MutableSet<Long>,
    row: Int,
    column: Int,
    rowSpan: Int,
    columnSpan: Int,
) {
    for (r in row until row + rowSpan) {
        for (c in column until column + columnSpan) {
            occupied += richGridCellKey(r, c)
        }
    }
}

private fun richGridCellKey(row: Int, column: Int): Long {
    return (row.toLong() shl 32) xor column.toLong()
}

private fun ComputedStyle.flexShrinkWidths(children: List<RichBlock>, availableWidth: Dp?): Map<String, Dp> {
    if (availableWidth == null || !display.isFlexContainer() || !flexDirection.isRowAxis() || flexWrap != RichFlexWrap.NoWrap) {
        return emptyMap()
    }
    val basisItems = children.mapNotNull { child ->
        val basis = child.style.flexBasis.dpOrNull() ?: child.style.width.dpOrNull()
        basis?.takeIf { it > 0.dp }?.let { child to it }
    }
    if (basisItems.size < 2) return emptyMap()
    val totalBasis = basisItems.fold(0.dp) { acc, (_, basis) -> acc + basis }
    val totalGap = columnGap * (basisItems.size - 1).coerceAtLeast(0)
    val overflow = totalBasis + totalGap - availableWidth
    if (overflow <= 0.dp) return emptyMap()
    val totalShrinkFactor = basisItems.sumOf { (child, basis) ->
        (child.style.flexShrink.coerceAtLeast(0f) * basis.value).toDouble()
    }.toFloat()
    if (totalShrinkFactor <= 0f) return emptyMap()
    return basisItems.associate { (child, basis) ->
        val factor = child.style.flexShrink.coerceAtLeast(0f) * basis.value
        val shrink = overflow * (factor / totalShrinkFactor)
        child.blockId to (basis - shrink).coerceAtLeast(24.dp)
    }
}

private fun ComputedStyle.alignContentStretchChildModifier(): Modifier {
    return if (alignContent == RichAlignContent.Stretch && flexWrap != RichFlexWrap.NoWrap) {
        Modifier.heightIn(min = 40.dp)
    } else {
        Modifier
    }
}

private fun RowScope.rowFlexItemModifier(
    style: ComputedStyle,
    parentStyle: ComputedStyle,
    shrinkWidth: Dp? = null,
): Modifier {
    var modifier: Modifier = Modifier
    shrinkWidth?.let {
        modifier = modifier.width(it)
    } ?: run {
        modifier = modifier.richWidthConstraints(style)
        style.flexBasis.dpOrNull()?.let {
            modifier = if (parentStyle.flexDirection.isColumnAxis()) modifier.height(it) else modifier.width(it)
        }
    }
    style.alignSelf?.let {
        modifier = when (it) {
            RichAlign.Stretch -> modifier.fillMaxHeight()
            RichAlign.Baseline -> modifier.align(Alignment.Top)
            else -> modifier.align(it.toRowAlignment())
        }
    }
    if (shrinkWidth == null && parentStyle.display.isFlexContainer() && style.flexGrow > 0f) {
        modifier = modifier.weight(style.flexGrow, fill = style.flexBasis == RichSize.Auto)
    }
    return modifier
}

private fun ColumnScope.columnFlexItemModifier(style: ComputedStyle, parentStyle: ComputedStyle): Modifier {
    var modifier: Modifier = Modifier
    modifier = modifier.richWidthConstraints(style)
    style.flexBasis.dpOrNull()?.let {
        modifier = if (parentStyle.flexDirection.isColumnAxis()) modifier.height(it) else modifier.width(it)
    }
    style.alignSelf?.let {
        modifier = when (it) {
            RichAlign.Stretch -> modifier.fillMaxWidth()
            RichAlign.Baseline -> modifier.align(Alignment.Start)
            else -> modifier.align(it.toColumnAlignment())
        }
    }
    if (parentStyle.display.isFlexContainer() && style.flexGrow > 0f) {
        modifier = modifier.weight(style.flexGrow, fill = style.flexBasis == RichSize.Auto)
    }
    return modifier
}

private fun ComputedStyle.flowFlexItemModifier(parentStyle: ComputedStyle): Modifier {
    var modifier: Modifier = Modifier
    flexBasis.dpOrNull()?.let {
        modifier = if (parentStyle.flexDirection.isColumnAxis()) modifier.height(it) else modifier.width(it)
    }
    if (alignSelf == RichAlign.Stretch) {
        modifier = if (parentStyle.flexDirection.isColumnAxis()) modifier.fillMaxWidth() else modifier.fillMaxHeight()
    }
    return modifier
}

@Composable
private fun ComputedStyle.repeatedBackgroundModifier(): Modifier {
    val url = backgroundUrl ?: return Modifier
    val style = this
    val mediaRequest = remember(url) {
        RichMediaRequest.fromSource(url, kind = RichMediaKind.BackgroundImage)
    }
    val safeUrl = RichMediaLoader.safeData(mediaRequest) ?: return Modifier
    val painter = rememberAsyncImagePainter(safeUrl)
    return Modifier.drawWithContent {
        val paintArea = backgroundAreaRect(style, style.backgroundOrigin)
        val clipArea = backgroundAreaRect(style, style.backgroundClip)
        val tileSize = backgroundTileSize(style, painter.intrinsicSize, paintArea.size)
        val origin = backgroundTileOrigin(style.backgroundPosition, containerSize = paintArea.size, tileSize = tileSize)
        val repeatX = backgroundRepeat == RichBackgroundRepeat.Repeat ||
            backgroundRepeat == RichBackgroundRepeat.RepeatX ||
            backgroundRepeat == RichBackgroundRepeat.Round ||
            backgroundRepeat == RichBackgroundRepeat.Space
        val repeatY = backgroundRepeat == RichBackgroundRepeat.Repeat ||
            backgroundRepeat == RichBackgroundRepeat.RepeatY ||
            backgroundRepeat == RichBackgroundRepeat.Round ||
            backgroundRepeat == RichBackgroundRepeat.Space
        val xPositions = backgroundTilePositions(
            origin = origin.x,
            container = paintArea.width,
            tile = tileSize.width,
            repeat = repeatX,
            spaced = backgroundRepeat == RichBackgroundRepeat.Space,
        )
        val yPositions = backgroundTilePositions(
            origin = origin.y,
            container = paintArea.height,
            tile = tileSize.height,
            repeat = repeatY,
            spaced = backgroundRepeat == RichBackgroundRepeat.Space,
        )
        clipRect(
            left = clipArea.left,
            top = clipArea.top,
            right = clipArea.right,
            bottom = clipArea.bottom,
        ) {
            yPositions.forEach { y ->
                xPositions.forEach { x ->
                    translate(left = paintArea.left + x, top = paintArea.top + y) {
                        with(painter) {
                            draw(size = tileSize, alpha = 0.45f)
                        }
                    }
                }
            }
        }
        drawContent()
    }
}

@Composable
private fun ComputedStyle.backgroundLayersModifier(
    layers: List<RichBackgroundLayer>,
    shape: RoundedCornerShape,
    baseColor: Color?,
): Modifier {
    if (layers.isEmpty()) return Modifier
    val safeLayers = layers.take(MAX_SAFE_BACKGROUND_LAYERS)
    val painters = safeLayers.map { layer ->
        layer.url?.let { url ->
            val request = RichMediaRequest.fromSource(url, kind = RichMediaKind.BackgroundImage)
            RichMediaLoader.safeData(request)?.let { safeUrl -> rememberAsyncImagePainter(safeUrl) }
        }
    }
    val style = this
    return Modifier
        .clip(shape)
        .drawWithContent {
            baseColor?.let { drawRect(color = it) }
            safeLayers.indices.reversed().forEach { index ->
                drawBackgroundLayer(style, safeLayers[index], painters[index])
            }
            drawContent()
        }
}

private fun DrawScope.drawBackgroundLayer(
    style: ComputedStyle,
    layer: RichBackgroundLayer,
    painter: Painter?,
) {
    val paintArea = backgroundAreaRect(style, layer.origin)
    val clipArea = backgroundAreaRect(style, layer.clip)
    clipRect(
        left = clipArea.left,
        top = clipArea.top,
        right = clipArea.right,
        bottom = clipArea.bottom,
    ) {
        layer.image?.let { image ->
            translate(left = paintArea.left, top = paintArea.top) {
                drawRect(brush = richBackgroundImageBrush(image, paintArea.size), size = paintArea.size)
            }
        }
        if (painter != null) {
            val tileSize = backgroundTileSize(layer.size, layer.repeat, painter.intrinsicSize, paintArea.size)
            val origin = backgroundTileOrigin(layer.position, containerSize = paintArea.size, tileSize = tileSize)
            val repeatX = layer.repeat == RichBackgroundRepeat.Repeat ||
                layer.repeat == RichBackgroundRepeat.RepeatX ||
                layer.repeat == RichBackgroundRepeat.Round ||
                layer.repeat == RichBackgroundRepeat.Space
            val repeatY = layer.repeat == RichBackgroundRepeat.Repeat ||
                layer.repeat == RichBackgroundRepeat.RepeatY ||
                layer.repeat == RichBackgroundRepeat.Round ||
                layer.repeat == RichBackgroundRepeat.Space
            val xPositions = backgroundTilePositions(
                origin = origin.x,
                container = paintArea.width,
                tile = tileSize.width,
                repeat = repeatX,
                spaced = layer.repeat == RichBackgroundRepeat.Space,
            )
            val yPositions = backgroundTilePositions(
                origin = origin.y,
                container = paintArea.height,
                tile = tileSize.height,
                repeat = repeatY,
                spaced = layer.repeat == RichBackgroundRepeat.Space,
            )
            yPositions.forEach { y ->
                xPositions.forEach { x ->
                    translate(left = paintArea.left + x, top = paintArea.top + y) {
                        with(painter) {
                            draw(size = tileSize, alpha = 1f)
                        }
                    }
                }
            }
        }
    }
}

private const val MAX_BACKGROUND_TILES = 96
private const val MAX_SAFE_BACKGROUND_LAYERS = 4

private data class BackgroundArea(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(1f)
    val height: Float get() = (bottom - top).coerceAtLeast(1f)
    val size: Size get() = Size(width, height)
}

private fun DrawScope.backgroundAreaRect(style: ComputedStyle, box: RichBackgroundBox): BackgroundArea {
    val borderLeft = style.border.left.width.toPx()
    val borderTop = style.border.top.width.toPx()
    val borderRight = style.border.right.width.toPx()
    val borderBottom = style.border.bottom.width.toPx()
    val paddingLeft = style.padding.left.toPx()
    val paddingTop = style.padding.top.toPx()
    val paddingRight = style.padding.right.toPx()
    val paddingBottom = style.padding.bottom.toPx()
    return when (box) {
        RichBackgroundBox.BorderBox -> BackgroundArea(0f, 0f, size.width, size.height)
        RichBackgroundBox.PaddingBox -> BackgroundArea(
            left = borderLeft,
            top = borderTop,
            right = size.width - borderRight,
            bottom = size.height - borderBottom,
        )
        RichBackgroundBox.ContentBox -> BackgroundArea(
            left = borderLeft + paddingLeft,
            top = borderTop + paddingTop,
            right = size.width - borderRight - paddingRight,
            bottom = size.height - borderBottom - paddingBottom,
        )
        RichBackgroundBox.Text -> BackgroundArea(0f, 0f, size.width, size.height)
    }
}

private fun ComputedStyle.backgroundAreaPadding(box: RichBackgroundBox): RichSpacing {
    return when (box) {
        RichBackgroundBox.BorderBox -> RichSpacing.Zero
        RichBackgroundBox.PaddingBox -> RichSpacing(
            top = border.top.width,
            right = border.right.width,
            bottom = border.bottom.width,
            left = border.left.width,
        )
        RichBackgroundBox.ContentBox -> RichSpacing(
            top = border.top.width + padding.top,
            right = border.right.width + padding.right,
            bottom = border.bottom.width + padding.bottom,
            left = border.left.width + padding.left,
        )
        RichBackgroundBox.Text -> RichSpacing.Zero
    }
}

private fun ComputedStyle.backgroundBrushModifier(brush: Brush, shape: RoundedCornerShape): Modifier {
    return Modifier
        .clip(shape)
        .drawBehind {
            val paintArea = backgroundAreaRect(this@backgroundBrushModifier, backgroundOrigin)
            val clipArea = backgroundAreaRect(this@backgroundBrushModifier, backgroundClip)
            val areaBrush = backgroundImage?.let { richBackgroundImageBrush(it, paintArea.size) } ?: brush
            clipRect(
                left = clipArea.left,
                top = clipArea.top,
                right = clipArea.right,
                bottom = clipArea.bottom,
            ) {
                translate(left = paintArea.left, top = paintArea.top) {
                    drawRect(brush = areaBrush, size = paintArea.size)
                }
            }
        }
}

private fun ComputedStyle.backdropApproximationModifier(
    shape: RoundedCornerShape,
    defaults: RichRenderColorDefaults,
): Modifier {
    val tint = backgroundColor ?: defaults.surface.copy(alpha = 0.32f)
    val brightened = backdropFilter.brightness?.let { factor ->
        if (factor >= 1f) Color.White.copy(alpha = ((factor - 1f) * 0.10f).coerceIn(0.02f, 0.10f)) else Color.Black.copy(alpha = ((1f - factor) * 0.08f).coerceIn(0.02f, 0.08f))
    }
    return Modifier
        .background(tint.copy(alpha = tint.alpha.coerceAtLeast(0.10f)), shape)
        .drawBehind {
            brightened?.let {
                drawRoundRect(
                    color = it,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                        shape.topStart.toPx(size, this),
                        shape.topStart.toPx(size, this),
                    ),
                )
            }
            drawRoundRect(
                color = defaults.outline.copy(alpha = 0.18f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                    shape.topStart.toPx(size, this),
                    shape.topStart.toPx(size, this),
                ),
            )
        }
}

private fun DrawScope.backgroundTileSize(style: ComputedStyle, intrinsic: Size, containerSize: Size): Size {
    return backgroundTileSize(style.backgroundSize, style.backgroundRepeat, intrinsic, containerSize)
}

private fun DrawScope.backgroundTileSize(
    backgroundSize: RichBackgroundSize,
    backgroundRepeat: RichBackgroundRepeat,
    intrinsic: Size,
    containerSize: Size,
): Size {
    val fallbackWidth = 96.dp.toPx()
    val fallbackHeight = 96.dp.toPx()
    val sourceWidth = intrinsic.width.takeIf { it.isFinite() && it > 0f } ?: fallbackWidth
    val sourceHeight = intrinsic.height.takeIf { it.isFinite() && it > 0f } ?: fallbackHeight
    if (backgroundRepeat == RichBackgroundRepeat.Round) {
        val columns = max(1, (containerSize.width / sourceWidth).roundToInt())
        val rows = max(1, (containerSize.height / sourceHeight).roundToInt())
        return Size(containerSize.width / columns, containerSize.height / rows)
    }
    return when (val bgSize = backgroundSize) {
        RichBackgroundSize.Auto -> Size(sourceWidth, sourceHeight)
        RichBackgroundSize.Contain -> {
            val scale = min(containerSize.width / sourceWidth, containerSize.height / sourceHeight).takeIf { it.isFinite() && it > 0f } ?: 1f
            Size(sourceWidth * scale, sourceHeight * scale)
        }
        RichBackgroundSize.Cover -> {
            val scale = max(containerSize.width / sourceWidth, containerSize.height / sourceHeight).takeIf { it.isFinite() && it > 0f } ?: 1f
            Size(sourceWidth * scale, sourceHeight * scale)
        }
        is RichBackgroundSize.Explicit -> {
            val explicitWidth = richSizeToPx(bgSize.width, containerSize.width)
            val explicitHeight = richSizeToPx(bgSize.height, containerSize.height)
            when {
                explicitWidth != null && explicitHeight != null -> Size(explicitWidth, explicitHeight)
                explicitWidth != null -> Size(explicitWidth, sourceHeight * (explicitWidth / sourceWidth))
                explicitHeight != null -> Size(sourceWidth * (explicitHeight / sourceHeight), explicitHeight)
                else -> Size(sourceWidth, sourceHeight)
            }
        }
    }.let { Size(it.width.coerceAtLeast(1f), it.height.coerceAtLeast(1f)) }
}

private fun backgroundTilePositions(
    origin: Float,
    container: Float,
    tile: Float,
    repeat: Boolean,
    spaced: Boolean,
): List<Float> {
    if (!repeat || !tile.isFinite() || tile <= 0f) return listOf(origin)
    if (spaced) {
        val count = (container / tile).toInt().coerceIn(1, MAX_BACKGROUND_TILES)
        if (count <= 1) return listOf(origin.coerceIn(0f, (container - tile).coerceAtLeast(0f)))
        val gap = ((container - tile * count) / (count - 1)).coerceAtLeast(0f)
        return List(count) { index -> index * (tile + gap) }
    }
    val positions = mutableListOf<Float>()
    var current = origin.repeatedStart(tile)
    var count = 0
    while (current <= container && count < MAX_BACKGROUND_TILES) {
        positions += current
        current += tile
        count++
    }
    return positions.ifEmpty { listOf(origin) }
}

private fun DrawScope.richSizeToPx(size: RichSize, containerPx: Float): Float? = when (size) {
    is RichSize.DpSize -> size.value.toPx()
    is RichSize.Fraction -> containerPx * size.value
    RichSize.Auto -> null
}

private fun DrawScope.resolveShapeSizePx(size: RichSize, axisPx: Float): Float = when (size) {
    is RichSize.DpSize -> size.value.toPx()
    is RichSize.Fraction -> axisPx * size.value
    RichSize.Auto -> axisPx
}

private fun DrawScope.backgroundTileOrigin(
    position: RichBackgroundPosition,
    containerSize: Size,
    tileSize: Size,
): Offset {
    return Offset(
        x = (containerSize.width - tileSize.width) * position.xFraction + position.xOffset.toPx(),
        y = (containerSize.height - tileSize.height) * position.yFraction + position.yOffset.toPx(),
    )
}

private fun Float.repeatedStart(step: Float): Float {
    if (!step.isFinite() || step <= 0f) return this
    var start = this
    while (start > 0f) start -= step
    while (start + step < 0f) start += step
    return start
}

private fun ComputedStyle.shape(default: Dp): RoundedCornerShape {
    val radius = if (borderRadius == RichCornerRadius.Zero) RichCornerRadius.all(default) else borderRadius
    return RoundedCornerShape(
        topStart = radius.topStart,
        topEnd = radius.topEnd,
        bottomEnd = radius.bottomEnd,
        bottomStart = radius.bottomStart,
    )
}

private fun ComputedStyle.surfaceBorderStroke(): BorderStroke? {
    val sides = border.visibleSides()
    if (sides.size != 4) return null
    val first = sides.first()
    return if (first.style == RichBorderStyle.Solid && sides.all { it == first }) {
        BorderStroke(first.width, first.color)
    } else {
        null
    }
}

private fun ComputedStyle.hasCustomDrawBorder(): Boolean {
    return border.visibleSides().isNotEmpty() && surfaceBorderStroke() == null
}

private fun RichBorder.visibleSides(): List<RichBorderSide> {
    return listOf(top, right, bottom, left).filter {
        it.style != RichBorderStyle.None && it.width > 0.dp && it.color.alpha > 0f
    }
}

private fun ComputedStyle.richShadowModifier(shape: RoundedCornerShape): Modifier {
    if (shadows.isEmpty()) return Modifier
    val inlineBox = display.isInlineBox()
    return Modifier.drawBehind {
        shadows.filterNot { it.inset }.forEach { shadow ->
            val spreadPx = if (inlineBox) shadow.spread.toPx().coerceIn(0f, 2.dp.toPx()) else shadow.spread.toPx()
            val blurPx = if (inlineBox) shadow.blurRadius.toPx().coerceAtMost(10.dp.toPx()) else shadow.blurRadius.toPx()
            val shadowColor = if (inlineBox) {
                shadow.color.copy(alpha = shadow.color.alpha * 0.35f)
            } else {
                shadow.color
            }
            val left = shadow.offsetX.toPx() - spreadPx
            val top = shadow.offsetY.toPx() - spreadPx
            val right = size.width + shadow.offsetX.toPx() + spreadPx
            val bottom = size.height + shadow.offsetY.toPx() + spreadPx
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = shadowColor.copy(alpha = shadowColor.alpha * 0.02f).toArgb()
                setShadowLayer(blurPx, shadow.offsetX.toPx(), shadow.offsetY.toPx(), shadowColor.toArgb())
            }
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawRoundRect(
                    left,
                    top,
                    right,
                    bottom,
                    shape.topStart.toPx(size, this),
                    shape.topStart.toPx(size, this),
                    paint,
                )
            }
        }
    }
}

private fun ComputedStyle.borderDrawModifier(shape: RoundedCornerShape): Modifier {
    val sides = listOf(border.top, border.right, border.bottom, border.left)
    if (sides.none { it.style != RichBorderStyle.None && it.width > 0.dp && it.color.alpha > 0f }) return Modifier
    return Modifier.drawBehind {
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val sameSide = sides.first().takeIf { first ->
                first.style != RichBorderStyle.None &&
                    first.width > 0.dp &&
                    first.color.alpha > 0f &&
                    sides.all { it == first }
            }
            if (sameSide != null) {
                val paint = borderPaint(sameSide)
                val inset = sameSide.width.toPx() / 2f
                if (sameSide.style == RichBorderStyle.Double) {
                    val third = sameSide.width.toPx() / 3f
                    paint.strokeWidth = third
                    native.drawRoundRect(
                        inset,
                        inset,
                        size.width - inset,
                        size.height - inset,
                        shape.topStart.toPx(size, this),
                        shape.topStart.toPx(size, this),
                        paint,
                    )
                    native.drawRoundRect(
                        inset + third * 2f,
                        inset + third * 2f,
                        size.width - inset - third * 2f,
                        size.height - inset - third * 2f,
                        shape.topStart.toPx(size, this),
                        shape.topStart.toPx(size, this),
                        paint,
                    )
                } else {
                    native.drawRoundRect(
                        inset,
                        inset,
                        size.width - inset,
                        size.height - inset,
                        shape.topStart.toPx(size, this),
                        shape.topStart.toPx(size, this),
                        paint,
                    )
                }
                return@drawIntoCanvas
            }

            drawBorderLine(native, border.top, 0f, border.top.width.toPx() / 2f, size.width, border.top.width.toPx() / 2f)
            drawBorderLine(native, border.right, size.width - border.right.width.toPx() / 2f, 0f, size.width - border.right.width.toPx() / 2f, size.height)
            drawBorderLine(native, border.bottom, 0f, size.height - border.bottom.width.toPx() / 2f, size.width, size.height - border.bottom.width.toPx() / 2f)
            drawBorderLine(native, border.left, border.left.width.toPx() / 2f, 0f, border.left.width.toPx() / 2f, size.height)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBorderLine(
    canvas: android.graphics.Canvas,
    side: RichBorderSide,
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
) {
    if (side.style == RichBorderStyle.None || side.width <= 0.dp || side.color.alpha <= 0f) return
    val paint = borderPaint(side)
    if (side.style == RichBorderStyle.Double) {
        val delta = side.width.toPx() / 3f
        paint.strokeWidth = delta
        canvas.drawLine(startX, startY, endX, endY, paint)
        val horizontal = startY == endY
        val sign = if (startX == 0f || startY == 0f) 1f else -1f
        if (horizontal) {
            canvas.drawLine(startX, startY + delta * 2f * sign, endX, endY + delta * 2f * sign, paint)
        } else {
            canvas.drawLine(startX + delta * 2f * sign, startY, endX + delta * 2f * sign, endY, paint)
        }
    } else {
        canvas.drawLine(startX, startY, endX, endY, paint)
    }
}

private fun DrawScope.borderPaint(side: RichBorderSide): Paint {
    val strokeWidthPx = side.width.toPx()
    return Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        color = side.color.toArgb()
        strokeCap = if (side.style == RichBorderStyle.Dotted) Paint.Cap.ROUND else Paint.Cap.SQUARE
        pathEffect = when (side.style) {
            RichBorderStyle.Dashed -> DashPathEffect(
                PreparedDrawCache.dashRecipe(listOf(strokeWidthPx * 3f, strokeWidthPx * 2f)).intervals.toFloatArray(),
                0f,
            )
            RichBorderStyle.Dotted -> DashPathEffect(
                PreparedDrawCache.dashRecipe(listOf(0f, strokeWidthPx * 2f)).intervals.toFloatArray(),
                0f,
            )
            else -> null
        }
    }
}

private fun ComputedStyle.backgroundBrush(): Brush? {
    return backgroundImage?.let(::richBackgroundImageBrush)
}

private fun richBackgroundImageBrush(image: RichBackgroundImage, targetSize: Size? = null): Brush {
    return when (image) {
        is RichBackgroundImage.LinearGradient -> {
            image.stops.recordPreparedGradientRecipe()
            val radians = Math.toRadians(image.angleDegrees.toDouble())
            val width = targetSize?.width?.coerceAtLeast(1f) ?: 1000f
            val height = targetSize?.height?.coerceAtLeast(1f) ?: 1000f
            val length = sqrt(width * width + height * height)
            val center = Offset(width / 2f, height / 2f)
            val vector = Offset(
                x = cos(radians).toFloat() * length / 2f,
                y = sin(radians).toFloat() * length / 2f,
            )
            val start = center - vector
            val end = center + vector
            image.stops.colorStopPairs()?.let { colorStops ->
                Brush.linearGradient(colorStops = colorStops, start = start, end = end)
            } ?: Brush.linearGradient(colors = image.stops.colors(), start = start, end = end)
        }
        is RichBackgroundImage.RadialGradient -> {
            image.stops.recordPreparedGradientRecipe()
            val center = targetSize?.let { Offset(it.width / 2f, it.height / 2f) } ?: Offset.Unspecified
            val radius = targetSize?.let { max(it.width, it.height).coerceAtLeast(1f) / 2f } ?: Float.POSITIVE_INFINITY
            image.stops.colorStopPairs()?.let { Brush.radialGradient(colorStops = it, center = center, radius = radius) }
                ?: Brush.radialGradient(image.stops.colors(), center = center, radius = radius)
        }
        is RichBackgroundImage.ConicGradient -> {
            image.stops.recordPreparedGradientRecipe()
            image.stops.colorStopPairs()?.let { Brush.sweepGradient(colorStops = it) }
                ?: Brush.sweepGradient(image.stops.colors())
        }
    }
}

private fun ComputedStyle.textClipBrush(): Brush? {
    if (backgroundClip != RichBackgroundBox.Text) return null
    return when (val image = backgroundImage) {
        is RichBackgroundImage.LinearGradient,
        is RichBackgroundImage.RadialGradient,
        is RichBackgroundImage.ConicGradient -> backgroundBrush()
        null -> null
    }
}

private fun RichBackgroundSize.toContentScale(): ContentScale = when (this) {
    RichBackgroundSize.Auto -> ContentScale.Crop
    RichBackgroundSize.Cover -> ContentScale.Crop
    RichBackgroundSize.Contain -> ContentScale.Fit
    is RichBackgroundSize.Explicit -> ContentScale.Fit
}

private fun RichObjectFit.toContentScale(): ContentScale = when (this) {
    RichObjectFit.Cover -> ContentScale.Crop
    RichObjectFit.Contain -> ContentScale.Fit
    RichObjectFit.Fill -> ContentScale.FillBounds
    RichObjectFit.None -> ContentScale.None
}

@Composable
private fun RichBackgroundPosition.toAlignment(): Alignment {
    val density = LocalDensity.current
    val xOffsetPx = with(density) { xOffset.toPx().roundToInt() }
    val yOffsetPx = with(density) { yOffset.toPx().roundToInt() }
    val horizontal = when {
        xFraction <= 0.25f -> Alignment.Start
        xFraction >= 0.75f -> Alignment.End
        else -> Alignment.CenterHorizontally
    }
    val vertical = when {
        yFraction <= 0.25f -> Alignment.Top
        yFraction >= 0.75f -> Alignment.Bottom
        else -> Alignment.CenterVertically
    }
    return object : Alignment {
        override fun align(size: IntSize, space: IntSize, layoutDirection: LayoutDirection): IntOffset {
            val x = when (horizontal) {
                Alignment.Start -> 0
                Alignment.CenterHorizontally -> (space.width - size.width) / 2
                Alignment.End -> space.width - size.width
                else -> 0
            } + xOffsetPx
            val y = when (vertical) {
                Alignment.Top -> 0
                Alignment.CenterVertically -> (space.height - size.height) / 2
                Alignment.Bottom -> space.height - size.height
                else -> 0
            } + yOffsetPx
            return IntOffset(x, y)
        }
    }
}

private fun List<RichColorStop>.colors(): List<Color> = map { it.color }

private fun List<RichColorStop>.recordPreparedGradientRecipe() {
    PreparedDrawCache.gradientRecipe(
        colors = map { it.color.toArgb() },
        stops = map { it.offset },
    )
}

private fun List<RichColorStop>.colorStopPairs(): Array<Pair<Float, Color>>? {
    if (size < 2 || any { it.offset == null }) return null
    return map { it.offset!!.coerceIn(0f, 1f) to it.color }.toTypedArray()
}

private fun RichSpacing.toPaddingValues(root: Boolean): androidx.compose.foundation.layout.PaddingValues {
    return androidx.compose.foundation.layout.PaddingValues(
        start = left,
        top = top,
        end = right,
        bottom = bottom,
    )
}

private fun RichSize.dpOrNull(): Dp? = (this as? RichSize.DpSize)?.value

private fun ComputedStyle.toHorizontalArrangement(childCount: Int): Arrangement.Horizontal = when (justifyContent) {
    RichJustify.Center -> Arrangement.spacedBy(gap, Alignment.CenterHorizontally)
    RichJustify.End -> Arrangement.spacedBy(gap, Alignment.End)
    RichJustify.SpaceBetween -> if (gap > 0.dp && childCount <= 1) Arrangement.spacedBy(gap) else Arrangement.SpaceBetween
    RichJustify.SpaceAround -> if (gap > 0.dp && childCount <= 1) Arrangement.spacedBy(gap) else Arrangement.SpaceAround
    RichJustify.SpaceEvenly -> if (gap > 0.dp && childCount <= 1) Arrangement.spacedBy(gap) else Arrangement.SpaceEvenly
    RichJustify.Start -> Arrangement.spacedBy(gap)
}

private fun ComputedStyle.toColumnAlignment(): Alignment.Horizontal = when (alignItems) {
    RichAlign.Center -> Alignment.CenterHorizontally
    RichAlign.End -> Alignment.End
    else -> Alignment.Start
}

private fun ComputedStyle.toVerticalAlignment(): Alignment.Vertical = when (alignItems) {
    RichAlign.Center -> Alignment.CenterVertically
    RichAlign.End -> Alignment.Bottom
    else -> Alignment.Top
}

private fun RichAlign.toRowAlignment(): Alignment.Vertical = when (this) {
    RichAlign.Center -> Alignment.CenterVertically
    RichAlign.End -> Alignment.Bottom
    RichAlign.Start,
    RichAlign.Stretch,
    RichAlign.Baseline -> Alignment.Top
}

private fun RichAlign.toColumnAlignment(): Alignment.Horizontal = when (this) {
    RichAlign.Center -> Alignment.CenterHorizontally
    RichAlign.End -> Alignment.End
    RichAlign.Start,
    RichAlign.Stretch,
    RichAlign.Baseline -> Alignment.Start
}
