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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import coil3.compose.rememberAsyncImagePainter
import me.rerere.rikkahub.ui.components.table.DataTableCellStyle
import me.rerere.rikkahub.ui.components.table.SpannedDataTable
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private val LocalRichRenderColorDefaults = staticCompositionLocalOf { RichRenderColorDefaults.Fallback }
private val LocalRichEffectiveBackground = staticCompositionLocalOf<Color?> { null }
private val LocalRichAnimationHostId = staticCompositionLocalOf { "rich-html" }
private val LocalRichNativeAnimationsEnabled = staticCompositionLocalOf { true }
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
) {
    when (block) {
        is RichTextBlock -> RichTextBlockView(block, modifier)
        is RichContainerBlock -> RichContainerBlockView(block, modifier, onSendInput, root)
        is RichImageBlock -> RichImageBlockView(block, modifier)
        is RichTableBlock -> RichTableBlockView(block, modifier)
        is RichSvgBlock -> RichSvgBlockView(block, modifier)
        is RichMathBlock -> RichMathBlockView(block, modifier)
        is RichButtonBlock -> RichButtonBlockView(block, modifier, onSendInput)
        is RichDetailsBlock -> RichDetailsBlockView(block, modifier, onSendInput)
        is RichUnsupportedBlock -> RichUnsupportedBlockView(block, modifier)
    }
}

