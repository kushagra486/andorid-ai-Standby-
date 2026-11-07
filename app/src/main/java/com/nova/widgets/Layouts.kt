package com.nova.widgets

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** A tile shows one widget, or a stack of widgets cycled vertically / on a timer. */
data class Tile(val id: Long, val size: TileSize, val stack: List<WidgetType>, val rotateSec: Int = 0) {
    val primary: WidgetType get() = stack.first()
}

/** When a layout should be picked automatically (optional context switching). */
enum class LayoutContext(val label: String) {
    NONE("Manual only"), MORNING("Mornings (6–11)"), WORK("Weekdays 9–17"), EVENING("Evenings (17–22)"), NIGHT("Night"), MUSIC("While music plays");
}

data class Layout(
    val id: Long, val name: String, val columns: Int, val locked: Boolean,
    val tiles: List<Tile>, val context: LayoutContext = LayoutContext.NONE,
)

data class Placement(val tile: Tile, val col: Int, val row: Int, val colSpan: Int, val rowSpan: Int)
data class PackResult(val placements: List<Placement>, val overflow: List<Tile>)

/**
 * Responsive grid placement (spec v1.1 §14.5): tiles are placed first-fit in order on a
 * `columns × rows` grid, never overlapping. Tiles that do not fit are reported as overflow.
 * With [fill], tiles grow into empty neighbouring cells so no large gaps remain.
 */
object GridPacker {
    fun spans(size: TileSize, columns: Int, rows: Int): Pair<Int, Int> =
        if (size == TileSize.FULL) columns to rows else minOf(size.cols, columns) to minOf(size.rows, rows)

    fun pack(layout: Layout, rows: Int = 2, fill: Boolean = true): PackResult {
        val cols = layout.columns.coerceIn(1, 6)
        val used = Array(rows) { BooleanArray(cols) }
        val placed = ArrayList<Placement>()
        val overflow = ArrayList<Tile>()
        for (tile in layout.tiles) {
            val (cs, rs) = spans(tile.size, cols, rows)
            var spot: Pair<Int, Int>? = null
            loop@ for (r in 0..rows - rs) for (c in 0..cols - cs) {
                if (free(used, c, r, cs, rs)) { spot = c to r; break@loop }
            }
            if (spot == null) { overflow += tile; continue }
            mark(used, spot.first, spot.second, cs, rs)
            placed += Placement(tile, spot.first, spot.second, cs, rs)
        }
        if (fill && placed.isNotEmpty()) {
            for (i in placed.indices) {
                var p = placed[i]
                // Grow right, then down, while the new cells are empty.
                while (p.col + p.colSpan < cols && free(used, p.col + p.colSpan, p.row, 1, p.rowSpan)) {
                    mark(used, p.col + p.colSpan, p.row, 1, p.rowSpan); p = p.copy(colSpan = p.colSpan + 1)
                }
                while (p.row + p.rowSpan < rows && free(used, p.col, p.row + p.rowSpan, p.colSpan, 1)) {
                    mark(used, p.col, p.row + p.rowSpan, p.colSpan, 1); p = p.copy(rowSpan = p.rowSpan + 1)
                }
                placed[i] = p
            }
        }
        return PackResult(placed, overflow)
    }

    private fun free(used: Array<BooleanArray>, c: Int, r: Int, cs: Int, rs: Int): Boolean {
        for (y in r until r + rs) for (x in c until c + cs) {
            if (y >= used.size || x >= used[0].size || used[y][x]) return false
        }
        return true
    }

    private fun mark(used: Array<BooleanArray>, c: Int, r: Int, cs: Int, rs: Int) {
        for (y in r until r + rs) for (x in c until c + cs) used[y][x] = true
    }
}

/** Saved layouts, persisted as versioned JSON so future widgets never break old data. */
class LayoutBook(val layouts: List<Layout>, val activeId: Long, val autoContext: Boolean) {

    val active: Layout get() = layouts.firstOrNull { it.id == activeId } ?: layouts.first()

    fun copy(layouts: List<Layout> = this.layouts, activeId: Long = this.activeId, autoContext: Boolean = this.autoContext) =
        LayoutBook(layouts.ifEmpty { defaults().layouts }, activeId, autoContext)

    fun update(layout: Layout) = copy(layouts = layouts.map { if (it.id == layout.id) layout else it })
    fun remove(id: Long): LayoutBook {
        val rest = layouts.filter { it.id != id }
        return copy(layouts = rest, activeId = if (activeId == id) (rest.firstOrNull()?.id ?: 0) else activeId)
    }
    fun duplicate(id: Long, newId: Long): LayoutBook {
        val src = layouts.firstOrNull { it.id == id } ?: return this
        val dup = src.copy(id = newId, name = src.name + " copy", locked = false,
            tiles = src.tiles.mapIndexed { i, t -> t.copy(id = newId * 100 + i) })
        return copy(layouts = layouts + dup)
    }

    /** Layout matching the current context, if context switching is on. */
    fun forContext(cal: Calendar, musicPlaying: Boolean, night: Boolean): Layout? {
        if (!autoContext) return null
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val weekday = cal.get(Calendar.DAY_OF_WEEK) in Calendar.MONDAY..Calendar.FRIDAY
        fun find(c: LayoutContext) = layouts.firstOrNull { it.context == c }
        return when {
            night && find(LayoutContext.NIGHT) != null -> find(LayoutContext.NIGHT)
            musicPlaying && find(LayoutContext.MUSIC) != null -> find(LayoutContext.MUSIC)
            h in 6..10 && find(LayoutContext.MORNING) != null -> find(LayoutContext.MORNING)
            weekday && h in 9..16 && find(LayoutContext.WORK) != null -> find(LayoutContext.WORK)
            h in 17..21 && find(LayoutContext.EVENING) != null -> find(LayoutContext.EVENING)
            else -> null
        }
    }

