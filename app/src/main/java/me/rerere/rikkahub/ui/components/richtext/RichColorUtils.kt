package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.utilities.Blend
import kotlin.math.abs
import kotlin.math.roundToInt

internal sealed interface RichCssColor {
    data class Solid(val color: Color) : RichCssColor
    data object CurrentColor : RichCssColor
    data object Transparent : RichCssColor
    data class Unresolved(val raw: String) : RichCssColor
}

internal data class RichRenderColorDefaults(
    val text: Color,
    val weakText: Color,
    val surface: Color,
    val surfaceLow: Color,
    val outline: Color,
    val codeBackground: Color,
    val quoteBackground: Color,
    val tableHeaderBackground: Color,
    val tableBodyBackground: Color,
    val tableFooterBackground: Color,
    val buttonBackground: Color,
    val buttonText: Color,
    val accent: Color,
) {
    fun harmonized(): RichRenderColorDefaults {
        return copy(
            weakText = RichColorHarmonizer.harmonizeIfDefault(weakText, accent),
            outline = RichColorHarmonizer.harmonizeIfDefault(outline, accent),
            codeBackground = RichColorHarmonizer.harmonizeIfDefault(codeBackground, accent),
            quoteBackground = RichColorHarmonizer.harmonizeIfDefault(quoteBackground, accent),
            tableHeaderBackground = RichColorHarmonizer.harmonizeIfDefault(tableHeaderBackground, accent),
            tableFooterBackground = RichColorHarmonizer.harmonizeIfDefault(tableFooterBackground, accent),
            buttonBackground = RichColorHarmonizer.harmonizeIfDefault(buttonBackground, accent),
        )
    }

    companion object {
        val Fallback = RichRenderColorDefaults(
            text = Color(0xFF111827),
            weakText = Color(0xFF4B5563),
            surface = Color.White,
            surfaceLow = Color(0xFFF9FAFB),
            outline = Color(0xFFE5E7EB),
            codeBackground = Color(0xFFF3F4F6),
            quoteBackground = Color(0xFFF8FAFC),
            tableHeaderBackground = Color(0xFFF3F4F6),
            tableBodyBackground = Color.Transparent,
            tableFooterBackground = Color(0xFFF9FAFB),
            buttonBackground = Color(0xFF2563EB),
            buttonText = Color.White,
            accent = Color(0xFF2563EB),
        )
    }
}

internal object RichColorHarmonizer {
    fun harmonizeIfDefault(color: Color, source: Color): Color {
        if (color.alpha == 0f) return color
        return runCatching {
            Color(Blend.harmonize(color.toArgb(), source.toArgb())).copy(alpha = color.alpha)
        }.getOrDefault(color)
    }
}

internal object RichColorResolver {
    fun resolveTextColor(
        requested: Color?,
        fallback: Color,
        background: Color?,
        largeOrBold: Boolean = false,
    ): Color {
        val base = requested ?: fallback
        val solidBackground = background?.takeIf { it.alpha >= 0.98f } ?: return base
        val target = if (largeOrBold) 3.0 else 4.5
        val current = contrastRatio(base, solidBackground)
        if (current >= target) return base
        val strongerTarget = if (contrastRatio(Color.Black, solidBackground) >= contrastRatio(Color.White, solidBackground)) {
            Color.Black
        } else {
            Color.White
        }
        val maxMix = if (current < 2.0) 0.55f else 0.35f
        var low = 0f
        var high = maxMix
        var best = base
        repeat(8) {
            val mid = (low + high) / 2f
            val candidate = blend(base, strongerTarget, mid)
            if (contrastRatio(candidate, solidBackground) >= target) {
                best = candidate
                high = mid
            } else {
                low = mid
                best = candidate
            }
        }
        return best
    }

    fun effectiveBackground(
        parent: Color?,
        declared: Color?,
        fallback: Color,
    ): Color {
        val base = parent ?: fallback
        val own = declared ?: return base
        if (own.alpha <= 0f) return base
        return if (own.alpha < 1f) own.compositeOver(base) else own
    }

