package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

internal data class RichHtmlRenderModel(
    val id: String,
    val blocks: List<RichBlock>,
    val unsupported: List<RichUnsupportedReason> = emptyList(),
    val visualHints: List<RichVisualHint> = emptyList(),
    val animationStats: RichAnimationStats = RichAnimationStats.Empty,
)

internal enum class RichVisualHint {
    BackgroundExtraLayer,
    CssFilter,
    CssBackdropFilter,
    CssBackgroundClipText,
    CssMixBlendMode,
    CssMask,
    CssClipPath,
    CssUnsupportedColor,
    CssAnimation,
    CssTransition,
    CssKeyframes,
    CssInteractivePseudoClass,
    CssInfiniteAnimation,
    CssLayoutAnimation,
    AnimationDependentVisibility,
    AnimationBudgetExceeded,
    TableComplexSpan,
    SvgClipPath,
    SvgMask,
    SvgFilter,
    SvgUse,
    SvgSymbol,
    SvgPattern,
    SvgForeignObject,
    SvgMarker,
}

internal data class RichAnimationStats(
    val strategy: RichAnimationStrategy = RichAnimationStrategy.None,
    val animatedElementCount: Int = 0,
    val nativeAnimatedCount: Int = 0,
    val staticizedCount: Int = 0,
    val infiniteCount: Int = 0,
    val layoutAnimationCount: Int = 0,
    val transitionCount: Int = 0,
    val dependentVisibilityCount: Int = 0,
    val budgetExceededCount: Int = 0,
) {
    companion object {
        val Empty = RichAnimationStats()
    }
}

internal enum class RichAnimationStrategy {
    None,
    Staticized,
    NativeAnimated,
    BudgetExceededStaticized,
}

internal sealed interface RichBlock {
    val blockId: String
    val style: ComputedStyle
}

internal data class RichTextBlock(
    override val blockId: String,
    override val style: ComputedStyle,
    val content: AnnotatedString,
    val inlineMath: List<InlineMathRun> = emptyList(),
    val listMarker: String? = null,
) : RichBlock

internal data class RichContainerBlock(
    override val blockId: String,
    override val style: ComputedStyle,
    val children: List<RichBlock>,
    val tagName: String = "div",
) : RichBlock

internal data class RichImageBlock(
    override val blockId: String,
    override val style: ComputedStyle,
    val src: String,
    val alt: String?,
) : RichBlock

internal data class RichTableBlock(
    override val blockId: String,
    override val style: ComputedStyle,
    val headers: List<RichTableCell>,
    val rows: List<List<RichTableCell>>,
    val caption: AnnotatedString? = null,
    val captionStyle: ComputedStyle? = null,
    val sections: List<RichTableSection> = emptyList(),
) : RichBlock

internal data class RichTableSection(
    val type: RichTableSectionType,
    val rows: List<List<RichTableCell>>,
)

internal enum class RichTableSectionType {
    Head,
    Body,
    Foot,
}

internal data class RichTableCell(
    val content: AnnotatedString,
    val style: ComputedStyle,
    val colspan: Int = 1,
    val rowspan: Int = 1,
    val isHeader: Boolean = false,
)

internal data class RichSvgBlock(
    override val blockId: String,
    override val style: ComputedStyle,
    val model: RichSvgModel,
) : RichBlock

internal data class RichMathBlock(
    override val blockId: String,
    override val style: ComputedStyle,
    val latex: String,
    val inline: Boolean = false,
) : RichBlock

internal data class RichButtonBlock(
    override val blockId: String,
    override val style: ComputedStyle,
    val label: AnnotatedString,
    val action: String,
) : RichBlock

internal data class RichDetailsBlock(
    override val blockId: String,
    override val style: ComputedStyle,
    val summary: AnnotatedString,
    val children: List<RichBlock>,
    val open: Boolean,
) : RichBlock

