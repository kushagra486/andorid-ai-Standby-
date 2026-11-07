package com.nova.core

import android.view.View
import com.nova.clock.ClockFace
import com.nova.clock.ClockFaceView
import com.nova.music.MusicView
import com.nova.pet.PetView
import com.nova.photos.PhotoFrameView
import com.nova.photos.PhotoStyle
import com.nova.voice.AssistantView
import com.nova.widgets.DualPresets
import com.nova.widgets.LayoutStore
import com.nova.widgets.TileGridView

/** Compact info options shown with the Pixel Pet (vertical swipe cycles them). */
enum class PetInfo(val label: String) { NONE("Face only"), TIME("Face + time"), STATUS("Face + status"), MUSIC("Face + music") }

class PetPage(val pet: PetView, private val compact: (PetInfo) -> String?) : FamilyPage {
    override val family = Family.PET
    override val view: View get() = pet
    override val variationCount = PetInfo.entries.size
    override var variation = 0
        set(value) { field = value; val info = PetInfo.entries[value]; pet.compactLine = if (info == PetInfo.NONE) null else ({ compact(info) }) }
    override fun variationLabel(i: Int) = PetInfo.entries[i].label
    override fun onShow() = pet.resume()
    override fun onHide() = pet.pause()
    override val capturesGestures: Boolean get() = pet.testing
}

class ClockPage(val clock: ClockFaceView) : FamilyPage {
    override val family = Family.CLOCK
    override val view: View get() = clock
    override val variationCount = ClockFace.entries.size
    override var variation = 0
        set(value) { field = value; clock.face = ClockFace.entries[value] }
    override fun variationLabel(i: Int) = ClockFace.entries[i].label + " clock"
    override fun onShow() = clock.resume()
    override fun onHide() = clock.pause()
}

class DualPage(val grid: TileGridView) : FamilyPage {
    override val family = Family.DUAL
    override val view: View get() = grid
    override val variationCount = DualPresets.pairs.size
    override var variation = 0
        set(value) { field = value; grid.layout = DualPresets.layout(value) }
    override fun variationLabel(i: Int) = DualPresets.pairs[i].first
    override fun onShow() = grid.resume()
    override fun onHide() = grid.pause()
    init { grid.editable = false }
}

/** Modular dashboard: vertical swipe cycles saved layouts; long-press edits in place. */
class DashboardPage(val grid: TileGridView) : FamilyPage {
    override val family = Family.DASHBOARD
    override val view: View get() = grid
    override val variationCount: Int get() = LayoutStore.book.layouts.size
    override var variation = 0
        set(value) {
            val book = LayoutStore.book
            val i = value.coerceIn(0, book.layouts.size - 1)
            field = i
            val l = book.layouts[i]
            grid.layout = l
            if (book.activeId != l.id) LayoutStore.book = book.copy(activeId = l.id)
        }
    override fun variationLabel(i: Int) = LayoutStore.book.layouts.getOrNull(i)?.name?.let { "$it layout" } ?: ""
    override fun onShow() { reload(); grid.resume() }
    override fun onHide() { grid.setEditing(false); grid.pause() }
    override fun onBack(): Boolean = if (grid.editing) { grid.setEditing(false); true } else false
    override val capturesGestures: Boolean get() = grid.editing

    fun reload() {
        val book = LayoutStore.book
        val i = book.layouts.indexOfFirst { it.id == book.activeId }.coerceAtLeast(0)
        if (!grid.editing) { variation = i }
    }
}

class PhotoPage(val photos: PhotoFrameView) : FamilyPage {
    override val family = Family.PHOTOS
    override val view: View get() = photos
    override val variationCount = PhotoStyle.entries.size
    override var variation = 0
        set(value) { field = value; photos.style = PhotoStyle.entries[value] }
    override fun variationLabel(i: Int) = PhotoStyle.entries[i].label
    override fun onShow() = photos.resume()
    override fun onHide() = photos.pause()
}

class MusicPage(val music: MusicView) : FamilyPage {
    override val family = Family.MUSIC
    override val view: View get() = music
    override val variationCount = 2
    override var variation = 0
        set(value) { field = value; music.variation = value }
    override fun variationLabel(i: Int) = if (i == 0) "Now playing" else "Dancing NØVA"
    override fun onShow() = music.resume()
    override fun onHide() = music.pause()
}

class AssistantPage(val assistantView: AssistantView) : FamilyPage {
    override val family = Family.ASSISTANT
    override val view: View get() = assistantView
    override val variationCount = 1
    override var variation = 0
    override fun variationLabel(i: Int) = "Assistant"
    override fun onShow() = assistantView.resume()
    override fun onHide() = assistantView.pause()
}
