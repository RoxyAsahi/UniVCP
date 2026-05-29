@file:Suppress("unused")

package me.rerere.rikkahub.ui.components.table

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * DataTable（自定义布局 + 横向滚动 + 行内等高）
 * - 使用 SubcomposeLayout 两阶段测量，避免 Lookahead 下的重复测量异常
 * - 高度自适应内容（不提供纵向滚动）
 * - 宽度可超出视口，外层内置 horizontalScroll
 */
@Composable
fun DataTable(
    headers: List<@Composable () -> Unit>,
    rows: List<List<@Composable () -> Unit>>,
    modifier: Modifier = Modifier,
    cellPadding: Dp = 4.dp,
    cellBorder: BorderStroke? = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    headerBackground: Color = MaterialTheme.colorScheme.surfaceVariant,
    zebraStriping: Boolean = false,
    columnMinWidths: List<Dp> = emptyList(),
    columnMaxWidths: List<Dp> = emptyList(),
    cellAlignment: Alignment = Alignment.CenterStart,
) {
    SpannedDataTable(
        headers = headers,
        rows = rows,
        modifier = modifier,
        cellPadding = cellPadding,
        cellBorder = cellBorder,
        headerBackground = headerBackground,
        zebraStriping = zebraStriping,
        columnMinWidths = columnMinWidths,
        columnMaxWidths = columnMaxWidths,
        cellAlignment = cellAlignment,
    )
}

