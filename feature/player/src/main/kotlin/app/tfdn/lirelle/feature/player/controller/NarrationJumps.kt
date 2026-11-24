package app.tfdn.lirelle.feature.player.controller

import app.tfdn.lirelle.feature.player.ListeningGoBack
import app.tfdn.lirelle.feature.player.service.PlayerCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A narration's section chosen, and "Go back" from it (spec 6.3), as the 3a player's chapter and "Go back". */
interface NarrationJumpCommands {
    /** The start of the narrated section [index] (a row of the sections sheet): "Go back" returns from it. */
    fun goToSection(index: Int)

    /** "Go back": the place the last section was chosen from, which is forgotten. Nothing when there is none. */
    fun goBackInNarration()
}

/**
 * The places of the narration that sections were chosen from, the last one on top, kept as the 3a player keeps
 * its chapter jumps ([ListeningGoBack]): two minutes listened since the last jump or go back forget them, and so
 * does another book, or the same book's audiobook. Call it on the main thread.
 */
internal class NarrationJumps(private val connection: PlayerConnection) : NarrationJumpCommands {
    private val places = ListeningGoBack()
    private val mutableShown = MutableStateFlow(false)

    /** Whether "Go back" is offered. */
    val shown: StateFlow<Boolean> = mutableShown.asStateFlow()

    /** What the session shows now. */
    fun onSession(session: SessionSnapshot) {
        val narration = session.bookId?.takeIf { session.narrating }
        places.onPlayback(narration, session.bookTimeMs, session.playWhenReady && !session.ended)
        publish()
    }

    override fun goToSection(index: Int) {
        val now = connection.snapshot.value
        if (!now.narrating || index !in now.chapters.indices) return
        places.push(now.bookTimeMs)
        connection.seekTo(index, 0)
        publish()
    }

    override fun goBackInNarration() {
        val place = places.pop() ?: return
        connection.send(PlayerCommand.SeekBookTime(place))
        publish()
    }

    private fun publish() {
        mutableShown.value = !places.isEmpty
    }
}
