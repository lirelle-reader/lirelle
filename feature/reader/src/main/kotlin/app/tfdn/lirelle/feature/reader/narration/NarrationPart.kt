package app.tfdn.lirelle.feature.reader.narration

import app.tfdn.lirelle.core.audio.narration.SkippedNotice
import app.tfdn.lirelle.feature.player.narration.NarrationActions
import app.tfdn.lirelle.feature.reader.EndOfBook
import app.tfdn.lirelle.feature.reader.ReaderCore
import app.tfdn.lirelle.feature.reader.ReaderUiState
import app.tfdn.lirelle.feature.reader.bridge.HostMessage
import app.tfdn.lirelle.feature.reader.bridge.NarrationTarget
import app.tfdn.lirelle.feature.reader.bridge.PageMessage
import app.tfdn.lirelle.feature.reader.sectionStartCfi
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * The reader during a narration of its book (spec 7): the sentence heard underlined and followed
 * ([NarrationFollowing]), the starting points ([NarrationStarts]), the sections skipped, the end.
 *
 * @param endSelection ends the page's selection, once "Read from here" has asked for its sentence.
 * @param narrate the reader was opened to narrate the book.
 */
internal class NarrationPart(
    private val core: ReaderCore,
    private val services: NarrationServices,
    private val endOfBook: EndOfBook,
    private val endSelection: () -> Unit,
    narrate: Boolean,
) : ReaderNarrationActions,
    ReaderNarrationStop by NarrationStop(core, services.player) {
    val following = NarrationFollowing(core, services.player)
    val starts = NarrationStarts(core, services, following, narrate)
    private val backJumps = PanelBackJumps(core, starts, following)

    /** The bar's and the panel's controls, a move back the user meant marked on the way. */
    val panelActions: NarrationActions =
        ReaderPanelActions(services.player.actions, { core.current.narration.active }, backJumps::mark)

    init {
        following.onSentence = backJumps::onHeard
    }

    fun start() {
        following.start()
        core.scope.launch {
            // Only the narration of the file read: a notice names no file, the sentence heard before it does.
            val ours = services.player.skipped.filter { it.bookId == core.bookId && following.sentence != null }
            ours.collect { notice ->
                // The service knows no title: the section is named by the contents entry it starts, if it does.
                val named = notice.copy(title = notice.title ?: core.current.entryStartedBy(notice.sectionIndex))
                core.update { it.copy(narration = it.narration.copy(notice = named)) }
            }
        }
    }

    fun onMessage(message: PageMessage.Narrating) = when (message) {
        is PageMessage.NarrationFound -> starts.onFound(message)
        is PageMessage.NarrationCfiOf -> starts.onPosition(message)
        is PageMessage.NarrationVisible -> following.onVisible(message.visible)
        is PageMessage.NarrationMissing -> following.onMissing(message.sectionIndex, message.id)
    }

    override fun startHere() = starts.find(NarrationTarget.Page, core.sectionIndex)

    override fun readFromSelection() {
        val selection = core.current.selection ?: return
        starts.find(NarrationTarget.Selection, selection.sectionIndex, then = endSelection)
    }

    override fun readThisPage() = startHere()

    override fun backToSentence() = following.follow()

    override fun finishBook() = endOfBook.onPastEnd()

    /** A jump of the user's: "Go back" is offered, and following stops. */
    override fun readSkipped(notice: SkippedNotice) {
        services.player.pause()
        dismissNotice(notice)
        core.jump(HostMessage.GoTo.Cfi(sectionStartCfi(notice.sectionIndex)), remember = true)
    }

    override fun dismissNotice(notice: SkippedNotice) = core.update {
        if (it.narration.notice == notice) it.copy(narration = it.narration.copy(notice = null)) else it
    }

    /** A narration that could not be loaded, or that an error stopped, is the player's to load again; else the map. */
    override fun retryNarration() {
        val shown = core.current.narration
        val active = shown.active
        val player = shown.loadFailed || active != null && (active.loadFailed || active.error)
        if (player) services.player.retryNarration() else starts.retry()
    }
}

/** The label of the contents entry the section at [index] of the spine starts; null when it starts none. */
private fun ReaderUiState.entryStartedBy(index: Int): String? {
    val label = sectionLabels.getOrNull(index) ?: return null
    return label.takeUnless { index > 0 && sectionLabels.getOrNull(index - 1) == it }
}
