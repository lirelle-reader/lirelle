package app.tfdn.lirelle.feature.player

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.feature.player.controller.PlayerController
import app.tfdn.lirelle.feature.player.controller.PlayerUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Two minutes listened after a jump mean the user is listening on: every place is forgotten. */
private const val ForgetAfterMs = 120_000L
private const val MostPlaces = 16

/** A step of book time between two looks at most this long is listening; a longer one is a skip or a seek. */
private const val ListeningStepMs = 5_000L

/**
 * The places of the book jumps were made from, the last one on top: "Go back" is offered while there is
 * one. The audio version of the EPUB reader's two pages turned: two minutes listened since the last jump
 * or go back forget every place, and so does another book.
 */
class ListeningGoBack {
    private val places = ArrayDeque<Long>()
    private var bookId: BookId? = null
    private var lastLook: Long? = null
    private var listenedMs = 0L

    val isEmpty: Boolean get() = places.isEmpty()

    /** A jump is made from [bookTimeMs]. */
    fun push(bookTimeMs: Long) {
        places.addLast(bookTimeMs)
        if (places.size > MostPlaces) places.removeFirst()
        restartCount()
    }

    /** Where to go back to, which is forgotten; null when there is nowhere. */
    fun pop(): Long? = places.removeLastOrNull().also { restartCount() }

    /** The player shows [bookId] at [bookTimeMs], [playing] or not. */
    fun onPlayback(bookId: BookId?, bookTimeMs: Long, playing: Boolean) {
        if (bookId != this.bookId) {
            this.bookId = bookId
            places.clear()
            restartCount()
        }
        val last = lastLook
        lastLook = bookTimeMs
        if (places.isEmpty() || last == null || !playing) return
        val step = bookTimeMs - last
        if (step in 1..ListeningStepMs) listenedMs += step
        if (listenedMs >= ForgetAfterMs) places.clear()
    }

    /** The place the player is at is not known yet: the look after a jump does not count. */
    private fun restartCount() {
        lastLook = null
        listenedMs = 0
    }
}

/** The chapter list's jumps, a bookmark's and the newer position's "Go there", which "Go back" undoes. */
internal class JumpsPart(private val controller: PlayerController) : ChapterJumps, NewerPositionActions {
    private val goBack = ListeningGoBack()
    private val mutableShown = MutableStateFlow(false)
    private var current = PlayerUiState()

    /** Whether "Go back" is offered. */
    val shown: StateFlow<Boolean> = mutableShown.asStateFlow()

    /** What the player shows now. */
    fun onState(state: PlayerUiState) {
        current = state
        goBack.onPlayback(state.bookId, state.bookTimeMs, state.playing)
        publish()
    }

    override fun goToChapter(index: Int) {
        if (current.bookId == null || index !in current.chapters.indices) return
        goBack.push(current.bookTimeMs)
        controller.goToChapter(index)
        publish()
    }

    /** A place on the book's timeline, a bookmark's; "Go back" returns to where playback was. */
    fun jumpTo(bookTimeMs: Long) {
        if (current.bookId == null) return
        goBack.push(current.bookTimeMs)
        controller.seekBookTime(bookTimeMs)
        publish()
    }

    override fun goBack() {
        val place = goBack.pop() ?: return
        controller.seekBookTime(place)
        publish()
    }

    override fun acceptNewerPosition() {
        val from = current.bookTimeMs
        if (!controller.acceptNewerPosition()) return
        goBack.push(from)
        publish()
    }

    override fun ignoreNewerPosition() = controller.ignoreNewerPosition()

    private fun publish() {
        mutableShown.value = !goBack.isEmpty
    }
}