internal data class RichUnsupportedBlock(
    override val blockId: String,
    override val style: ComputedStyle,
    val reason: RichUnsupportedReason,
    val previewText: String,
) : RichBlock

internal enum class RichUnsupportedReason {
    UnsafeHtml,
    DynamicRuntime,
    SvgTooComplex,
    Unknown,
}

internal data class InlineMathRun(
    val start: Int,
    val end: Int,
    val latex: String,
)

internal data class ComputedStyle(
    val display: RichDisplay = RichDisplay.Block,
    val position: RichPosition = RichPosition.Static,
    val visibility: RichVisibility = RichVisibility.Visible,
    val color: Color? = null,
    val declaredColor: RichCssColor? = null,
    val backgroundColor: Color? = null,
    val declaredBackgroundColor: RichCssColor? = null,
    val backgroundImage: RichBackgroundImage? = null,
    val backgroundUrl: String? = null,
    val backgroundSize: RichBackgroundSize = RichBackgroundSize.Auto,
    val backgroundPosition: RichBackgroundPosition = RichBackgroundPosition.Center,
    val backgroundRepeat: RichBackgroundRepeat = RichBackgroundRepeat.Repeat,
    val backgroundOrigin: RichBackgroundBox = RichBackgroundBox.PaddingBox,
    val backgroundClip: RichBackgroundBox = RichBackgroundBox.BorderBox,
    val extraBackgroundLayers: Int = 0,
    val objectFit: RichObjectFit = RichObjectFit.Contain,
    val opacity: Float = 1f,
    val animation: RichAnimationStyle = RichAnimationStyle.None,
    val transition: RichTransitionStyle = RichTransitionStyle.None,
    val cssFilter: RichCssFilter = RichCssFilter.None,
    val backdropFilter: RichCssFilter = RichCssFilter.None,
    val padding: RichSpacing = RichSpacing.Zero,
    val margin: RichSpacing = RichSpacing.Zero,
    val border: RichBorder = RichBorder.None,
    val borderRadius: RichCornerRadius = RichCornerRadius.Zero,
    val shadows: List<RichShadow> = emptyList(),
    val width: RichSize = RichSize.Auto,
    val height: RichSize = RichSize.Auto,
    val minWidth: Dp? = null,
    val maxWidth: Dp? = null,
    val minHeight: Dp? = null,
    val maxHeight: Dp? = null,
    val gap: Dp = 0.dp,
    val rowGap: Dp = 0.dp,
    val columnGap: Dp = 0.dp,
    val flexDirection: RichFlexDirection = RichFlexDirection.Row,
    val flexWrap: RichFlexWrap = RichFlexWrap.NoWrap,
    val justifyContent: RichJustify = RichJustify.Start,
    val alignItems: RichAlign = RichAlign.Stretch,
    val alignSelf: RichAlign? = null,
    val flexGrow: Float = 0f,
    val flexShrink: Float = 1f,
    val flexBasis: RichSize = RichSize.Auto,
    val gridColumns: RichGridColumns = RichGridColumns.Auto,
    val order: Int = 0,
    val alignContent: RichAlignContent = RichAlignContent.Start,
    val gridColumnSpan: Int = 1,
    val gridRowSpan: Int = 1,
    val zIndex: Float = 0f,
    val offset: RichOffset = RichOffset.Zero,
    val transform: RichTransform = RichTransform.None,
    val overflow: RichOverflow = RichOverflow.Visible,
    val fontSize: TextUnit = TextUnit.Unspecified,
    val fontWeight: FontWeight? = null,
    val fontStyle: FontStyle? = null,
    val fontFamily: FontFamily? = null,
    val lineHeight: TextUnit = TextUnit.Unspecified,
    val lineHeightMultiplier: Float? = null,
    val textAlign: TextAlign = TextAlign.Unspecified,
    val letterSpacing: TextUnit = TextUnit.Unspecified,
    val textDecoration: TextDecoration? = null,
    val textShadow: RichTextShadow? = null,
    val textTransform: RichTextTransform = RichTextTransform.None,
    val verticalAlign: RichVerticalAlign = RichVerticalAlign.Baseline,
    val fontVariantNumeric: String? = null,
    val whiteSpace: RichWhiteSpace = RichWhiteSpace.Normal,
    val wordBreak: RichWordBreak = RichWordBreak.Normal,
    val textOverflow: RichTextOverflow = RichTextOverflow.Clip,
    val listStyleType: RichListStyleType = RichListStyleType.Default,
    val listStylePosition: RichListStylePosition = RichListStylePosition.Outside,
    val listStyleImage: String? = null,
    val borderCollapse: RichBorderCollapse = RichBorderCollapse.Separate,
    val captionSide: RichCaptionSide = RichCaptionSide.Top,
    val beforeContent: String? = null,
    val afterContent: String? = null,
    val cursorPointer: Boolean = false,
) {
    fun textSpan(): SpanStyle {
        var span = SpanStyle()
        val effectiveOpacity = effectiveOpacity()
        color?.let { span = span.merge(SpanStyle(color = it.copy(alpha = it.alpha * effectiveOpacity))) }
        backgroundColor?.let { span = span.merge(SpanStyle(background = it.copy(alpha = it.alpha * effectiveOpacity))) }
        if (fontSize != TextUnit.Unspecified) span = span.merge(SpanStyle(fontSize = fontSize))
        fontWeight?.let { span = span.merge(SpanStyle(fontWeight = it)) }
        fontStyle?.let { span = span.merge(SpanStyle(fontStyle = it)) }
        fontFamily?.let { span = span.merge(SpanStyle(fontFamily = it)) }
        if (letterSpacing != TextUnit.Unspecified) span = span.merge(SpanStyle(letterSpacing = letterSpacing))
        textDecoration?.let { span = span.merge(SpanStyle(textDecoration = it)) }
        textShadow?.let { span = span.merge(SpanStyle(shadow = it.toShadow())) }
        verticalAlign.toBaselineShift()?.let { span = span.merge(SpanStyle(baselineShift = it)) }
        return span
    }

    companion object {
        val Initial = ComputedStyle()
    }
}

