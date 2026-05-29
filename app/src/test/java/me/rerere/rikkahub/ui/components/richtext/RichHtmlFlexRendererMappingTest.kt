package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.layout.ExperimentalFlexBoxApi
import androidx.compose.foundation.layout.FlexAlignContent
import androidx.compose.foundation.layout.FlexAlignItems
import androidx.compose.foundation.layout.FlexAlignSelf
import androidx.compose.foundation.layout.FlexDirection
import androidx.compose.foundation.layout.FlexJustifyContent
import androidx.compose.foundation.layout.FlexWrap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalFlexBoxApi::class)
class RichHtmlFlexRendererMappingTest {
    @Test
    fun `renderer maps reverse direction and wrap reverse to compose flexbox`() {
        assertEquals(FlexDirection.RowReverse, RichFlexDirection.RowReverse.toFlexDirection())
        assertEquals(FlexDirection.ColumnReverse, RichFlexDirection.ColumnReverse.toFlexDirection())
        assertEquals(
            FlexWrap.WrapReverse,
            ComputedStyle.Initial.copy(flexWrap = RichFlexWrap.WrapReverse).toFlexWrap(),
        )
    }

    @Test
    fun `renderer maps space evenly and baseline flex alignment`() {
        val style = ComputedStyle.Initial.copy(
            justifyContent = RichJustify.SpaceEvenly,
            alignItems = RichAlign.Baseline,
            alignContent = RichAlignContent.SpaceEvenly,
        )

        assertEquals(FlexJustifyContent.SpaceEvenly, style.toFlexJustifyContent())
        assertEquals(FlexAlignItems.Baseline, style.toFlexAlignItems())
        assertEquals(FlexAlignSelf.Baseline, RichAlign.Baseline.toFlexAlignSelf())
        assertEquals(FlexAlignContent.SpaceAround, style.toFlexAlignContent())
    }

    @Test
    fun `single centered flex text child receives text alignment compensation`() {
        val parent = ComputedStyle.Initial.copy(
            display = RichDisplay.InlineFlex,
            justifyContent = RichJustify.Center,
            alignItems = RichAlign.Center,
        )
        val child = RichTextBlock(
            blockId = "check",
            style = ComputedStyle.Initial.copy(display = RichDisplay.Inline),
            content = AnnotatedString("✓"),
        )

        assertEquals(TextAlign.Center, parent.flexTextAlignOverride(child, childCount = 1))
        assertNull(parent.flexTextAlignOverride(child.copy(style = child.style.copy(width = RichSize.DpSize(24.dp))), 1))
        assertNull(parent.flexTextAlignOverride(child, childCount = 2))
    }
}
