package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.jsoup.nodes.Element

internal object RichSvgCompiler {
    fun compile(
        svg: Element,
        cancellationCheck: () -> Unit = {},
    ): RichSvgModel? {
        val width = parseSvgDimension(svg.attr("width")) ?: 160.dp
        val height = parseSvgDimension(svg.attr("height")) ?: 80.dp
        val viewBox = parseViewBox(svg.attr("viewBox")) ?: RichSvgViewBox(0f, 0f, width.value, height.value)
        val gradients = parseGradients(svg)
        val visualHints = collectSvgVisualHints(svg)
        val commands = mutableListOf<RichSvgCommand>()

        svg.select("path,rect,circle,ellipse,line,polyline,polygon,text").forEach { element ->
            cancellationCheck()
            val inheritedTransform = collectTransform(element)
            val svgStyle = collectSvgStyle(element, gradients)
            when (element.tagName().lowercase()) {
                "path" -> {
                    val d = element.attr("d")
                    if (d.isNotBlank()) {
                        commands += RichSvgCommand.Path(
                            d = d,
                            fill = svgStyle.fill,
                            stroke = svgStyle.stroke,
                            strokeWidth = svgStyle.strokeWidth,
                            strokeLineCap = svgStyle.strokeLineCap,
                            strokeLineJoin = svgStyle.strokeLineJoin,
                            strokeDashArray = svgStyle.strokeDashArray,
                            transform = inheritedTransform,
                        )
                    }
                }
                "rect" -> commands += RichSvgCommand.Rect(
                    x = parseSvgFloat(element.attr("x")) ?: 0f,
                    y = parseSvgFloat(element.attr("y")) ?: 0f,
                    width = parseSvgFloat(element.attr("width")) ?: 0f,
                    height = parseSvgFloat(element.attr("height")) ?: 0f,
                    rx = parseSvgFloat(element.attr("rx")) ?: parseSvgFloat(element.attr("ry")) ?: 0f,
                    ry = parseSvgFloat(element.attr("ry")) ?: parseSvgFloat(element.attr("rx")) ?: 0f,
                    fill = svgStyle.fill ?: RichSvgPaint.Solid(Color.Black.copy(alpha = svgStyle.opacity)),
                    stroke = svgStyle.stroke,
                    strokeWidth = svgStyle.strokeWidth,
                    strokeLineCap = svgStyle.strokeLineCap,
                    strokeLineJoin = svgStyle.strokeLineJoin,
                    strokeDashArray = svgStyle.strokeDashArray,
                    transform = inheritedTransform,
                )
                "circle" -> commands += RichSvgCommand.Circle(
                    cx = parseSvgFloat(element.attr("cx")) ?: 0f,
                    cy = parseSvgFloat(element.attr("cy")) ?: 0f,
                    r = parseSvgFloat(element.attr("r")) ?: 0f,
                    fill = svgStyle.fill ?: RichSvgPaint.Solid(Color.Black.copy(alpha = svgStyle.opacity)),
                    stroke = svgStyle.stroke,
                    strokeWidth = svgStyle.strokeWidth,
                    strokeLineCap = svgStyle.strokeLineCap,
                    strokeLineJoin = svgStyle.strokeLineJoin,
                    strokeDashArray = svgStyle.strokeDashArray,
                    transform = inheritedTransform,
                )
                "ellipse" -> commands += RichSvgCommand.Ellipse(
                    cx = parseSvgFloat(element.attr("cx")) ?: 0f,
                    cy = parseSvgFloat(element.attr("cy")) ?: 0f,
                    rx = parseSvgFloat(element.attr("rx")) ?: 0f,
                    ry = parseSvgFloat(element.attr("ry")) ?: 0f,
                    fill = svgStyle.fill ?: RichSvgPaint.Solid(Color.Black.copy(alpha = svgStyle.opacity)),
                    stroke = svgStyle.stroke,
                    strokeWidth = svgStyle.strokeWidth,
                    strokeLineCap = svgStyle.strokeLineCap,
                    strokeLineJoin = svgStyle.strokeLineJoin,
                    strokeDashArray = svgStyle.strokeDashArray,
                    transform = inheritedTransform,
                )
                "line" -> commands += RichSvgCommand.Line(
                    x1 = parseSvgFloat(element.attr("x1")) ?: 0f,
                    y1 = parseSvgFloat(element.attr("y1")) ?: 0f,
                    x2 = parseSvgFloat(element.attr("x2")) ?: 0f,
                    y2 = parseSvgFloat(element.attr("y2")) ?: 0f,
                    stroke = svgStyle.stroke ?: RichSvgPaint.Solid(Color.Black.copy(alpha = svgStyle.opacity)),
                    strokeWidth = svgStyle.strokeWidth,
                    strokeLineCap = svgStyle.strokeLineCap,
                    strokeLineJoin = svgStyle.strokeLineJoin,
                    strokeDashArray = svgStyle.strokeDashArray,
                    transform = inheritedTransform,
                )
                "polyline", "polygon" -> commands += RichSvgCommand.Polyline(
                    points = parsePoints(element.attr("points")),
                    fill = svgStyle.fill,
                    stroke = svgStyle.stroke,
                    strokeWidth = svgStyle.strokeWidth,
                    closed = element.tagName().equals("polygon", ignoreCase = true),
                    strokeLineCap = svgStyle.strokeLineCap,
                    strokeLineJoin = svgStyle.strokeLineJoin,
                    strokeDashArray = svgStyle.strokeDashArray,
                    transform = inheritedTransform,
                )
                "text" -> commands += RichSvgCommand.Text(
                    x = parseSvgFloat(element.attr("x")) ?: 0f,
                    y = parseSvgFloat(element.attr("y")) ?: 0f,
                    text = element.text(),
                    fill = svgStyle.fill ?: RichSvgPaint.Solid(Color.Black.copy(alpha = svgStyle.opacity)),
                    fontSize = svgStyle.fontSize,
                    fontWeight = svgStyle.fontWeight,
                    textAnchor = svgStyle.textAnchor,
                    transform = inheritedTransform,
                )
            }
        }

        return if (commands.isEmpty()) null else RichSvgModel(width, height, viewBox, commands.take(256), visualHints)
    }