@Composable
internal fun SpannedDataTable(
    headers: List<@Composable () -> Unit>,
    rows: List<List<@Composable () -> Unit>>,
    modifier: Modifier = Modifier,
    cellPadding: Dp = 4.dp,
    cellBorder: BorderStroke? = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    headerBackground: Color = MaterialTheme.colorScheme.surfaceVariant,
    zebraStriping: Boolean = false,
    columnMinWidths: List<Dp> = emptyList(),
    columnMaxWidths: List<Dp> = emptyList(),
    cellAlignment: Alignment = Alignment.CenterStart,
    headerColSpans: List<Int> = emptyList(),
    rowColSpans: List<List<Int>> = emptyList(),
    rowSpans: List<List<Int>> = emptyList(),
    headerCellStyles: List<DataTableCellStyle> = emptyList(),
    rowCellStyles: List<List<DataTableCellStyle>> = emptyList(),
    rowSectionTypes: List<DataTableSectionType> = emptyList(),
    collapseBorders: Boolean = false,
) {
    val hScroll = rememberScrollState()
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
    val footerBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)

    BoxWithConstraints(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .then(
                if (collapseBorders) {
                    Modifier.border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant), MaterialTheme.shapes.small)
                } else {
                    Modifier.border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), MaterialTheme.shapes.small)
                }
            ),
    ) {
        val boundedWidth = maxWidth != Dp.Infinity
        Box(
            modifier = if (boundedWidth) {
                Modifier.horizontalScroll(hScroll)
            } else {
                Modifier
            }
        ) {
            SubcomposeLayout { constraints ->
            val headerSpecs = tableRowSpecs(
                cellCount = headers.size,
                colSpans = headerColSpans,
                rowSpans = emptyList(),
                rowIndex = 0,
                occupancy = mutableListOf(),
            )
            val bodySpecs = buildBodyTableSpecs(rows, rowColSpans, rowSpans)
            val columnCount = max(
                headerSpecs.maxOfOrNull { it.startColumn + it.colspan } ?: 0,
                bodySpecs.maxOfOrNull { row -> row.maxOfOrNull { it.startColumn + it.colspan } ?: 0 } ?: 0,
            )
            val rowCount = rows.size
            if (columnCount == 0) return@SubcomposeLayout layout(0, 0) {}

            // ---------- 参数 & 中间结果容器 ----------
            val infinity = Constraints.Infinity
            val unbounded = Constraints(0, infinity, 0, infinity)
            val minWidthsPx = IntArray(columnCount) { i -> columnMinWidths.getOrNull(i)?.roundToPx() ?: 0 }
            val maxWidthsPx = IntArray(columnCount) { i -> columnMaxWidths.getOrNull(i)?.roundToPx() ?: Int.MAX_VALUE }
            val colWidths = IntArray(columnCount) { 0 }
            val headerP1 = arrayOfNulls<Placeable>(headerSpecs.size)
            val bodyP1 = bodySpecs.map { arrayOfNulls<Placeable>(it.size) }

            // ---------- 第一阶段：自然尺寸测量（估列宽、算行高） ----------
            fun updateSpannedColumnWidths(spec: TableCellSpec, measuredWidth: Int) {
                val safeSpan = spec.colspan.coerceAtLeast(1)
                val perColumn = ceil(measuredWidth.toDouble() / safeSpan).toInt()
                for (c in spec.startColumn until (spec.startColumn + safeSpan).coerceAtMost(columnCount)) {
                    colWidths[c] = max(colWidths[c], max(perColumn, minWidthsPx[c])).coerceAtMost(maxWidthsPx[c])
                }
            }

            fun subcomposeHeaderOnce(index: Int, spec: TableCellSpec): Placeable {
                val cellStyle = headerCellStyles.getOrNull(spec.cellIndex)
                val measurables = subcompose("h1_$index") {
                    CellBox(
                        padding = cellPadding,
                        border = cellBorder.collapseIfNeeded(collapseBorders),
                        background = cellStyle?.background ?: headerBackground,
                        alignment = cellStyle?.alignment ?: cellAlignment
                    ) {
                        headers.getOrNull(spec.cellIndex)?.invoke()
                    }
                }
                val maxWidth = spec.maxWidthPx(maxWidthsPx)
                val constraints = if (maxWidth != Int.MAX_VALUE) Constraints(0, maxWidth, 0, infinity) else unbounded
                val p = measurables.first().measure(constraints)
                updateSpannedColumnWidths(spec, p.width)
                return p
            }

            fun subcomposeBodyOnce(r: Int, index: Int, spec: TableCellSpec): Placeable {
                val bg = defaultBodyCellBackground(
                    rowIndex = r,
                    sectionType = rowSectionTypes.getOrNull(r),
                    zebraStriping = zebraStriping,
                    zebraColor = surfaceContainer,
                    headerColor = headerBackground,
                    footerColor = footerBackground,
                )
                val cellStyle = rowCellStyles.getOrNull(r)?.getOrNull(spec.cellIndex)
                val measurables = subcompose("b1_${r}_$index") {
                    CellBox(
                        padding = cellPadding,
                        border = cellBorder.collapseIfNeeded(collapseBorders),
                        background = cellStyle?.background ?: bg,
                        alignment = cellStyle?.alignment ?: cellAlignment,
                    ) {
                        rows[r].getOrNull(spec.cellIndex)?.invoke()
                    }
                }
                val maxWidth = spec.maxWidthPx(maxWidthsPx)
                val constraints = if (maxWidth != Int.MAX_VALUE) Constraints(0, maxWidth, 0, infinity) else unbounded
                val p = measurables.first().measure(constraints)
                updateSpannedColumnWidths(spec, p.width)
                return p
            }

            headerSpecs.forEachIndexed { index, spec -> headerP1[index] = subcomposeHeaderOnce(index, spec) }
            bodySpecs.forEachIndexed { r, specs ->
                specs.forEachIndexed { index, spec -> bodyP1[r][index] = subcomposeBodyOnce(r, index, spec) }
            }

            val rowHeights = IntArray(rowCount) { r ->
                var h = 0
                bodySpecs[r].forEachIndexed { index, spec ->
                    if (spec.rowspan <= 1) {
                        h = max(h, bodyP1[r][index]!!.height)
                    } else {
                        h = max(h, ceil(bodyP1[r][index]!!.height.toDouble() / spec.rowspan.coerceAtLeast(1)).toInt())
                    }
                }
                h
            }
            distributeRowspanHeights(rowHeights, bodySpecs, bodyP1, rowCount)
            val headerHeight = headerP1.maxOfOrNull { it?.height ?: 0 } ?: 0

            // ---------- 第二阶段：固定列宽 + 统一行高重新测量 ----------
            fun constraintsFor(spec: TableCellSpec, minH: Int): Constraints {
                val safeColWidth = spec.widthPx(colWidths).coerceAtLeast(0)
                val safeMinH = minH.coerceAtLeast(0)
                return Constraints(
                    minWidth = safeColWidth,
                    maxWidth = safeColWidth,
                    minHeight = safeMinH,
                    maxHeight = infinity,
                )
            }

            val headerPlaceables = Array(headerSpecs.size) { index ->
                val spec = headerSpecs[index]
                val cellStyle = headerCellStyles.getOrNull(spec.cellIndex)
                val measurables = subcompose("h2_$index") {
                    CellBox(
                        padding = cellPadding,
                        border = cellBorder.collapseIfNeeded(collapseBorders),
                        background = cellStyle?.background ?: headerBackground,
                        alignment = cellStyle?.alignment ?: cellAlignment
                    ) {
                        headers.getOrNull(spec.cellIndex)?.invoke()
                    }
                }
                measurables.first().measure(constraintsFor(spec, headerHeight))
            }

            val bodyPlaceables = bodySpecs.mapIndexed { r, specs ->
                Array(specs.size) { index ->
                    val spec = specs[index]
                    val bg = defaultBodyCellBackground(
                        rowIndex = r,
                        sectionType = rowSectionTypes.getOrNull(r),
                        zebraStriping = zebraStriping,
                        zebraColor = surfaceContainer,
                        headerColor = headerBackground,
                        footerColor = footerBackground,
                    )
                    val cellStyle = rowCellStyles.getOrNull(r)?.getOrNull(spec.cellIndex)
                    val measurables = subcompose("b2_${r}_$index") {
                        CellBox(
                            padding = cellPadding,
                            border = cellBorder.collapseIfNeeded(collapseBorders),
                            background = cellStyle?.background ?: bg,
                            alignment = cellStyle?.alignment ?: cellAlignment,
                        ) {
                            rows[r].getOrNull(spec.cellIndex)?.invoke()
                        }
                    }
                    val spannedHeight = rowHeights.sumRange(r, (r + spec.rowspan).coerceAtMost(rowCount)).coerceAtLeast(rowHeights.getOrElse(r) { 0 })
                    measurables.first().measure(constraintsFor(spec, spannedHeight))
                }
            }

            val tableWidth = colWidths.sum()
            val tableHeight = headerHeight + rowHeights.sum()
            val finalWidth = tableWidth.coerceIn(constraints.minWidth, constraints.maxWidth)
            val finalHeight = tableHeight.coerceIn(constraints.minHeight, constraints.maxHeight)

            // ---------- 放置 ----------
            layout(finalWidth, finalHeight) {
                headerSpecs.forEachIndexed { index, spec ->
                    headerPlaceables[index].placeRelative(colWidths.sumRange(0, spec.startColumn), 0)
                }
                var y = headerHeight
                for (r in 0 until rowCount) {
                    bodySpecs[r].forEachIndexed { index, spec ->
                        bodyPlaceables[r][index].placeRelative(colWidths.sumRange(0, spec.startColumn), y)
                    }
                    y += rowHeights[r]
                }
            }
            }
        }
    }
}

