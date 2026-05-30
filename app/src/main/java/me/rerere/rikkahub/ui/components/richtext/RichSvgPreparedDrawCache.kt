package me.rerere.rikkahub.ui.components.richtext

import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.PathParser
import me.rerere.rikkahub.ui.components.render.RenderLruCache
import kotlin.math.max
import kotlin.math.min

internal object RichSvgPreparedDrawCache {
    private val cache = RenderLruCache<String, List<PreparedRichSvgCommand>>(maxEntries = 128)

    fun prepare(model: RichSvgModel): List<PreparedRichSvgCommand> {
        return cache.getOrPut(model.preparedDrawKey()) {
            model.commands.map { it.prepareSvgDrawCommand() }
        }
    }

    fun clearForTest() {
        cache.clear()
    }

    private fun RichSvgModel.preparedDrawKey(): String {
        return buildString {
            append(width.value)
            append(':')
            append(height.value)
            append(':')
            append(viewBox)
            append(':')
            append(commands.hashCode())
        }
    }
}

internal data class PreparedRichSvgCommand(
    val command: RichSvgCommand,
    val path: android.graphics.Path? = null,
    val bounds: RectF? = null,
    val fillPaint: Paint? = null,
    val strokePaint: Paint? = null,
)

private fun RichSvgCommand.prepareSvgDrawCommand(): PreparedRichSvgCommand {
    return when (this) {
        is RichSvgCommand.Path -> {
            val path = runCatching { PathParser.createPathFromPathData(d) }.getOrNull()
            val bounds = path?.let {
                RectF().also { rect -> it.computeBounds(rect, true) }
            }
            PreparedRichSvgCommand(
                command = this,
                path = path,
                bounds = bounds,
                fillPaint = bounds?.let { fill?.toPaint(Paint.Style.FILL, bounds = it) },
                strokePaint = bounds?.let {
                    stroke?.toPaint(Paint.Style.STROKE, strokeWidth, it, strokeLineCap, strokeLineJoin, strokeDashArray)
                },
            )
        }

        is RichSvgCommand.Polyline -> {
            val path = android.graphics.Path()
            points.firstOrNull()?.let { first ->
                path.moveTo(first.x, first.y)
                points.drop(1).forEach { point -> path.lineTo(point.x, point.y) }
                if (closed) path.close()
            }
            val bounds = RectF().also { path.computeBounds(it, true) }
            PreparedRichSvgCommand(
                command = this,
                path = path,
                bounds = bounds,
                fillPaint = fill?.toPaint(Paint.Style.FILL, bounds = bounds),
                strokePaint = stroke?.toPaint(Paint.Style.STROKE, strokeWidth, bounds, strokeLineCap, strokeLineJoin, strokeDashArray),
            )
        }

        is RichSvgCommand.Rect -> {
            val bounds = RectF(x, y, x + width, y + height)
            PreparedRichSvgCommand(
                command = this,
                bounds = bounds,
                fillPaint = fill?.toPaint(Paint.Style.FILL, bounds = bounds),
                strokePaint = stroke?.toPaint(Paint.Style.STROKE, strokeWidth, bounds, strokeLineCap, strokeLineJoin, strokeDashArray),
            )
        }

        is RichSvgCommand.Circle -> {
            val bounds = RectF(cx - r, cy - r, cx + r, cy + r)
            PreparedRichSvgCommand(
                command = this,
                bounds = bounds,
                fillPaint = fill?.toPaint(Paint.Style.FILL, bounds = bounds),
                strokePaint = stroke?.toPaint(Paint.Style.STROKE, strokeWidth, bounds, strokeLineCap, strokeLineJoin, strokeDashArray),
            )
        }

        is RichSvgCommand.Ellipse -> {
            val bounds = RectF(cx - rx, cy - ry, cx + rx, cy + ry)
            PreparedRichSvgCommand(
                command = this,
                bounds = bounds,
                fillPaint = fill?.toPaint(Paint.Style.FILL, bounds = bounds),
                strokePaint = stroke?.toPaint(Paint.Style.STROKE, strokeWidth, bounds, strokeLineCap, strokeLineJoin, strokeDashArray),
            )
        }

        is RichSvgCommand.Line -> {
            val bounds = RectF(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
            PreparedRichSvgCommand(
                command = this,
                bounds = bounds,
                strokePaint = stroke?.toPaint(Paint.Style.STROKE, strokeWidth, bounds, strokeLineCap, strokeLineJoin, strokeDashArray),
            )
        }

        is RichSvgCommand.Text -> {
            val bounds = RectF(x, y - fontSize, x + text.length * fontSize, y)
            PreparedRichSvgCommand(
                command = this,
                bounds = bounds,
                fillPaint = fill?.toPaint(Paint.Style.FILL, bounds = bounds)?.apply {
                    textSize = fontSize
                    textAlign = textAnchor.toPaintAlign()
                    isFakeBoldText = (fontWeight ?: 400) >= 600
                },
            )
        }
    }
}

internal fun PreparedRichSvgCommand.draw(canvas: android.graphics.Canvas) {
    val command = this.command
    canvas.save()
    command.transform().let {
        canvas.translate(it.translateX, it.translateY)
        canvas.scale(it.scaleX, it.scaleY)
        canvas.rotate(it.rotateDegrees)
    }
    when (command) {
        is RichSvgCommand.Path -> {
            path?.let { preparedPath ->
                fillPaint?.let { paint -> canvas.drawPath(preparedPath, paint) }
                strokePaint?.let { paint -> canvas.drawPath(preparedPath, paint) }
            }
        }
        is RichSvgCommand.Rect -> {
            fillPaint?.let { canvas.drawRoundRect(command.x, command.y, command.x + command.width, command.y + command.height, command.rx, command.ry, it) }
            strokePaint?.let { canvas.drawRoundRect(command.x, command.y, command.x + command.width, command.y + command.height, command.rx, command.ry, it) }
        }
        is RichSvgCommand.Circle -> {
            fillPaint?.let { canvas.drawCircle(command.cx, command.cy, command.r, it) }
            strokePaint?.let { canvas.drawCircle(command.cx, command.cy, command.r, it) }
        }
        is RichSvgCommand.Ellipse -> {
            fillPaint?.let { canvas.drawOval(command.cx - command.rx, command.cy - command.ry, command.cx + command.rx, command.cy + command.ry, it) }
            strokePaint?.let { canvas.drawOval(command.cx - command.rx, command.cy - command.ry, command.cx + command.rx, command.cy + command.ry, it) }
        }
        is RichSvgCommand.Line -> {
            strokePaint?.let { canvas.drawLine(command.x1, command.y1, command.x2, command.y2, it) }
        }
        is RichSvgCommand.Polyline -> {
            path?.let { preparedPath ->
                fillPaint?.let { canvas.drawPath(preparedPath, it) }
                strokePaint?.let { canvas.drawPath(preparedPath, it) }
            }
        }
        is RichSvgCommand.Text -> {
            fillPaint?.let { canvas.drawText(command.text, command.x, command.y, it) }
        }
    }
    canvas.restore()
}

private fun RichSvgCommand.transform(): RichSvgTransform = when (this) {
    is RichSvgCommand.Path -> transform
    is RichSvgCommand.Rect -> transform
    is RichSvgCommand.Circle -> transform
    is RichSvgCommand.Ellipse -> transform
    is RichSvgCommand.Line -> transform
    is RichSvgCommand.Polyline -> transform
    is RichSvgCommand.Text -> transform
}

private fun RichSvgPaint.toPaint(
    style: Paint.Style,
    strokeWidth: Float = 1f,
    bounds: RectF = RectF(0f, 0f, 100f, 100f),
    lineCap: RichSvgLineCap = RichSvgLineCap.Round,
    lineJoin: RichSvgLineJoin = RichSvgLineJoin.Round,
    dashArray: List<Float> = emptyList(),
): Paint {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.style = style
        this.strokeWidth = strokeWidth
        strokeJoin = lineJoin.toPaintJoin()
        strokeCap = lineCap.toPaintCap()
        val safeDashArray = dashArray.safeDashIntervals()
        if (safeDashArray.isNotEmpty()) {
            pathEffect = runCatching { DashPathEffect(safeDashArray, 0f) }.getOrNull()
        }
    }
    when (this) {
        is RichSvgPaint.Solid -> paint.color = color.toArgb()
        is RichSvgPaint.LinearGradient -> {
            val colors = stops.map { it.color.toArgb() }.toIntArray()
            if (colors.size < 2) {
                paint.color = stops.firstOrNull()?.color?.toArgb() ?: Color.Black.toArgb()
            } else {
                val positions = stops.map { it.offset }.takeIf { offsets -> offsets.all { it != null } }
                    ?.map { it!!.coerceIn(0f, 1f) }
                    ?.toFloatArray()
                paint.shader = android.graphics.LinearGradient(
                    bounds.left,
                    bounds.top,
                    bounds.right.takeIf { it > bounds.left } ?: bounds.left + 1f,
                    bounds.bottom.takeIf { it > bounds.top } ?: bounds.top + 1f,
                    colors,
                    positions,
                    Shader.TileMode.CLAMP,
                )
            }
        }
        is RichSvgPaint.RadialGradient -> {
            val colors = stops.map { it.color.toArgb() }.toIntArray()
            if (colors.size < 2) {
                paint.color = stops.firstOrNull()?.color?.toArgb() ?: Color.Black.toArgb()
            } else {
                val positions = stops.map { it.offset }.takeIf { offsets -> offsets.all { it != null } }
                    ?.map { it!!.coerceIn(0f, 1f) }
                    ?.toFloatArray()
                paint.shader = android.graphics.RadialGradient(
                    bounds.centerX(),
                    bounds.centerY(),
                    max(bounds.width(), bounds.height()).coerceAtLeast(1f) / 2f,
                    colors,
                    positions,
                    Shader.TileMode.CLAMP,
                )
            }
        }
    }
    return paint
}

private fun List<Float>.safeDashIntervals(): FloatArray {
    val intervals = filter { it.isFinite() && it > 0f }.take(16)
    if (intervals.isEmpty()) return FloatArray(0)
    val evenIntervals = if (intervals.size % 2 == 0) intervals else intervals + intervals
    return evenIntervals.toFloatArray()
}

private fun RichSvgLineCap.toPaintCap(): Paint.Cap = when (this) {
    RichSvgLineCap.Butt -> Paint.Cap.BUTT
    RichSvgLineCap.Round -> Paint.Cap.ROUND
    RichSvgLineCap.Square -> Paint.Cap.SQUARE
}

private fun RichSvgLineJoin.toPaintJoin(): Paint.Join = when (this) {
    RichSvgLineJoin.Miter -> Paint.Join.MITER
    RichSvgLineJoin.Round -> Paint.Join.ROUND
    RichSvgLineJoin.Bevel -> Paint.Join.BEVEL
}

private fun RichSvgTextAnchor.toPaintAlign(): Paint.Align = when (this) {
    RichSvgTextAnchor.Start -> Paint.Align.LEFT
    RichSvgTextAnchor.Middle -> Paint.Align.CENTER
    RichSvgTextAnchor.End -> Paint.Align.RIGHT
}