internal fun ComputedStyle.effectiveOpacity(): Float {
    return (opacity * (cssFilter.opacity ?: 1f)).coerceIn(0f, 1f)
}

internal fun RichCssFilter.hasLowCostColorEffect(): Boolean {
    return brightness != null || grayscale != null
}

internal enum class RichDisplay {
    None,
    Block,
    Inline,
    InlineBlock,
    Flex,
    Grid,
}

internal enum class RichPosition {
    Static,
    Relative,
    Absolute,
    Fixed,
    Sticky,
}

internal enum class RichVisibility {
    Visible,
    Hidden,
}

internal enum class RichFlexDirection {
    Row,
    RowReverse,
    Column,
    ColumnReverse,
}

internal enum class RichFlexWrap {
    NoWrap,
    Wrap,
    WrapReverse,
}

internal enum class RichJustify {
    Start,
    Center,
    End,
    SpaceBetween,
    SpaceAround,
    SpaceEvenly,
}

internal enum class RichAlign {
    Start,
    Center,
    End,
    Stretch,
    Baseline,
}

internal enum class RichAlignContent {
    Start,
    Center,
    End,
    SpaceBetween,
    SpaceAround,
    SpaceEvenly,
    Stretch,
}

internal enum class RichOverflow {
    Visible,
    Hidden,
    Scroll,
    Auto,
}

internal enum class RichWhiteSpace {
    Normal,
    NoWrap,
    Pre,
    PreWrap,
    PreLine,
}

internal enum class RichWordBreak {
    Normal,
    BreakAll,
    BreakWord,
}

internal enum class RichTextOverflow {
    Clip,
    Ellipsis,
}

internal enum class RichTextTransform {
    None,
    Uppercase,
    Lowercase,
    Capitalize,
}