    private fun collectSvgVisualHints(svg: Element): List<RichVisualHint> {
        val hints = linkedSetOf<RichVisualHint>()
        if (svg.select("clipPath").isNotEmpty() || svg.select("[clip-path]").isNotEmpty()) hints += RichVisualHint.SvgClipPath
        if (svg.select("mask").isNotEmpty() || svg.select("[mask]").isNotEmpty()) hints += RichVisualHint.SvgMask
        if (svg.select("filter").isNotEmpty() || svg.select("[filter]").isNotEmpty()) hints += RichVisualHint.SvgFilter
        if (svg.select("use").isNotEmpty()) hints += RichVisualHint.SvgUse
        if (svg.select("symbol").isNotEmpty()) hints += RichVisualHint.SvgSymbol
        if (svg.select("pattern").isNotEmpty()) hints += RichVisualHint.SvgPattern
        if (svg.select("foreignObject").isNotEmpty()) hints += RichVisualHint.SvgForeignObject
        if (svg.select("marker").isNotEmpty() || svg.select("[marker-start],[marker-mid],[marker-end]").isNotEmpty()) {
            hints += RichVisualHint.SvgMarker
        }
        return hints.toList()
    }

    private fun parseGradients(svg: Element): Map<String, RichSvgPaint> {
        return svg.select("linearGradient,radialGradient").mapNotNull { gradient ->
            val id = gradient.id().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val stops = gradient.select("stop").mapNotNull { stop ->
                val styleColor = stop.styleValue("stop-color")
                val styleOpacity = stop.styleValue("stop-opacity")
                val color = parseSvgColor(stop.attr("stop-color").ifBlank { styleColor.orEmpty() })
                    ?: return@mapNotNull null
                val opacity = stop.attr("stop-opacity")
                    .ifBlank { styleOpacity.orEmpty() }
                    .toFloatOrNull()
                    ?.coerceIn(0f, 1f)
                    ?: 1f
                RichColorStop(
                    color = color.copy(alpha = color.alpha * opacity),
                    offset = parseSvgOffset(stop.attr("offset")),
                )
            }
            val safeStops = stops.ifEmpty {
                listOf(RichColorStop(Color.Black, 0f), RichColorStop(Color.Transparent, 1f))
            }
            id to if (gradient.tagName().equals("radialGradient", ignoreCase = true)) {
                RichSvgPaint.RadialGradient(id = id, stops = safeStops)
            } else {
                RichSvgPaint.LinearGradient(id = id, stops = safeStops)
            }
        }.toMap()
    }

