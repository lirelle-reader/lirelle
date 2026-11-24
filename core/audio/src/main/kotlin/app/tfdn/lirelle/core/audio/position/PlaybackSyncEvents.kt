package app.tfdn.lirelle.core.audio.position

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import javax.inject.Inject
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

private const val Buffered = 8

/**
 * What the [PlaybackFlusher] of a profile learns that its player must act on, by book id: the book's files
 * changed. Nothing is kept for a player that is not listening: it reads the manifest when it opens anyway.
 * (A state written elsewhere is kept, and offered, by [ListeningConflicts].)
 */
@ProfileScoped
class PlaybackSyncEvents @Inject constructor() {
    private val mutableManifestChanges = events()

    /** The book's files changed on the server: reload the manifest. */
    val manifestChanges: SharedFlow<BookId> = mutableManifestChanges.asSharedFlow()

    fun manifestChanged(bookId: BookId) {
        mutableManifestChanges.tryEmit(bookId)
    }

    private fun events() =
        MutableSharedFlow<BookId>(extraBufferCapacity = Buffered, onBufferOverflow = BufferOverflow.DROP_OLDEST)
}