    fun contrastRatio(foreground: Color, background: Color): Double {
        val fg = if (foreground.alpha < 1f) foreground.compositeOver(background) else foreground
        return runCatching { ColorUtils.calculateContrast(fg.toArgb(), background.toArgb()) }
            .getOrElse { fallbackContrast(fg, background) }
    }

    private fun blend(from: Color, to: Color, amount: Float): Color {
        val t = amount.coerceIn(0f, 1f)
        fun channel(a: Float, b: Float) = a + (b - a) * t
        return Color(
            red = channel(from.red, to.red),
            green = channel(from.green, to.green),
            blue = channel(from.blue, to.blue),
            alpha = from.alpha,
        )
    }

    private fun fallbackContrast(foreground: Color, background: Color): Double {
        val lighter = maxOf(foreground.luminance(), background.luminance())
        val darker = minOf(foreground.luminance(), background.luminance())
        return ((lighter + 0.05f) / (darker + 0.05f)).toDouble()
    }
}

internal fun parseRichCssColor(value: String): RichCssColor? {
    val token = extractCssColorToken(value) ?: value.trim()
    if (token.isBlank()) return null
    if (token.equals("currentColor", ignoreCase = true)) return RichCssColor.CurrentColor
    if (token.equals("transparent", ignoreCase = true)) return RichCssColor.Transparent
    if (token.startsWith("color-mix(", ignoreCase = true)) return RichCssColor.Unresolved(token)
    return runCatching {
        when {
            token.startsWith("#") -> parseHexColor(token)?.let(RichCssColor::Solid)
            token.startsWith("rgb", ignoreCase = true) -> parseRgbColor(token)?.let(RichCssColor::Solid)
            token.startsWith("hsl", ignoreCase = true) -> parseHslColor(token)?.let(RichCssColor::Solid)
            else -> CSS_NAMED_COLORS[token.lowercase()]?.let(RichCssColor::Solid)
        }
    }.getOrNull() ?: RichCssColor.Unresolved(token).takeIf { looksLikeCssColor(token) }
}

internal fun parseCssColor(value: String): Color? {
    return when (val parsed = parseRichCssColor(value)) {
        is RichCssColor.Solid -> parsed.color
        RichCssColor.Transparent -> Color.Transparent
        else -> null
    }
}

internal fun resolveRichCssColor(value: RichCssColor?, currentColor: Color?): Color? {
    return when (value) {
        null -> null
        is RichCssColor.Solid -> value.color
        RichCssColor.Transparent -> Color.Transparent
        RichCssColor.CurrentColor -> currentColor
        is RichCssColor.Unresolved -> null
    }
}

internal fun containsUnresolvedCssColor(value: String): Boolean {
    val parsed = parseRichCssColor(value)
    return parsed is RichCssColor.Unresolved
}

private fun extractCssColorToken(value: String): String? {
    val trimmed = value.trim()
    return when {
        trimmed.contains("gradient", ignoreCase = true) -> CSS_COLOR_TOKEN.find(trimmed)?.value
        trimmed.startsWith("rgb", ignoreCase = true) ||
            trimmed.startsWith("hsl", ignoreCase = true) ||
            trimmed.startsWith("color-mix", ignoreCase = true) -> trimmed
        trimmed.contains(" ") -> trimmed.substringBefore(" ")
        else -> trimmed
    }
}

private fun looksLikeCssColor(token: String): Boolean {
    val normalized = token.trim().lowercase()
    return normalized.startsWith("#") ||
        normalized.startsWith("rgb") ||
        normalized.startsWith("hsl") ||
        normalized.startsWith("color-mix") ||
        normalized == "currentcolor" ||
        normalized == "transparent" ||
        normalized in CSS_NAMED_COLORS
}

private fun parseHexColor(token: String): Color? {
    val hex = token.removePrefix("#")
    fun doubled(index: Int): Int = "${hex[index]}${hex[index]}".toInt(16)
    fun pair(start: Int): Int = hex.substring(start, start + 2).toInt(16)
    return when (hex.length) {
        3 -> Color(doubled(0), doubled(1), doubled(2))
        4 -> Color(doubled(0), doubled(1), doubled(2), doubled(3))
        6 -> Color(pair(0), pair(2), pair(4))
        8 -> Color(pair(0), pair(2), pair(4), pair(6))
        else -> null
    }
}