    private fun parsePaint(value: String, gradients: Map<String, RichSvgPaint>, opacity: Float = 1f): RichSvgPaint? {
        val normalized = value.trim()
        if (normalized.isBlank()) return null
        if (normalized.equals("none", ignoreCase = true)) return null
        if (normalized.equals("currentColor", ignoreCase = true)) {
            return RichSvgPaint.Solid(Color.Black.copy(alpha = opacity))
        }
        Regex("""url\(#([^)]+)\)""").find(normalized)?.groupValues?.getOrNull(1)?.let { id ->
            return gradients[id]?.withOpacity(opacity)
        }
        return parseSvgColor(normalized)?.let { color ->
            RichSvgPaint.Solid(color.copy(alpha = color.alpha * opacity))
        }
    }

    private data class SvgStyle(
        val fill: RichSvgPaint?,
        val stroke: RichSvgPaint?,
        val strokeWidth: Float,
        val strokeLineCap: RichSvgLineCap,
        val strokeLineJoin: RichSvgLineJoin,
        val strokeDashArray: List<Float>,
        val opacity: Float,
        val fontSize: Float,
        val fontWeight: Int?,
        val textAnchor: RichSvgTextAnchor,
    )

    private fun collectSvgStyle(element: Element, gradients: Map<String, RichSvgPaint>): SvgStyle {
        val chain = element.parents().asReversed() + element
        fun inheritedAttr(name: String): String {
            return chain.asSequence()
                .map { it.styleValue(name) ?: it.attr(name).takeIf(String::isNotBlank) }
                .filterNotNull()
                .lastOrNull()
                .orEmpty()
        }
        val opacity = parseSvgOpacity(inheritedAttr("opacity"))
        val fillOpacity = opacity * parseSvgOpacity(inheritedAttr("fill-opacity"))
        val strokeOpacity = opacity * parseSvgOpacity(inheritedAttr("stroke-opacity"))
        return SvgStyle(
            fill = parsePaint(inheritedAttr("fill"), gradients, fillOpacity),
            stroke = parsePaint(inheritedAttr("stroke"), gradients, strokeOpacity),
            strokeWidth = parseSvgFloat(inheritedAttr("stroke-width")) ?: 1f,
            strokeLineCap = parseLineCap(inheritedAttr("stroke-linecap")),
            strokeLineJoin = parseLineJoin(inheritedAttr("stroke-linejoin")),
            strokeDashArray = parseDashArray(inheritedAttr("stroke-dasharray")),
            opacity = opacity,
            fontSize = parseSvgFloat(inheritedAttr("font-size")) ?: 10f,
            fontWeight = parseFontWeight(inheritedAttr("font-weight")),
            textAnchor = parseTextAnchor(inheritedAttr("text-anchor")),
        )
    }

    private fun collectTransform(element: Element): RichSvgTransform {
        val transforms = element.parents().asReversed().map { it.attr("transform") } + element.attr("transform")
        return transforms.filter { it.isNotBlank() }.fold(RichSvgTransform.None) { acc, value ->
            parseSvgTransform(value, acc)
        }
    }

