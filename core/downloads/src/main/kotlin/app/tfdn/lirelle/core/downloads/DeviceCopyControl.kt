package app.tfdn.lirelle.core.downloads

import app.tfdn.lirelle.core.downloads.model.DeviceCopy
import app.tfdn.lirelle.core.downloads.model.deviceCopy
import app.tfdn.lirelle.core.ids.BookId
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The row of an end panel that says what the device keeps of the finished book. */
interface DeviceCopyActions {
    /** "Delete": everything the device keeps of the book goes when the book is closed. Asked once. */
    fun deleteFromDevice()

    /** "Undo": the book stays after all. */
    fun keepOnDevice()
}

/** What a [DeviceCopyControl] works with, from the app's graph. */
class DeviceCopySources @Inject constructor(val downloads: Downloads, val deletions: ClosingDeletions)

/**
 * The row of the end panels, for the book a reader or the player shows: what the device keeps of it ([copy]),
 * "Delete" and "Undo". The book is open, so its deletion waits for it to be closed; a book that nothing holds
 * goes at once, and its copy with it. Call it on one thread, the one of [scope].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceCopyControl(private val sources: DeviceCopySources, private val scope: CoroutineScope) : DeviceCopyActions {
    private val book = MutableStateFlow<BookId?>(null)

    /** What was last asked and is not yet seen in [copy]: true for a deletion, false for its undoing. */
    private var asked: Boolean? = null

    /** Null while no book is shown, and when the device keeps nothing whole of the one shown. */
    val copy: StateFlow<DeviceCopy?> = book
        .flatMapLatest { id -> if (id == null) flowOf(null) else sources.downloads.book(id).map { it?.deviceCopy() } }
        .onEach { if (it == null || it.deleting == asked) asked = null }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** The book the panel is for; null for none. */
    fun show(bookId: BookId?) {
        if (bookId != book.value) asked = null
        book.value = bookId
    }

    override fun deleteFromDevice() = ask(delete = true)

    override fun keepOnDevice() = ask(delete = false)

    private fun ask(delete: Boolean) {
        val id = book.value ?: return
        val shown = copy.value ?: return
        if ((asked ?: shown.deleting) == delete) return
        asked = delete
        scope.launch {
            try {
                if (delete) sources.deletions.deleteAtClose(id) else sources.deletions.keepAfterAll(id)
            } catch (_: IOException) {
                // The registry could not be written: the row stays as it was, and can be tapped again.
                if (book.value == id && asked == delete) asked = null
            }
        }
    }
}