    fun toJson(): String = JSONObject().apply {
        put("schema", SCHEMA)
        put("active", activeId)
        put("autoContext", autoContext)
        put("layouts", JSONArray().apply {
            layouts.forEach { l ->
                put(JSONObject().apply {
                    put("id", l.id); put("name", l.name); put("columns", l.columns); put("locked", l.locked)
                    put("context", l.context.name)
                    put("tiles", JSONArray().apply {
                        l.tiles.forEach { t ->
                            put(JSONObject().apply {
                                put("id", t.id); put("size", t.size.name); put("rotate", t.rotateSec)
                                put("stack", JSONArray().apply { t.stack.forEach { put(it.name) } })
                            })
                        }
                    })
                })
            }
        })
    }.toString()

    companion object {
        const val SCHEMA = 1

        fun fromJson(json: String): LayoutBook {
            if (json.isBlank()) return defaults()
            return runCatching {
                val o = JSONObject(json)
                // Future schemas stay readable: unknown fields are ignored, unknown widgets skipped.
                val arr = o.getJSONArray("layouts")
                val layouts = (0 until arr.length()).map { i ->
                    val l = arr.getJSONObject(i)
                    val tilesArr = l.optJSONArray("tiles") ?: JSONArray()
                    val tiles = (0 until tilesArr.length()).mapNotNull { j ->
                        val t = tilesArr.getJSONObject(j)
                        val st = t.optJSONArray("stack") ?: JSONArray()
                        val stack = (0 until st.length()).mapNotNull { k -> WidgetType.byName(st.getString(k)) }
                        if (stack.isEmpty()) null else Tile(
                            t.getLong("id"),
                            TileSize.entries.firstOrNull { it.name == t.optString("size") } ?: TileSize.SMALL,
                            stack, t.optInt("rotate", 0),
                        )
                    }
                    Layout(
                        l.getLong("id"), l.optString("name", "Layout"), l.optInt("columns", 4).coerceIn(2, 4),
                        l.optBoolean("locked", false), tiles,
                        LayoutContext.entries.firstOrNull { it.name == l.optString("context") } ?: LayoutContext.NONE,
                    )
                }.filter { it.tiles.isNotEmpty() || true }
                if (layouts.isEmpty()) defaults() else LayoutBook(layouts, o.optLong("active", layouts.first().id), o.optBoolean("autoContext", false))
            }.getOrElse { defaults() }
        }

        fun defaults(): LayoutBook {
            val desk = Layout(1, "Desk", 4, false, listOf(
                Tile(101, TileSize.LARGE, listOf(WidgetType.CLOCK)),
                Tile(102, TileSize.MEDIUM, listOf(WidgetType.WEATHER)),
                Tile(103, TileSize.SMALL, listOf(WidgetType.BATTERY)),
                Tile(104, TileSize.SMALL, listOf(WidgetType.CALENDAR, WidgetType.REMINDERS), rotateSec = 10),
            ))
            val morning = Layout(2, "Morning", 4, false, listOf(
                Tile(201, TileSize.LARGE, listOf(WidgetType.WEATHER)),
                Tile(202, TileSize.LARGE, listOf(WidgetType.CALENDAR)),
            ), LayoutContext.MORNING)
            val music = Layout(3, "Music", 4, false, listOf(
                Tile(301, TileSize.LARGE, listOf(WidgetType.MUSIC)),
                Tile(302, TileSize.MEDIUM, listOf(WidgetType.CLOCK)),
                Tile(303, TileSize.SMALL, listOf(WidgetType.BLUETOOTH)),
                Tile(304, TileSize.SMALL, listOf(WidgetType.BATTERY)),
            ), LayoutContext.MUSIC)
            val status = Layout(4, "Status", 3, false, listOf(
                Tile(401, TileSize.SMALL, listOf(WidgetType.WIFI)),
                Tile(402, TileSize.SMALL, listOf(WidgetType.BLUETOOTH)),
                Tile(403, TileSize.SMALL, listOf(WidgetType.BATTERY)),
                Tile(404, TileSize.SMALL, listOf(WidgetType.TIMER)),
                Tile(405, TileSize.SMALL, listOf(WidgetType.NOTIFICATIONS)),
                Tile(406, TileSize.SMALL, listOf(WidgetType.PET)),
            ))
            val night = Layout(5, "Night", 2, false, listOf(Tile(501, TileSize.FULL, listOf(WidgetType.CLOCK))), LayoutContext.NIGHT)
            return LayoutBook(listOf(desk, morning, music, status, night), 1, false)
        }
    }
}

/** Fixed pairs for the Dual Widget family (vertical swipe cycles them). */
object DualPresets {
    val pairs = listOf(
        "Clock & calendar" to (WidgetType.CLOCK to WidgetType.CALENDAR),
        "Weather & battery" to (WidgetType.WEATHER to WidgetType.BATTERY),
        "Music & next event" to (WidgetType.MUSIC to WidgetType.CALENDAR),
        "Clock & weather" to (WidgetType.CLOCK to WidgetType.WEATHER),
        "Timer & reminders" to (WidgetType.TIMER to WidgetType.REMINDERS),
    )

    fun layout(i: Int): Layout {
        val (a, b) = pairs[i].second
        return Layout(-1L - i, pairs[i].first, 2, true, listOf(
            Tile(-10L - i * 2, TileSize.SMALL, listOf(a)), Tile(-11L - i * 2, TileSize.SMALL, listOf(b)),
        ))
    }
}