private data class TableCellSpec(
    val cellIndex: Int,
    val startColumn: Int,
    val colspan: Int,
    val rowspan: Int,
)

internal data class DataTableCellStyle(
    val background: Color? = null,
    val alignment: Alignment? = null,
)

internal enum class DataTableSectionType {
    Head,
    Body,
    Foot,
}

private fun defaultBodyCellBackground(
    rowIndex: Int,
    sectionType: DataTableSectionType?,
    zebraStriping: Boolean,
    zebraColor: Color,
    headerColor: Color,
    footerColor: Color,
): Color {
    return when (sectionType) {
        DataTableSectionType.Head -> headerColor
        DataTableSectionType.Foot -> footerColor
        else -> if (zebraStriping && rowIndex % 2 == 1) zebraColor else Color.Transparent
    }
}

private fun distributeRowspanHeights(
    rowHeights: IntArray,
    bodySpecs: List<List<TableCellSpec>>,
    bodyPlaceables: List<Array<Placeable?>>,
    rowCount: Int,
) {
    bodySpecs.forEachIndexed { rowIndex, specs ->
        specs.forEachIndexed { cellIndex, spec ->
            if (spec.rowspan <= 1) return@forEachIndexed
            val endRow = min(rowCount, rowIndex + spec.rowspan)
            val currentHeight = rowHeights.sumRange(rowIndex, endRow)
            val neededHeight = bodyPlaceables[rowIndex][cellIndex]?.height ?: 0
            val deficit = neededHeight - currentHeight
            if (deficit <= 0) return@forEachIndexed
            val share = ceil(deficit.toDouble() / (endRow - rowIndex).coerceAtLeast(1)).toInt()
            for (row in rowIndex until endRow) {
                rowHeights[row] += share
            }
        }
    }
}

