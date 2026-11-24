package app.tfdn.lirelle.feature.reader.narration

import app.tfdn.lirelle.core.audio.narration.HeardSentence
import app.tfdn.lirelle.feature.player.controller.NarrationFailure
import app.tfdn.lirelle.feature.player.controller.NarrationUi
import android.util.Log
import app.tfdn.lirelle.feature.reader.BuildConfig
import app.tfdn.lirelle.feature.reader.ReaderCore
import app.tfdn.lirelle.feature.reader.ReaderUiState
import app.tfdn.lirelle.feature.reader.panelWindowOpen
import app.tfdn.lirelle.feature.reader.sectionStartCfi
import app.tfdn.lirelle.feature.reader.bridge.HostMessage
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private const val Tag = "ReaderNarration"

/**
 * The sentence heard of this book's narration on the page: underlined, followed while [Following.On], and the
 * reading session left off while it plays. What it is not about, another book's narration or another file's,
 * shows nothing; when the narration stops, or another book's is heard, the underline goes.
 *
 * Following starts again when a narration of the book is loaded, at Play after a pause, with [follow] ("Back to
 * the sentence") and by itself when the page says the sentence shows again; it stops at any move of the user
 * ([navigated]).
 */
internal class NarrationFollowing(private val core: ReaderCore, private val player: ReaderNarrationPlayer) {
    private var following: Following = Following.On

    /** The sentence heard of this book's file, null when there is none. */
    var sentence: HeardSentence? = null
        private set

    private var sentenceOnPage = true

    /**
     * The sentence heard when a start elsewhere was asked: while it is still the one heard, it is not told, so
     * that the page is not sent back to it. Any other sentence heard ends the wait.
     */
    private var heldBack: Pair<Int, String>? = null

    /** What the page was last told of the narration; null when nothing since it opened the book. */
    private var told: HostMessage? = null

    /** The narration the player shows, of any book, and the one last asked that could not be loaded. */
    private var shown: NarrationUi? = null
    private var failed: NarrationFailure? = null

    /** Told each sentence heard of this book's file, or null when there is none, before the page is told. */
    var onSentence: (HeardSentence?) -> Unit = {}

    fun start() {
        core.scope.launch { player.heard.collect(::onHeard) }
        core.scope.launch {
            combine(player.narration, player.failure, ::Pair).collect { (narration, failure) ->
                onNarration(narration, failure)
            }
        }
    }

    /** The page shows the book, afresh: it knows nothing of the narration. */
    fun onOpened() {
        told = null
        sentenceOnPage = true
        // Only from a source with read-along (spec 2c).
        val available = core.capabilities.readAlong && core.book?.file?.hasReadAlong == true
        core.update { it.copy(narration = it.narration.copy(available = available)) }
        onNarration(shown, failed)
        onHeard(player.heard.value)
        core.session?.narration(sentence?.playing == true)
    }

    /** The page says whether the sentence heard is on the page shown; shown again, it is followed again. */
    fun onVisible(visible: Boolean) {
        sentenceOnPage = visible
        if (visible) following = Following.On
        refresh()
    }

    /**
     * The sentence [id] the page was told is not in its section [sectionIndex]: nothing is underlined. A page that
     * follows goes to that section's start when it shows another section (the page itself does it only for a
     * section it had not loaded).
     */
    fun onMissing(sectionIndex: Int, id: String) {
        if (BuildConfig.DEBUG) Log.d(Tag, "The sentence $id is not in section $sectionIndex")
        if (following == Following.On && core.sectionIndex != sectionIndex) {
            core.follow(HostMessage.GoTo.Cfi(sectionStartCfi(sectionIndex)))
        }
    }

    /** The user moved in the book: the page stays where they took it. */
    fun navigated() {
        heldBack = null
        if (following == Following.Off) return
        following = Following.Off
        refresh()
    }

    /** "Back to the sentence": the page goes there, and follows it. */
    fun follow() {
        heldBack = null
        following = Following.On
        told = null
        refresh()
    }

    /** The narration was asked to start at the sentence [fragment] of [sectionIndex]: it is followed from there. */
    fun starting(sectionIndex: Int, fragment: String) {
        following = Following.On
        heldBack = sentence?.key?.takeUnless { it == sectionIndex to fragment }
        refresh()
    }

    private fun onHeard(heard: HeardSentence?) {
        val ours = heard?.takeIf { it.bookId == core.bookId && it.fileId == core.fileId }
        val before = sentence
        sentence = ours
        val playsAgain = ours?.playing == true && before?.playing == false
        if (ours != null && (before == null || playsAgain)) following = Following.On
        if (heldBack != ours?.key) heldBack = null
        if (ours?.playing != before?.playing) core.session?.narration(ours?.playing == true)
        onSentence(ours)
        refresh()
    }

    /**
     * The narration held is this book's only for this book's file; another book's held leaves this one without, and
     * then a failure of this book's narration asked meanwhile is told.
     */
    private fun onNarration(narration: NarrationUi?, failure: NarrationFailure?) {
        shown = narration
        failed = failure
        val ours = narration?.takeIf { it.bookId == core.bookId && it.fileId == core.fileId }
        val ownFailure = ours == null && failure?.let { it.bookId == core.bookId && it.fileId == core.fileId } == true
        val ended = ours?.finished == true
        // Once the narration ended there is no way back to its sentence: the bar offers to finish the book.
        val strip = stripOf(sentence, following, sentenceOnPage).takeUnless { ended }
        core.update { state ->
            val shownNow = state.narration.copy(active = ours, ended = ended, loadFailed = ownFailure, strip = strip)
            // Its bar is one of the controls: a narration heard to its end here brings them, so that it is seen.
            val reachedEnd = ended && state.narration.active != null && !state.narration.ended
            state.copy(narration = shownNow, controlsShown = state.controlsShown || (reachedEnd && state.screenClear))
        }
    }

    /** Nothing holds the screen that the controls would come over: a selection, a footnote, a sheet or a panel. */
    private val ReaderUiState.screenClear: Boolean
        get() = selection == null && !noteOpen && openSheet == null && endPanel == null &&
            highlightPanel == null && !panelWindowOpen

    /** Tells the page the sentence when it changed, and shows where the following is. */
    private fun refresh() {
        val heard = sentence
        val message = when {
            heard == null || heard.fragment.isEmpty() -> HostMessage.NarrationClear.takeIf { told != null }
            heldBack != null -> null
            else -> HostMessage.NarrationMark(
                sectionIndex = heard.sectionIndex,
                id = heard.fragment,
                paused = !heard.playing,
                follow = following == Following.On,
            )
        }
        if (message != null && message != told && core.driver.send(message)) told = message
        val strip = stripOf(heard, following, sentenceOnPage)
        core.update { state ->
            val shown = strip.takeUnless { state.narration.ended }
            val narration = state.narration.copy(following = following, sentenceOnPage = sentenceOnPage, strip = shown)
            state.copy(narration = narration)
        }
    }
}

/** Which sentence it is, whether playing or paused. */
private val HeardSentence.key: Pair<Int, String> get() = sectionIndex to fragment

/** Playing and not followed: back to the sentence; paused with the sentence off the page: or read this page. */
private fun stripOf(heard: HeardSentence?, following: Following, sentenceOnPage: Boolean): StripUi? = when {
    heard == null -> null
    heard.playing && following == Following.Off -> StripUi.BackOnly
    !heard.playing && !sentenceOnPage -> StripUi.BackAndReadPage
    else -> null
}