    private fun parseSvgTransform(value: String, initial: RichSvgTransform): RichSvgTransform {
        var transform = initial
        Regex("""([a-zA-Z]+)\(([^)]*)\)""").findAll(value).forEach { match ->
            val args = match.groupValues[2].split(Regex("""[\s,]+""")).filter { it.isNotBlank() }
            when (match.groupValues[1].lowercase()) {
                "translate" -> transform = transform.copy(
                    translateX = args.getOrNull(0)?.toFloatOrNull() ?: transform.translateX,
                    translateY = args.getOrNull(1)?.toFloatOrNull() ?: transform.translateY,
                )
                "scale" -> {
                    val x = args.getOrNull(0)?.toFloatOrNull() ?: transform.scaleX
                    transform = transform.copy(scaleX = x, scaleY = args.getOrNull(1)?.toFloatOrNull() ?: x)
                }
                "rotate" -> transform = transform.copy(rotateDegrees = args.getOrNull(0)?.toFloatOrNull() ?: transform.rotateDegrees)
            }
        }
        return transform
    }
}

private fun RichSvgPaint.withOpacity(opacity: Float): RichSvgPaint = when (this) {
    is RichSvgPaint.Solid -> copy(color = color.copy(alpha = color.alpha * opacity))
    is RichSvgPaint.LinearGradient -> copy(stops = stops.map { it.copy(color = it.color.copy(alpha = it.color.alpha * opacity)) })
    is RichSvgPaint.RadialGradient -> copy(stops = stops.map { it.copy(color = it.color.copy(alpha = it.color.alpha * opacity)) })
}

private fun Element.styleValue(name: String): String? {
    return attr("style").split(";")
        .firstOrNull { it.trim().startsWith(name, ignoreCase = true) }
        ?.substringAfter(":")
        ?.trim()
}

private fun parseSvgOpacity(value: String): Float {
    return value.trim().toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
}

private fun parseLineCap(value: String): RichSvgLineCap = when (value.trim().lowercase()) {
    "round" -> RichSvgLineCap.Round
    "square" -> RichSvgLineCap.Square
    else -> RichSvgLineCap.Butt
}

private fun parseLineJoin(value: String): RichSvgLineJoin = when (value.trim().lowercase()) {
    "round" -> RichSvgLineJoin.Round
    "bevel" -> RichSvgLineJoin.Bevel
    else -> RichSvgLineJoin.Miter
}

private fun parseDashArray(value: String): List<Float> {
    val normalized = value.trim()
    if (normalized.isBlank() || normalized.equals("none", ignoreCase = true)) return emptyList()
    return normalized.split(Regex("""[\s,]+"""))
        .mapNotNull { parseSvgFloat(it)?.takeIf { number -> number > 0f } }
        .take(16)
}

private fun parseFontWeight(value: String): Int? = when (value.trim().lowercase()) {
    "bold", "bolder" -> 700
    "normal", "lighter" -> 400
    else -> value.trim().toIntOrNull()
}

private fun parseTextAnchor(value: String): RichSvgTextAnchor = when (value.trim().lowercase()) {
    "middle" -> RichSvgTextAnchor.Middle
    "end" -> RichSvgTextAnchor.End
    else -> RichSvgTextAnchor.Start
}

private fun parseSvgOffset(value: String): Float? {
    val normalized = value.trim()
    return when {
        normalized.endsWith("%") -> normalized.removeSuffix("%").toFloatOrNull()?.div(100f)?.coerceIn(0f, 1f)
        normalized.isNotBlank() -> normalized.toFloatOrNull()?.coerceIn(0f, 1f)
        else -> null
    }
}

private fun parseSvgDimension(value: String): androidx.compose.ui.unit.Dp? {
    val normalized = value.trim().lowercase()
    return when {
        normalized.endsWith("px") -> normalized.removeSuffix("px").toFloatOrNull()?.dp
        normalized.endsWith("dp") -> normalized.removeSuffix("dp").toFloatOrNull()?.dp
        normalized.endsWith("%") -> null
        normalized.isNotBlank() -> normalized.toFloatOrNull()?.dp
        else -> null
    }
}