private fun buildBodyTableSpecs(
    rows: List<List<@Composable () -> Unit>>,
    colSpans: List<List<Int>>,
    rowSpans: List<List<Int>>,
): List<List<TableCellSpec>> {
    val occupancy = mutableListOf<Int>()
    return rows.mapIndexed { rowIndex, row ->
        tableRowSpecs(
            cellCount = row.size,
            colSpans = colSpans.getOrNull(rowIndex).orEmpty(),
            rowSpans = rowSpans.getOrNull(rowIndex).orEmpty(),
            rowIndex = rowIndex,
            occupancy = occupancy,
        )
    }
}

private fun tableRowSpecs(
    cellCount: Int,
    colSpans: List<Int>,
    rowSpans: List<Int>,
    rowIndex: Int,
    occupancy: MutableList<Int>,
): List<TableCellSpec> {
    val specs = mutableListOf<TableCellSpec>()
    var column = 0
    repeat(cellCount) { cellIndex ->
        while (occupancy.getOrElse(column) { 0 } > rowIndex) column++
        val colspan = colSpans.getOrNull(cellIndex)?.coerceIn(1, 12) ?: 1
        val rowspan = rowSpans.getOrNull(cellIndex)?.coerceIn(1, 12) ?: 1
        specs += TableCellSpec(cellIndex, column, colspan, rowspan)
        repeat(colspan) { offset ->
            val occupiedColumn = column + offset
            while (occupancy.size <= occupiedColumn) occupancy += 0
            occupancy[occupiedColumn] = max(occupancy[occupiedColumn], rowIndex + rowspan)
        }
        column += colspan
    }
    return specs
}

private fun TableCellSpec.widthPx(widths: IntArray): Int = widths.sumRange(startColumn, startColumn + colspan)

private fun TableCellSpec.maxWidthPx(maxWidths: IntArray): Int {
    var total = 0
    for (index in startColumn until (startColumn + colspan).coerceAtMost(maxWidths.size)) {
        val width = maxWidths[index]
        if (width == Int.MAX_VALUE) return Int.MAX_VALUE
        total += width
    }
    return total.takeIf { it > 0 } ?: Int.MAX_VALUE
}

private fun IntArray.sumRange(from: Int, to: Int): Int {
    var total = 0
    for (index in from.coerceAtLeast(0) until to.coerceAtMost(size)) total += this[index]
    return total
}

private fun BorderStroke?.collapseIfNeeded(collapse: Boolean): BorderStroke? {
    if (!collapse || this == null) return this
    return BorderStroke(width / 2f, brush)
}

@Composable
private fun CellBox(
    padding: Dp,
    border: BorderStroke?,
    background: Color,
    alignment: Alignment,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .then(if (background != Color.Transparent) Modifier.background(background) else Modifier)
            .then(if (border != null) Modifier.border(border) else Modifier)
            .padding(padding),
        contentAlignment = alignment,
    ) {
        content()
    }
}

// -------------------- 示例 --------------------
@Preview(showBackground = true)
@Composable
private fun DataTablePreview() {
    Surface {
        val headers = listOf<@Composable () -> Unit>(
            { Text("Semester", style = MaterialTheme.typography.labelLarge) },
            { Text("Attendance", style = MaterialTheme.typography.labelLarge) },
            { Text("Notes / Example", style = MaterialTheme.typography.labelLarge) },
        )

        val rows = listOf<List<@Composable () -> Unit>>(
            listOf<@Composable () -> Unit>(
                { Text("Fall 2024") },
                { Text("Excellent", style = MaterialTheme.typography.bodyMedium) },
                { Text("x² + y² = 1") },
            ),
            listOf(
                { Text("Fall 2024") },
                { Text("Good", style = MaterialTheme.typography.bodyMedium) },
                { Text("∑ k = n(n+1)/2", maxLines = 2, overflow = TextOverflow.Ellipsis) },
            ),
            listOf(
                { Text("Fall 2024") },
                { Text("Fair", style = MaterialTheme.typography.bodyMedium) },
                { MarkdownBlock("这行更高会把整行拉齐! 这是一个很长的文本用来测试换行功能!  \n>haha") },
            ),
        )

        DataTable(
            headers = headers,
            rows = rows,
            columnMinWidths = listOf(60.dp, 100.dp, 80.dp),
            columnMaxWidths = listOf(120.dp, 100.dp, 200.dp),
            zebraStriping = false,
        )
    }
}