internal enum class RichVerticalAlign {
    Baseline,
    Middle,
    Top,
    Bottom,
    Sub,
    Super,
}

private fun RichVerticalAlign.toBaselineShift(): BaselineShift? = when (this) {
    RichVerticalAlign.Baseline -> null
    RichVerticalAlign.Middle -> BaselineShift(0.12f)
    RichVerticalAlign.Top -> BaselineShift(0.35f)
    RichVerticalAlign.Bottom -> BaselineShift(-0.25f)
    RichVerticalAlign.Sub -> BaselineShift.Subscript
    RichVerticalAlign.Super -> BaselineShift.Superscript
}

internal enum class RichObjectFit {
    Contain,
    Cover,
    Fill,
    None,
}

internal data class RichAnimationStyle(
    val names: List<String> = emptyList(),
    val durationMs: Int = 0,
    val delayMs: Int = 0,
    val iterationCount: Float = 1f,
    val fillModeForwards: Boolean = false,
    val hasLayoutProperty: Boolean = false,
    val hasOpacityOrTransform: Boolean = false,
    val nativeAnimation: RichNativeAnimation? = null,
) {
    val isDeclared: Boolean
        get() = names.isNotEmpty()

    val isInfinite: Boolean
        get() = iterationCount.isInfinite()

    val mayHideStaticContent: Boolean
        get() = isDeclared && fillModeForwards && hasOpacityOrTransform

    companion object {
        val None = RichAnimationStyle()
    }
}

internal data class RichNativeAnimation(
    val fromOpacity: Float? = null,
    val toOpacity: Float? = null,
    val fromTransform: RichTransform = RichTransform.None,
    val toTransform: RichTransform = RichTransform.None,
    val durationMs: Int,
    val delayMs: Int,
)

internal data class RichTransitionStyle(
    val properties: List<String> = emptyList(),
    val durationMs: Int = 0,
) {
    val isDeclared: Boolean
        get() = properties.isNotEmpty()

    companion object {
        val None = RichTransitionStyle()
    }
}

internal data class RichCssFilter(
    val blurRadius: Dp? = null,
    val brightness: Float? = null,
    val opacity: Float? = null,
    val grayscale: Float? = null,
    val unsupportedFunctions: Int = 0,
) {
    val hasSupportedEffect: Boolean
        get() = blurRadius != null || brightness != null || opacity != null || grayscale != null

    val requiresVisualApproximation: Boolean
        get() = blurRadius != null || brightness != null || grayscale != null || unsupportedFunctions > 0

    companion object {
        val None = RichCssFilter()
    }
}

internal enum class RichBackgroundRepeat {
    Repeat,
    NoRepeat,
    RepeatX,
    RepeatY,
    Round,
    Space,
}

internal enum class RichBackgroundBox {
    BorderBox,
    PaddingBox,
    ContentBox,
    Text,
}

internal data class RichBackgroundPosition(
    val xFraction: Float,
    val yFraction: Float,
    val xOffset: Dp = 0.dp,
    val yOffset: Dp = 0.dp,
) {
    companion object {
        val Center = RichBackgroundPosition(0.5f, 0.5f)
    }
}

internal sealed interface RichBackgroundSize {
    data object Auto : RichBackgroundSize
    data object Cover : RichBackgroundSize
    data object Contain : RichBackgroundSize
    data class Explicit(
        val width: RichSize = RichSize.Auto,
        val height: RichSize = RichSize.Auto,
    ) : RichBackgroundSize
}

internal enum class RichListStyleType {
    Default,
    Disc,
    Circle,
    Square,
    Decimal,
    LowerAlpha,
    UpperAlpha,
    None,
}

internal enum class RichListStylePosition {
    Inside,
    Outside,
}

internal enum class RichBorderCollapse {
    Separate,
    Collapse,
}

internal enum class RichCaptionSide {
    Top,
    Bottom,
}

