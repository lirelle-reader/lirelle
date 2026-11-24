package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.feature.reader.bridge.ReaderEngine
import app.tfdn.lirelle.feature.reader.narration.loaded
import kotlinx.coroutines.launch

/** What the screen and its window tell the reader. */
internal class HostEvents(
    private val core: ReaderCore,
    private val appearance: Appearance,
    private val opening: Opening,
    private val endOfBook: EndOfBook,
    private val navigation: ReaderNavigation,
    private val marks: MarksPart,
    private val search: SearchPart,
    private val notes: NotesPart,
) : ReaderHostEvents {

    /** A second engine is a web view that replaced a dead one: the book opens again where it was. */
    override fun onEngine(engine: ReaderEngine) {
        core.update { it.copy(opened = false) }
        core.driver.attach(engine)
    }

    override fun onResumed() {
        marks.onResumed()
        core.onFront()
        val session = core.session ?: return
        // While the book is narrated, the narration moves the server's text position ahead of the page, which
        // follows it without sending one: that place is not a newer one from elsewhere.
        val narrated = core.current.narration.active?.loaded == true
        core.scope.launch {
            val offered = session.resumed(offer = !narrated)
            core.offered = offered
            core.update { it.copy(newerPosition = offered?.let { o -> OfferedPosition(o.percent, o.updatedAt) }) }
        }
    }

    override fun onPaused() {
        core.session?.paused()
    }

    override fun onLeave() = opening.finish()

    /**
     * Whatever is drawn on top closes first: the question about a web link, the footnote, the
     * highlight's panel, the selection, a sheet, the panel at the end of the book (which sends the
     * stars chosen, and leaves the book open), the search's bar.
     */
    override fun onBack(): Boolean {
        if (core.current.externalLink != null) {
            core.update { it.copy(externalLink = null) }
            return true
        }
        if (notes.onBack() || marks.onBack()) return true
        val state = core.current
        when {
            state.openSheet != null -> core.update { under ->
                sheetUnder(state.openSheet)?.let { under.copy(openSheet = it) } ?: under.withoutSheet()
            }
            state.endPanel != null -> endOfBook.dismissEndPanel()
            state.search?.onPage == true -> search.closeSearch()
            state.goBackShown -> navigation.goBack()
            state.controlsShown -> core.update { it.copy(controlsShown = false) }
            else -> return false
        }
        return true
    }

    override fun onSystemDark(dark: Boolean) = appearance.onSystemDark(dark)

    override fun onWindow(wide: Boolean) = appearance.onWindow(wide)

    override fun onSystemBrightness(level: Float) {
        if (!level.isNaN()) core.update { it.copy(systemBrightness = level.coerceIn(0f, 1f)) }
    }

    override fun onVolumeKey(volumeDown: Boolean): Boolean {
        val state = core.current
        val covered = state.openSheet != null || state.endPanel != null || state.externalLink != null ||
            state.noteOpen || state.highlightPanel != null
        if (!state.settings.volumeKeys || !state.opened || covered) return false
        core.turn(forward = volumeDown)
        return true
    }

    /** The two sheets opened from the settings go back to them. */
    private fun sheetUnder(sheet: ReaderSheet): ReaderSheet? = when (sheet) {
        ReaderSheet.RunningLines, ReaderSheet.MoreOptions -> ReaderSheet.Settings
        ReaderSheet.Contents, ReaderSheet.Settings, ReaderSheet.Search -> null
    }
}
