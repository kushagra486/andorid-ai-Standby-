package com.nova.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutsTest {
    private fun layout(cols: Int, vararg sizes: TileSize) =
        Layout(1, "t", cols, false, sizes.mapIndexed { i, s -> Tile(i.toLong(), s, listOf(WidgetType.CLOCK)) })

    @Test fun `tiles never overlap and stay in the grid`() {
        val r = GridPacker.pack(layout(4, TileSize.LARGE, TileSize.MEDIUM, TileSize.SMALL, TileSize.SMALL), fill = false)
        assertTrue(r.overflow.isEmpty())
        val cells = HashSet<Pair<Int, Int>>()
        r.placements.forEach { p ->
            for (x in p.col until p.col + p.colSpan) for (y in p.row until p.row + p.rowSpan) {
                assertTrue("overlap at $x,$y", cells.add(x to y)); assertTrue(x < 4 && y < 2)
            }
        }
        assertEquals(8, cells.size)
    }

    @Test fun `overflow is reported`() {
        val r = GridPacker.pack(layout(2, TileSize.LARGE, TileSize.SMALL))
        assertEquals(1, r.overflow.size)
    }

    @Test fun `fill grows tiles into empty cells`() {
        val r = GridPacker.pack(layout(4, TileSize.SMALL, TileSize.SMALL), fill = true)
        val covered = r.placements.sumOf { it.colSpan * it.rowSpan }
        assertEquals(8, covered)
    }

    @Test fun `full size takes the whole grid`() {
        val r = GridPacker.pack(layout(3, TileSize.FULL))
        assertEquals(3, r.placements[0].colSpan); assertEquals(2, r.placements[0].rowSpan)
    }

    @Test fun `layout book round-trips through json`() {
        val book = LayoutBook.defaults().duplicate(1, 99)
        val back = LayoutBook.fromJson(book.toJson())
        assertEquals(book.layouts, back.layouts)
        assertEquals(book.activeId, back.activeId)
    }

    @Test fun `unknown widgets from a future schema are skipped, not fatal`() {
        val json = """{"schema":7,"active":1,"layouts":[{"id":1,"name":"X","columns":4,"tiles":[
            {"id":1,"size":"SMALL","stack":["CLOCK","HOLOGRAM"]},{"id":2,"size":"GIANT","stack":["WEATHER"]},{"id":3,"stack":["NOPE"]}]}]}"""
        val b = LayoutBook.fromJson(json)
        assertEquals(2, b.active.tiles.size)
        assertEquals(listOf(WidgetType.CLOCK), b.active.tiles[0].stack)
        assertEquals(TileSize.SMALL, b.active.tiles[1].size)
    }

    @Test fun `corrupt json falls back to defaults`() {
        assertEquals(LayoutBook.defaults().layouts.size, LayoutBook.fromJson("{oops").layouts.size)
    }

    @Test fun `remove and duplicate`() {
        val b = LayoutBook.defaults()
        val d = b.duplicate(1, 42)
        assertTrue(d.layouts.any { it.id == 42L && it.name.endsWith("copy") && !it.locked })
        val r = d.remove(1)
        assertTrue(r.layouts.none { it.id == 1L })
        assertTrue(r.layouts.any { it.id == r.activeId })
    }
}