@Composable
private fun RichContainerBlockView(
    block: RichContainerBlock,
    modifier: Modifier,
    onSendInput: (String) -> Unit,
    root: Boolean,
) {
    StyledContainer(block.style, modifier = modifier, root = root, animationKey = block.blockId) {
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
        parentStyle.display == RichDisplay.Flex -> {
            RichFlexBoxChildren(children = children, parentStyle = parentStyle, onSendInput = onSendInput)
        }
        parentStyle.display == RichDisplay.Grid -> {
            RichGridChildren(children = children, parentStyle = parentStyle, onSendInput = onSendInput)
        }
        else -> {
            Column(
                modifier = Modifier.fillMaxWidth(),
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
        modifier = Modifier.fillMaxWidth(),
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
        modifier = Modifier.fillMaxWidth(),
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

@Composable
private fun RichTextBlockView(block: RichTextBlock, modifier: Modifier) {
    StyledContainer(block.style, modifier = modifier, root = false, animationKey = block.blockId) {
        val textClipBrush = block.style.textClipBrush()
        val textStyle = LocalTextStyle.current.merge(
            block.style.toTextStyle(if (textClipBrush != null) Color.White else LocalContentColor.current)
        )
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
        val inline = buildInlineMathText(text, block.inlineMath, textStyle)
        Text(
            text = inline.text,
            modifier = Modifier.textClipBrushMask(textClipBrush),
            inlineContent = inline.inlineContent,
            style = textStyle,
            maxLines = if (block.style.whiteSpace == RichWhiteSpace.NoWrap) 1 else Int.MAX_VALUE,
            overflow = if (block.style.textOverflow == RichTextOverflow.Ellipsis) TextOverflow.Ellipsis else TextOverflow.Clip,
        )
    }
}

@Composable
private fun RichListImageMarker(
    url: String,
    fallbackMarker: String,
    textStyle: TextStyle,
) {
    var failed by remember(url) { mutableStateOf(false) }
    if (failed) {
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
                model = url,
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
        )
    }
}

@Composable
private fun RichTableBlockView(block: RichTableBlock, modifier: Modifier) {
    StyledContainer(block.style, modifier = modifier, root = false, animationKey = block.blockId) {
        val defaults = LocalRichRenderColorDefaults.current
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val caption: @Composable () -> Unit = {
                block.caption?.let { caption ->
                    Text(
                        caption,
                        style = MaterialTheme.typography.labelMedium.merge(block.captionStyle?.toTextStyle(LocalContentColor.current) ?: TextStyle.Default),
                        textAlign = block.captionStyle?.textAlign?.takeIf { it != TextAlign.Unspecified } ?: TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
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
                val columnCount = max(
                    headerCells.sumOf { it.colspan.coerceIn(1, 12) },
                    tableRows.maxOfOrNull { row -> row.sumOf { it.colspan.coerceIn(1, 12) } } ?: 0,
                )
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
                )
            }
            if (block.style.captionSide == RichCaptionSide.Bottom) caption()
        }
    }
}

@Composable
private fun TableCellContent(cell: RichTableCell) {
    StyledContainer(cell.style, root = false, animationKey = null) {
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
        Text(
            text = block.label,
            style = LocalTextStyle.current.merge(block.style.toTextStyle(LocalContentColor.current)),
            color = LocalContentColor.current,
        )
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
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = style.shape(default = 0.dp)
    val defaults = LocalRichRenderColorDefaults.current
    val parentBackground = LocalRichEffectiveBackground.current
    val effectiveOpacity = style.effectiveOpacity()
    val backgroundColor = style.backgroundColor?.copy(alpha = style.backgroundColor.alpha * effectiveOpacity)
    val effectiveBackground = RichColorResolver.effectiveBackground(
        parent = parentBackground,
        declared = backgroundColor,
        fallback = defaults.surface,
    )
    val contentColor = RichColorResolver.resolveTextColor(
        requested = style.color,
        fallback = LocalContentColor.current,
        background = effectiveBackground,
        largeOrBold = style.isLargeOrBoldText(),
    ).copy(alpha = (style.color?.alpha ?: 1f) * effectiveOpacity)
    val paintBoxBackground = style.backgroundClip != RichBackgroundBox.Text
    val backgroundLayers = style.backgroundLayers.takeIf { paintBoxBackground && it.isNotEmpty() }.orEmpty()
    val hasLayerBackground = backgroundLayers.isNotEmpty()
    val brush = style.backgroundBrush().takeIf { paintBoxBackground }
    val surfaceBorder = style.surfaceBorderStroke()
    val drawBorder = style.hasCustomDrawBorder()
    val backdropApproximation = style.backdropFilter.hasSupportedEffect
    var outer = modifier
        .then(style.marginModifier())
        .then(style.baseModifier(root))
    outer = outer.then(style.richShadowModifier(shape))
    outer = outer.then(style.cssClipPathModifier())
    outer = outer.then(style.cssMaskModifier())
    outer = outer.then(style.cssBlurFilterModifier())
    outer = outer.then(style.cssColorFilterModifier())
    outer = outer.then(style.nativeAnimationModifier(animationKey))
    if (style.overflow == RichOverflow.Hidden) outer = outer.clip(shape)
    if (onClick != null) outer = outer.clickable(onClick = onClick)
    if (style.overflow == RichOverflow.Scroll || style.overflow == RichOverflow.Auto) {
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

private data class InlineMathText(
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
private fun buildInlineMathText(
    source: AnnotatedString,
    runs: List<InlineMathRun>,
    style: TextStyle,
): InlineMathText {
    if (runs.isEmpty()) return InlineMathText(source, emptyMap())
    val density = LocalDensity.current
    val inlineContent = linkedMapOf<String, InlineTextContent>()
    val fontSize = style.fontSize.takeOrElse { 14.sp }
    val text = buildAnnotatedString {
        var cursor = 0
        runs.sortedBy { it.start }.forEachIndexed { index, run ->
            if (run.start > cursor) append(source.subSequence(cursor, run.start))
            val key = "math-$index-${run.latex.hashCode()}"
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
            cursor = run.end
        }
        if (cursor < source.length) append(source.subSequence(cursor, source.length))
    }
    return InlineMathText(text, inlineContent)
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
    if (root || (width == RichSize.Auto && maxWidth == null)) modifier = modifier.fillMaxWidth()
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
    if (availableWidth == null || display != RichDisplay.Flex || !flexDirection.isRowAxis() || flexWrap != RichFlexWrap.NoWrap) {
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
    if (shrinkWidth == null && parentStyle.display == RichDisplay.Flex && style.flexGrow > 0f) {
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
    if (parentStyle.display == RichDisplay.Flex && style.flexGrow > 0f) {
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
    val painter = rememberAsyncImagePainter(url)
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
    val safeLayers = layers.take(2)
    val painters = safeLayers.map { layer -> layer.url?.let { rememberAsyncImagePainter(it) } }
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
                drawRect(brush = richBackgroundImageBrush(image), size = paintArea.size)
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
    return if (backgroundOrigin == RichBackgroundBox.PaddingBox && backgroundClip == RichBackgroundBox.BorderBox) {
        Modifier.background(brush, shape)
    } else {
        Modifier
            .clip(shape)
            .drawBehind {
                val paintArea = backgroundAreaRect(this@backgroundBrushModifier, backgroundOrigin)
                val clipArea = backgroundAreaRect(this@backgroundBrushModifier, backgroundClip)
                clipRect(
                    left = clipArea.left,
                    top = clipArea.top,
                    right = clipArea.right,
                    bottom = clipArea.bottom,
                ) {
                    translate(left = paintArea.left, top = paintArea.top) {
                        drawRect(brush = brush, size = paintArea.size)
                    }
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
    return Modifier.drawBehind {
        shadows.filterNot { it.inset }.forEach { shadow ->
            val spreadPx = shadow.spread.toPx()
            val left = shadow.offsetX.toPx() - spreadPx
            val top = shadow.offsetY.toPx() - spreadPx
            val right = size.width + shadow.offsetX.toPx() + spreadPx
            val bottom = size.height + shadow.offsetY.toPx() + spreadPx
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = shadow.color.copy(alpha = shadow.color.alpha * 0.02f).toArgb()
                setShadowLayer(shadow.blurRadius.toPx(), shadow.offsetX.toPx(), shadow.offsetY.toPx(), shadow.color.toArgb())
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
            RichBorderStyle.Dashed -> DashPathEffect(floatArrayOf(strokeWidthPx * 3f, strokeWidthPx * 2f), 0f)
            RichBorderStyle.Dotted -> DashPathEffect(floatArrayOf(0f, strokeWidthPx * 2f), 0f)
            else -> null
        }
    }
}

private fun ComputedStyle.backgroundBrush(): Brush? {
    return backgroundImage?.let(::richBackgroundImageBrush)
}

private fun richBackgroundImageBrush(image: RichBackgroundImage): Brush {
    return when (image) {
        is RichBackgroundImage.LinearGradient -> {
            val radians = Math.toRadians(image.angleDegrees.toDouble())
            val start = Offset.Zero
            val end = Offset(cos(radians).toFloat() * 1000f, sin(radians).toFloat() * 1000f)
            image.stops.colorStopPairs()?.let { colorStops ->
                Brush.linearGradient(colorStops = colorStops, start = start, end = end)
            } ?: Brush.linearGradient(colors = image.stops.colors(), start = start, end = end)
        }
        is RichBackgroundImage.RadialGradient -> {
            image.stops.colorStopPairs()?.let { Brush.radialGradient(colorStops = it) }
                ?: Brush.radialGradient(image.stops.colors())
        }
        is RichBackgroundImage.ConicGradient -> {
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