internal sealed interface RichSize {
    data object Auto : RichSize
    data class DpSize(val value: Dp) : RichSize
    data class Fraction(val value: Float) : RichSize
}

internal sealed interface RichGridColumns {
    data object Auto : RichGridColumns
    data class Count(val count: Int) : RichGridColumns
    data class AutoFit(val minColumnWidth: Dp) : RichGridColumns
}

internal data class RichSpacing(
    val top: Dp,
    val right: Dp,
    val bottom: Dp,
    val left: Dp,
) {
    companion object {
        val Zero = all(0.dp)
        fun all(value: Dp) = RichSpacing(value, value, value, value)
    }
}

internal data class RichCornerRadius(
    val topStart: Dp,
    val topEnd: Dp,
    val bottomEnd: Dp,
    val bottomStart: Dp,
) {
    companion object {
        val Zero = all(0.dp)
        fun all(value: Dp) = RichCornerRadius(value, value, value, value)
    }
}

internal data class RichBorder(
    val top: RichBorderSide,
    val right: RichBorderSide,
    val bottom: RichBorderSide,
    val left: RichBorderSide,
) {
    companion object {
        val None = all(RichBorderSide.None)
        fun all(side: RichBorderSide) = RichBorder(side, side, side, side)
    }
}

internal data class RichBorderSide(
    val width: Dp,
    val color: Color,
    val style: RichBorderStyle,
) {
    companion object {
        val None = RichBorderSide(0.dp, Color.Transparent, RichBorderStyle.None)
    }
}

internal enum class RichBorderStyle {
    None,
    Solid,
    Dashed,
    Dotted,
    Double,
}

internal data class RichShadow(
    val offsetX: Dp,
    val offsetY: Dp,
    val blurRadius: Dp,
    val spread: Dp,
    val color: Color,
    val inset: Boolean = false,
)

internal data class RichTextShadow(
    val offsetX: Float,
    val offsetY: Float,
    val blurRadius: Float,
    val color: Color,
) {
    fun toShadow(): androidx.compose.ui.graphics.Shadow = androidx.compose.ui.graphics.Shadow(
        color = color,
        offset = androidx.compose.ui.geometry.Offset(offsetX, offsetY),
        blurRadius = blurRadius,
    )
}

internal data class RichOffset(
    val left: Dp? = null,
    val top: Dp? = null,
    val right: Dp? = null,
    val bottom: Dp? = null,
) {
    companion object {
        val Zero = RichOffset()
    }
}

internal data class RichTransform(
    val translateX: Dp = 0.dp,
    val translateY: Dp = 0.dp,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotateZ: Float = 0f,
    val skewX: Float = 0f,
    val skewY: Float = 0f,
) {
    companion object {
        val None = RichTransform()
    }
}

internal data class RichColorStop(
    val color: Color,
    val offset: Float? = null,
)

internal sealed interface RichBackgroundImage {
    data class LinearGradient(
        val angleDegrees: Float,
        val stops: List<RichColorStop>,
    ) : RichBackgroundImage

    data class RadialGradient(
        val stops: List<RichColorStop>,
    ) : RichBackgroundImage

    data class ConicGradient(
        val stops: List<RichColorStop>,
    ) : RichBackgroundImage
}

internal data class RichSvgModel(
    val width: Dp,
    val height: Dp,
    val viewBox: RichSvgViewBox,
    val commands: List<RichSvgCommand>,
    val visualHints: List<RichVisualHint> = emptyList(),
)

