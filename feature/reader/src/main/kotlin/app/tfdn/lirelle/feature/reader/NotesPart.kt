package app.tfdn.lirelle.feature.reader

import app.tfdn.lirelle.feature.reader.bridge.HostMessage
import app.tfdn.lirelle.feature.reader.bridge.PageMessage

/** The panel of a footnote: the page shows it and closes it, the reader only knows it is there. */
internal class NotesPart(private val core: ReaderCore) {

    /** A note covers the bottom of the page: the controls give way. */
    fun onMessage(message: PageMessage.Noting) = when (message) {
        is PageMessage.NoteOpened -> core.update { it.copy(noteOpen = true, controlsShown = false) }
        PageMessage.NoteClosed -> onClosed()
    }

    /** The page closed the note, or opened a book, or followed a link: no note is shown. */
    fun onClosed() = core.update { it.copy(noteOpen = false) }

    /** The system's back closes the note, without waiting for the page. False when none is shown. */
    fun onBack(): Boolean {
        if (!core.current.noteOpen) return false
        core.driver.send(HostMessage.CloseNote)
        onClosed()
        return true
    }
}
