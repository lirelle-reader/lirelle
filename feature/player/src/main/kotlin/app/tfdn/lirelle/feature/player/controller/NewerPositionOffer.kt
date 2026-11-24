package app.tfdn.lirelle.feature.player.controller

import app.tfdn.lirelle.core.audio.position.ListeningConflicts
import app.tfdn.lirelle.core.audio.position.NewerListeningRule
import app.tfdn.lirelle.core.audio.position.PlaybackOutbox
import app.tfdn.lirelle.core.audio.position.PlaybackStore
import app.tfdn.lirelle.core.audio.timeline.BookTimeline
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.AudioRepository
import app.tfdn.lirelle.core.library.model.PlaybackState
import app.tfdn.lirelle.core.network.ApiResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The server's [server] state of [bookId], offered; it is at [bookTimeMs] on the book's timeline. */
internal data class Offer(val bookId: BookId, val server: PlaybackState, val bookTimeMs: Long)

/**
 * The offers of a newer position from elsewhere (spec 8.3), by book: another device, or reading the
 * text. Nothing moves on its own. Two ways lead to one:
 *
 * - **Back at the front** ([check]): the server's state is offered when it is more recent than this
 *   device's last write, and elsewhere in the book ([NewerListeningRule]). The last write is the one the
 *   server took or may have taken ([ListeningConflicts.lastWritten]), or one still waiting to be sent;
 *   never the position last stored on the device, which may be a write the flusher dropped. A state in
 *   the book's known version is this device's own last write, or one already answered: it is not
 *   offered, even after a restart, when the last write in memory is gone.
 * - **A conflict** ([standing]): the flusher already found the state written elsewhere; it is offered
 *   when it is elsewhere in the book, whatever its date.
 *
 * Call it on the main thread.
 */
internal class NewerPositionOffer(
    private val audio: AudioRepository,
    private val store: PlaybackStore,
    private val outbox: PlaybackOutbox,
    private val conflicts: ListeningConflicts,
) {
    private val mutableOffers = MutableStateFlow<Map<BookId, Offer>>(emptyMap())
    val offers: StateFlow<Map<BookId, Offer>> = mutableOffers.asStateFlow()

    private val rules = mutableMapOf<BookId, NewerListeningRule>()
    private val timelines = mutableMapOf<BookId, BookTimeline>()

    /** The book's files changed on the server: its timeline is read again. */
    fun onManifestChanged(id: BookId) {
        timelines -= id
    }

    /** Back at the front: the server's state of [id], offered if it is worth it, against where playback is [now]. */
    suspend fun check(id: BookId, now: () -> Long?) {
        val timeline = timelineOf(id) ?: return
        val rule = rules.getOrPut(id, ::NewerListeningRule)
        conflicts.ownWrites(id).forEach(rule::onOwnWrite)
        outbox.pending().filter { it.bookId == id }.forEach(rule::onOwnWrite)
        val server = read(id) ?: return
        if (server.version == store.knownVersion(id)) return
        if (rule.isOffered(server, timeline, now())) offer(id, server, timeline)
    }

    /**
     * A state written elsewhere stands against the device's writes ([known], or read again); the device is
     * at [now]. Elsewhere, it is offered; at the same place, it is returned, to be followed without a word.
     */
    suspend fun standing(id: BookId, known: PlaybackState?, now: Long?): PlaybackState? {
        val timeline = timelineOf(id) ?: return null
        val server = known ?: read(id) ?: return null
        if (!rules.getOrPut(id, ::NewerListeningRule).isElsewhere(server, timeline, now)) return server
        offer(id, server, timeline)
        return null
    }

    /** "Go there": the offer of [id], followed; null when there is none. */
    fun accept(id: BookId): Offer? = take(id)?.also { rules[id]?.onRead(it.server) }

    /** "Ignore", said or implied: the offer of [id], dismissed; only a newer one is offered again. */
    fun ignore(id: BookId): Offer? = take(id)?.also { rules.getOrPut(id, ::NewerListeningRule).ignore(it.server) }

    /** The book [id] is no longer the player's: its offer goes, and what was learnt about it. */
    fun forget(id: BookId) {
        take(id)
        rules -= id
        timelines -= id
    }

    private fun offer(id: BookId, server: PlaybackState, timeline: BookTimeline) {
        val at = timeline.bookTimeOf(server.assetId, server.positionMs) ?: return
        mutableOffers.update { it + (id to Offer(id, server, at)) }
    }

    private fun take(id: BookId): Offer? = mutableOffers.value[id]?.also { mutableOffers.update { it - id } }

    private suspend fun read(id: BookId): PlaybackState? = (audio.playbackState(id) as? ApiResult.Success)?.value

    private suspend fun timelineOf(id: BookId): BookTimeline? = timelines[id] ?: run {
        val manifest = (audio.manifest(id) as? ApiResult.Success)?.value ?: return null
        BookTimeline(manifest).also { timelines[id] = it }
    }
}