internal data class RichSvgViewBox(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

internal sealed interface RichSvgCommand {
    data class Path(
        val d: String,
        val fill: RichSvgPaint?,
        val stroke: RichSvgPaint?,
        val strokeWidth: Float,
        val strokeLineCap: RichSvgLineCap = RichSvgLineCap.Butt,
        val strokeLineJoin: RichSvgLineJoin = RichSvgLineJoin.Miter,
        val strokeDashArray: List<Float> = emptyList(),
        val transform: RichSvgTransform = RichSvgTransform.None,
    ) : RichSvgCommand

    data class Rect(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val rx: Float,
        val ry: Float,
        val fill: RichSvgPaint?,
        val stroke: RichSvgPaint?,
        val strokeWidth: Float,
        val strokeLineCap: RichSvgLineCap = RichSvgLineCap.Butt,
        val strokeLineJoin: RichSvgLineJoin = RichSvgLineJoin.Miter,
        val strokeDashArray: List<Float> = emptyList(),
        val transform: RichSvgTransform = RichSvgTransform.None,
    ) : RichSvgCommand

    data class Circle(
        val cx: Float,
        val cy: Float,
        val r: Float,
        val fill: RichSvgPaint?,
        val stroke: RichSvgPaint?,
        val strokeWidth: Float,
        val strokeLineCap: RichSvgLineCap = RichSvgLineCap.Butt,
        val strokeLineJoin: RichSvgLineJoin = RichSvgLineJoin.Miter,
        val strokeDashArray: List<Float> = emptyList(),
        val transform: RichSvgTransform = RichSvgTransform.None,
    ) : RichSvgCommand

    data class Ellipse(
        val cx: Float,
        val cy: Float,
        val rx: Float,
        val ry: Float,
        val fill: RichSvgPaint?,
        val stroke: RichSvgPaint?,
        val strokeWidth: Float,
        val strokeLineCap: RichSvgLineCap = RichSvgLineCap.Butt,
        val strokeLineJoin: RichSvgLineJoin = RichSvgLineJoin.Miter,
        val strokeDashArray: List<Float> = emptyList(),
        val transform: RichSvgTransform = RichSvgTransform.None,
    ) : RichSvgCommand

    data class Line(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val stroke: RichSvgPaint?,
        val strokeWidth: Float,
        val strokeLineCap: RichSvgLineCap = RichSvgLineCap.Butt,
        val strokeLineJoin: RichSvgLineJoin = RichSvgLineJoin.Miter,
        val strokeDashArray: List<Float> = emptyList(),
        val transform: RichSvgTransform = RichSvgTransform.None,
    ) : RichSvgCommand

    data class Polyline(
        val points: List<androidx.compose.ui.geometry.Offset>,
        val fill: RichSvgPaint?,
        val stroke: RichSvgPaint?,
        val strokeWidth: Float,
        val closed: Boolean,
        val strokeLineCap: RichSvgLineCap = RichSvgLineCap.Butt,
        val strokeLineJoin: RichSvgLineJoin = RichSvgLineJoin.Miter,
        val strokeDashArray: List<Float> = emptyList(),
        val transform: RichSvgTransform = RichSvgTransform.None,
    ) : RichSvgCommand

    data class Text(
        val x: Float,
        val y: Float,
        val text: String,
        val fill: RichSvgPaint?,
        val fontSize: Float,
        val fontWeight: Int? = null,
        val textAnchor: RichSvgTextAnchor = RichSvgTextAnchor.Start,
        val transform: RichSvgTransform = RichSvgTransform.None,
    ) : RichSvgCommand
}

internal enum class RichSvgLineCap {
    Butt,
    Round,
    Square,
}

internal enum class RichSvgLineJoin {
    Miter,
    Round,
    Bevel,
}

internal enum class RichSvgTextAnchor {
    Start,
    Middle,
    End,
}

internal sealed interface RichSvgPaint {
    data class Solid(val color: Color) : RichSvgPaint
    data class LinearGradient(
        val id: String,
        val stops: List<RichColorStop>,
    ) : RichSvgPaint
    data class RadialGradient(
        val id: String,
        val stops: List<RichColorStop>,
    ) : RichSvgPaint
}

internal data class RichSvgTransform(
    val translateX: Float = 0f,
    val translateY: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotateDegrees: Float = 0f,
) {
    companion object {
        val None = RichSvgTransform()
    }
}
