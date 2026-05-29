package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedDrawCacheTest {
    @Test
    fun `stable keys reuse dash recipe`() {
        PreparedDrawCache.clearForTest()

        val first = PreparedDrawCache.dashRecipe(listOf(4f, 2f))
        val second = PreparedDrawCache.dashRecipe(listOf(4f, 2f))

        assertSame(first, second)
        assertTrue(PreparedDrawCache.stats().hits >= 1)
    }

    @Test
    fun `gradient recipe is reused by immutable signature`() {
        PreparedDrawCache.clearForTest()

        val first = PreparedDrawCache.gradientRecipe(listOf(1, 2), listOf(0f, 1f))
        val second = PreparedDrawCache.gradientRecipe(listOf(1, 2), listOf(0f, 1f))

        assertSame(first, second)
        assertEquals(listOf(1, 2), first.colors)
    }

    @Test
    fun `cache is bounded`() {
        PreparedDrawCache.clearForTest()

        repeat(160) { index -> PreparedDrawCache.dashRecipe(listOf(index + 1f, 2f)) }

        assertEquals(128, PreparedDrawCache.stats().maxEntries)
        assertTrue(PreparedDrawCache.stats().size <= 128)
        assertTrue(PreparedDrawCache.stats().evictions > 0)
    }
}
