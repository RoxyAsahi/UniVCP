package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Rich HTML may expose horizontal scrolling for wide tables, math, code, or CSS overflow.
 * Compose horizontalScroll must not be measured under another horizontalScroll with infinite width.
 */
internal val LocalRichHorizontalScrollAncestor = staticCompositionLocalOf { false }
