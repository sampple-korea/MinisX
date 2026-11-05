package com.minisx.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelFavoritesTest {
    @Test fun `a new favourite goes to the front and a second tap removes it`() {
        val one = ModelFavorites.toggled(emptyList(), "a")
        val two = ModelFavorites.toggled(one, "b")
        assertEquals(listOf("b", "a"), two)
        assertEquals(listOf("a"), ModelFavorites.toggled(two, "b"))
    }

    @Test fun `the list is capped`() {
        var ids = emptyList<String>()
        for (i in 1..(ModelFavorites.MAX + 5)) ids = ModelFavorites.toggled(ids, "m$i")
        assertEquals(ModelFavorites.MAX, ids.size)
        assertEquals("m${ModelFavorites.MAX + 5}", ids.first())
    }
}
