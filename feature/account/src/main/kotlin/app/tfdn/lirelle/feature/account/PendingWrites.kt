package app.tfdn.lirelle.feature.account

import app.tfdn.lirelle.core.audio.bookmarks.BookmarkChange
import app.tfdn.lirelle.core.audio.bookmarks.BookmarkQueue
import app.tfdn.lirelle.core.audio.position.ListeningSessionQueue
import app.tfdn.lirelle.core.audio.position.PlaybackOutbox
import app.tfdn.lirelle.core.library.net.ProfileNetwork
import app.tfdn.lirelle.core.reader.marks.MarkChange
import app.tfdn.lirelle.core.reader.marks.MarkOutbox
import app.tfdn.lirelle.core.reader.sync.Outbox
import javax.inject.Inject

/**
 * What was done on this device and has not reached the server yet, by kind.
 *
 * @property positions where the user is in a book: its text, its narration, its audiobook.
 * @property sessions the reading and listening sessions.
 * @property bookmarks those of the text and those of the audiobook.
 * @property highlights the highlights, with their notes.
 */
data class PendingCounts(
    val positions: Int = 0,
    val sessions: Int = 0,
    val bookmarks: Int = 0,
    val highlights: Int = 0,
) {
    val total: Int get() = positions + sessions + bookmarks + highlights
    val isEmpty: Boolean get() = total == 0
}

/** Several changes of one bookmark or of one highlight are one thing to lose. */
internal fun pendingCounts(
    positions: Int,
    sessions: Int,
    marks: List<MarkChange>,
    audioBookmarks: List<BookmarkChange>,
): PendingCounts {
    val (bookmarks, highlights) = marks.partition { it is MarkChange.AddBookmark || it is MarkChange.DeleteBookmark }
    return PendingCounts(
        positions = positions,
        sessions = sessions,
        bookmarks = bookmarks.distinctBy { it.bookId to it.id }.size +
            audioBookmarks.distinctBy { it.bookId to it.id }.size,
        highlights = highlights.distinctBy { it.bookId to it.id }.size,
    )
}

/**
 * What removing the current profile would lose. With a network nothing is: what waits is sent as it always
 * was, before the profile goes. Without one, it is still on this device only, and goes with the profile. A
 * profile whose session waits for a sign-in has no network of its own, whatever the device has: nothing of it
 * can be sent, and all of it is at risk.
 *
 * @param read what the queues of the reader and of the player hold for the account signed in.
 */
class PendingWrites internal constructor(
    private val read: suspend () -> PendingCounts,
    private val online: () -> Boolean,
) {
    @Inject
    constructor(
        outbox: Outbox,
        marks: MarkOutbox,
        playback: PlaybackOutbox,
        listeningSessions: ListeningSessionQueue,
        audioBookmarks: BookmarkQueue,
        network: ProfileNetwork,
    ) : this(
        read = {
            val reading = outbox.pending()
            pendingCounts(
                positions = reading.positions.size + reading.narrations.size + playback.pending().size,
                sessions = reading.sessions.size + listeningSessions.pending().size,
                marks = marks.pending(),
                audioBookmarks = audioBookmarks.pending(),
            )
        },
        network = network,
    )

    /** Asks the profile's own network: none while its session waits for a sign-in, whatever the device has. */
    internal constructor(read: suspend () -> PendingCounts, network: ProfileNetwork) :
        this(read, online = { network.isOnline })

    suspend fun atRisk(): PendingCounts = if (online()) PendingCounts() else read()
}