private fun parseRgbColor(token: String): Color? {
    val body = token.substringAfter("(").substringBeforeLast(")").replace("/", " ")
    val parts = body.split(Regex("""[\s,]+""")).filter { it.isNotBlank() }
    if (parts.size < 3) return null
    val r = parseColorChannel(parts[0]) ?: return null
    val g = parseColorChannel(parts[1]) ?: return null
    val b = parseColorChannel(parts[2]) ?: return null
    val a = parts.getOrNull(3)?.let(::parseAlphaChannel) ?: 255
    return Color(r, g, b, a)
}

private fun parseHslColor(token: String): Color? {
    val body = token.substringAfter("(").substringBeforeLast(")").replace("/", " ")
    val parts = body.split(Regex("""[\s,]+""")).filter { it.isNotBlank() }
    if (parts.size < 3) return null
    val h = parseHueDegrees(parts[0]) ?: return null
    val s = parseCssPercentUnit(parts[1]) ?: return null
    val l = parseCssPercentUnit(parts[2]) ?: return null
    val a = parts.getOrNull(3)?.let(::parseAlphaChannel) ?: 255
    val c = (1f - abs(2f * l - 1f)) * s
    val hPrime = ((h % 360f) + 360f) % 360f / 60f
    val x = c * (1f - abs(hPrime % 2f - 1f))
    val (r1, g1, b1) = when {
        hPrime < 1f -> Triple(c, x, 0f)
        hPrime < 2f -> Triple(x, c, 0f)
        hPrime < 3f -> Triple(0f, c, x)
        hPrime < 4f -> Triple(0f, x, c)
        hPrime < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = l - c / 2f
    fun channel(value: Float): Int = ((value + m).coerceIn(0f, 1f) * 255f).roundToInt()
    return Color(channel(r1), channel(g1), channel(b1), a)
}

private fun parseHueDegrees(value: String): Float? {
    val normalized = value.trim().lowercase()
    return when {
        normalized.endsWith("deg") -> normalized.removeSuffix("deg").toFloatOrNull()
        normalized.endsWith("turn") -> normalized.removeSuffix("turn").toFloatOrNull()?.times(360f)
        normalized.endsWith("rad") -> normalized.removeSuffix("rad").toFloatOrNull()?.times(57.29578f)
        else -> normalized.toFloatOrNull()
    }
}

private fun parseCssPercentUnit(value: String): Float? {
    return value.trim().removeSuffix("%").toFloatOrNull()?.div(100f)?.coerceIn(0f, 1f)
}

private fun parseColorChannel(value: String): Int? {
    return if (value.endsWith("%")) {
        value.removeSuffix("%").toFloatOrNull()?.let { (it.coerceIn(0f, 100f) * 2.55f).roundToInt() }
    } else {
        value.toFloatOrNull()?.roundToInt()?.coerceIn(0, 255)
    }
}

private fun parseAlphaChannel(value: String): Int? {
    return if (value.endsWith("%")) {
        value.removeSuffix("%").toFloatOrNull()?.let { (it.coerceIn(0f, 100f) * 2.55f).roundToInt() }
    } else {
        value.toFloatOrNull()?.let { (it.coerceIn(0f, 1f) * 255f).roundToInt() }
    }
}

internal val CSS_COLOR_TOKEN: Regex by lazy {
    Regex(
    """color-mix\([^)]+\)|#[0-9a-fA-F]{3,8}|rgba?\([^)]+\)|hsla?\([^)]+\)|\bcurrentColor\b|\b(?:${CSS_NAMED_COLORS.keys.joinToString("|")})\b""",
    RegexOption.IGNORE_CASE,
    )
}

@Suppress("LongMethod")
private val CSS_NAMED_COLORS = mapOf(
    "transparent" to Color.Transparent,
    "aliceblue" to Color(0xFFF0F8FF),
    "antiquewhite" to Color(0xFFFAEBD7),
    "aqua" to Color(0xFF00FFFF),
    "aquamarine" to Color(0xFF7FFFD4),
    "azure" to Color(0xFFF0FFFF),
    "beige" to Color(0xFFF5F5DC),
    "bisque" to Color(0xFFFFE4C4),
    "black" to Color.Black,
    "blanchedalmond" to Color(0xFFFFEBCD),
    "blue" to Color.Blue,
    "blueviolet" to Color(0xFF8A2BE2),
    "brown" to Color(0xFFA52A2A),
    "burlywood" to Color(0xFFDEB887),
    "cadetblue" to Color(0xFF5F9EA0),
    "chartreuse" to Color(0xFF7FFF00),
    "chocolate" to Color(0xFFD2691E),
    "coral" to Color(0xFFFF7F50),
    "cornflowerblue" to Color(0xFF6495ED),
    "cornsilk" to Color(0xFFFFF8DC),
    "crimson" to Color(0xFFDC143C),
    "cyan" to Color.Cyan,
    "darkblue" to Color(0xFF00008B),
    "darkcyan" to Color(0xFF008B8B),
    "darkgoldenrod" to Color(0xFFB8860B),
    "darkgray" to Color(0xFFA9A9A9),
    "darkgreen" to Color(0xFF006400),
    "darkgrey" to Color(0xFFA9A9A9),
    "darkkhaki" to Color(0xFFBDB76B),
    "darkmagenta" to Color(0xFF8B008B),
    "darkolivegreen" to Color(0xFF556B2F),
    "darkorange" to Color(0xFFFF8C00),
    "darkorchid" to Color(0xFF9932CC),
    "darkred" to Color(0xFF8B0000),
    "darksalmon" to Color(0xFFE9967A),
    "darkseagreen" to Color(0xFF8FBC8F),
    "darkslateblue" to Color(0xFF483D8B),
    "darkslategray" to Color(0xFF2F4F4F),
    "darkslategrey" to Color(0xFF2F4F4F),
    "darkturquoise" to Color(0xFF00CED1),
    "darkviolet" to Color(0xFF9400D3),
    "deeppink" to Color(0xFFFF1493),
    "deepskyblue" to Color(0xFF00BFFF),
    "dimgray" to Color(0xFF696969),
    "dimgrey" to Color(0xFF696969),
    "dodgerblue" to Color(0xFF1E90FF),
    "firebrick" to Color(0xFFB22222),
    "floralwhite" to Color(0xFFFFFAF0),
    "forestgreen" to Color(0xFF228B22),
    "fuchsia" to Color(0xFFFF00FF),
    "gainsboro" to Color(0xFFDCDCDC),
    "ghostwhite" to Color(0xFFF8F8FF),
    "gold" to Color(0xFFFFD700),
    "goldenrod" to Color(0xFFDAA520),
    "gray" to Color.Gray,
    "green" to Color(0xFF008000),
    "greenyellow" to Color(0xFFADFF2F),
    "grey" to Color.Gray,
    "honeydew" to Color(0xFFF0FFF0),
    "hotpink" to Color(0xFFFF69B4),
    "indianred" to Color(0xFFCD5C5C),
    "indigo" to Color(0xFF4B0082),
    "ivory" to Color(0xFFFFFFF0),
    "khaki" to Color(0xFFF0E68C),
    "lavender" to Color(0xFFE6E6FA),
    "lavenderblush" to Color(0xFFFFF0F5),
    "lawngreen" to Color(0xFF7CFC00),
    "lemonchiffon" to Color(0xFFFFFACD),
    "lightblue" to Color(0xFFADD8E6),
    "lightcoral" to Color(0xFFF08080),
    "lightcyan" to Color(0xFFE0FFFF),
    "lightgoldenrodyellow" to Color(0xFFFAFAD2),
    "lightgray" to Color(0xFFD3D3D3),
    "lightgreen" to Color(0xFF90EE90),
    "lightgrey" to Color(0xFFD3D3D3),
    "lightpink" to Color(0xFFFFB6C1),
    "lightsalmon" to Color(0xFFFFA07A),
    "lightseagreen" to Color(0xFF20B2AA),
    "lightskyblue" to Color(0xFF87CEFA),
    "lightslategray" to Color(0xFF778899),
    "lightslategrey" to Color(0xFF778899),
    "lightsteelblue" to Color(0xFFB0C4DE),
    "lightyellow" to Color(0xFFFFFFE0),
    "lime" to Color(0xFF00FF00),
    "limegreen" to Color(0xFF32CD32),
    "linen" to Color(0xFFFAF0E6),
    "magenta" to Color.Magenta,
    "maroon" to Color(0xFF800000),
    "mediumaquamarine" to Color(0xFF66CDAA),
    "mediumblue" to Color(0xFF0000CD),
    "mediumorchid" to Color(0xFFBA55D3),
    "mediumpurple" to Color(0xFF9370DB),
    "mediumseagreen" to Color(0xFF3CB371),
    "mediumslateblue" to Color(0xFF7B68EE),
    "mediumspringgreen" to Color(0xFF00FA9A),
    "mediumturquoise" to Color(0xFF48D1CC),
    "mediumvioletred" to Color(0xFFC71585),
    "midnightblue" to Color(0xFF191970),
    "mintcream" to Color(0xFFF5FFFA),
    "mistyrose" to Color(0xFFFFE4E1),
    "moccasin" to Color(0xFFFFE4B5),
    "navajowhite" to Color(0xFFFFDEAD),
    "navy" to Color(0xFF000080),
    "oldlace" to Color(0xFFFDF5E6),
    "olive" to Color(0xFF808000),
    "olivedrab" to Color(0xFF6B8E23),
    "orange" to Color(0xFFFFA500),
    "orangered" to Color(0xFFFF4500),
    "orchid" to Color(0xFFDA70D6),
    "palegoldenrod" to Color(0xFFEEE8AA),
    "palegreen" to Color(0xFF98FB98),
    "paleturquoise" to Color(0xFFAFEEEE),
    "palevioletred" to Color(0xFFDB7093),
    "papayawhip" to Color(0xFFFFEFD5),
    "peachpuff" to Color(0xFFFFDAB9),
    "peru" to Color(0xFFCD853F),
    "pink" to Color(0xFFFFC0CB),
    "plum" to Color(0xFFDDA0DD),
    "powderblue" to Color(0xFFB0E0E6),
    "purple" to Color(0xFF800080),
    "rebeccapurple" to Color(0xFF663399),
    "red" to Color.Red,
    "rosybrown" to Color(0xFFBC8F8F),
    "royalblue" to Color(0xFF4169E1),
    "saddlebrown" to Color(0xFF8B4513),
    "salmon" to Color(0xFFFA8072),
    "sandybrown" to Color(0xFFF4A460),
    "seagreen" to Color(0xFF2E8B57),
    "seashell" to Color(0xFFFFF5EE),
    "sienna" to Color(0xFFA0522D),
    "silver" to Color(0xFFC0C0C0),
    "skyblue" to Color(0xFF87CEEB),
    "slateblue" to Color(0xFF6A5ACD),
    "slategray" to Color(0xFF708090),
    "slategrey" to Color(0xFF708090),
    "snow" to Color(0xFFFFFAFA),
    "springgreen" to Color(0xFF00FF7F),
    "steelblue" to Color(0xFF4682B4),
    "tan" to Color(0xFFD2B48C),
    "teal" to Color(0xFF008080),
    "thistle" to Color(0xFFD8BFD8),
    "tomato" to Color(0xFFFF6347),
    "turquoise" to Color(0xFF40E0D0),
    "violet" to Color(0xFFEE82EE),
    "wheat" to Color(0xFFF5DEB3),
    "white" to Color.White,
    "whitesmoke" to Color(0xFFF5F5F5),
    "yellow" to Color.Yellow,
    "yellowgreen" to Color(0xFF9ACD32),
)