private fun parseViewBox(value: String): RichSvgViewBox? {
    val parts = value.split(Regex("""[\s,]+""")).mapNotNull { it.toFloatOrNull() }
    return if (parts.size >= 4) RichSvgViewBox(parts[0], parts[1], parts[2], parts[3]) else null
}

private fun parseSvgFloat(value: String): Float? = value.trim().takeIf { it.isNotBlank() }?.let {
    if (it.startsWith(".")) "0$it".toFloatOrNull() else it.toFloatOrNull()
}

private fun parsePoints(value: String): List<Offset> {
    val parts = value.trim().split(Regex("""[\s,]+""")).mapNotNull { it.toFloatOrNull() }
    return parts.chunked(2).mapNotNull { pair ->
        if (pair.size == 2) Offset(pair[0], pair[1]) else null
    }
}

private fun parseSvgColor(value: String): Color? {
    val token = value.trim()
    return runCatching {
        when {
            token.startsWith("#") -> {
                val hex = token.removePrefix("#")
                fun doubled(index: Int): Int = "${hex[index]}${hex[index]}".toInt(16)
                fun pair(start: Int): Int = hex.substring(start, start + 2).toInt(16)
                when (hex.length) {
                    3 -> Color(doubled(0), doubled(1), doubled(2))
                    4 -> Color(doubled(0), doubled(1), doubled(2), doubled(3))
                    6 -> Color(pair(0), pair(2), pair(4))
                    8 -> Color(pair(0), pair(2), pair(4), pair(6))
                    else -> null
                }
            }
            token.startsWith("rgb", ignoreCase = true) -> {
                val parts = token.substringAfter("(").substringBeforeLast(")")
                    .split(Regex("""[\s,\/]+""")).filter { it.isNotBlank() }
                val r = parts.getOrNull(0)?.toFloatOrNull()?.toInt()
                val g = parts.getOrNull(1)?.toFloatOrNull()?.toInt()
                val b = parts.getOrNull(2)?.toFloatOrNull()?.toInt()
                val a = parts.getOrNull(3)?.toFloatOrNull()?.let { (it.coerceIn(0f, 1f) * 255).toInt() } ?: 255
                if (r != null && g != null && b != null) Color(r, g, b, a) else null
            }
            token.equals("none", ignoreCase = true) || token.isBlank() -> null
            token.lowercase() in svgNamedColors -> svgNamedColors.getValue(token.lowercase())
            else -> null
        }
    }.getOrNull()
}

private val svgNamedColors = mapOf(
    "transparent" to Color.Transparent,
    "white" to Color.White,
    "black" to Color.Black,
    "red" to Color.Red,
    "green" to Color.Green,
    "blue" to Color.Blue,
    "gray" to Color.Gray,
    "grey" to Color.Gray,
    "silver" to Color(0xFFC0C0C0),
    "maroon" to Color(0xFF800000),
    "purple" to Color(0xFF800080),
    "fuchsia" to Color(0xFFFF00FF),
    "magenta" to Color(0xFFFF00FF),
    "lime" to Color(0xFF00FF00),
    "olive" to Color(0xFF808000),
    "yellow" to Color.Yellow,
    "navy" to Color(0xFF000080),
    "teal" to Color(0xFF008080),
    "aqua" to Color(0xFF00FFFF),
    "cyan" to Color.Cyan,
    "orange" to Color(0xFFFFA500),
    "pink" to Color(0xFFFFC0CB),
    "brown" to Color(0xFFA52A2A),
    "gold" to Color(0xFFFFD700),
    "skyblue" to Color(0xFF87CEEB),
    "deepskyblue" to Color(0xFF00BFFF),
    "dodgerblue" to Color(0xFF1E90FF),
    "steelblue" to Color(0xFF4682B4),
    "slategray" to Color(0xFF708090),
    "slategrey" to Color(0xFF708090),
    "lightgray" to Color(0xFFD3D3D3),
    "lightgrey" to Color(0xFFD3D3D3),
    "darkgray" to Color(0xFFA9A9A9),
    "darkgrey" to Color(0xFFA9A9A9),
)
