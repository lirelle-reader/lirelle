package app.tfdn.lirelle.feature.reader.narration

import app.tfdn.lirelle.core.audio.narration.HeardSentence
import app.tfdn.lirelle.feature.player.controller.NarrationUi
import app.tfdn.lirelle.feature.player.narration.NarrationActions
import app.tfdn.lirelle.feature.reader.ReaderCore
import kotlinx.coroutines.launch

/**
 * The bar's and the panel's controls as the reader passes them on, each move told to [onMove]: a section chosen
 * before the current one, a seek back in the section, or "Go back" (a move back only when it lands before the
 * sentence heard, which [PanelBackJumps] checks), is a move back the user meant (`true`); any other move, the
 * sentence buttons, the skips, play or pause and "Stop listening" included, is not (`false`).
 *
 * @param shown this book's narration as the player shows it, null when there is none.
 */
internal class ReaderPanelActions(
    private val inner: NarrationActions,
    private val shown: () -> NarrationUi?,
    private val onMove: (back: Boolean) -> Unit,
) : NarrationActions by inner {

    override fun goToSection(index: Int) {
        val now = shown()
        onMove(now != null && index < now.sectionNumber - 1)
        inner.goToSection(index)
    }

    /** A move like a section chosen: back the user meant when it lands before the sentence heard. */
    override fun goBack() {
        onMove(true)
        inner.goBack()
    }

    override fun seekInSection(fraction: Float) {
        val now = shown()
        onMove(now != null && fraction * now.sectionDurationMs < now.elapsedMs)
        inner.seekInSection(fraction)
    }

    override fun previousSentence() = notBack(inner::previousSentence)

    override fun nextSentence() = notBack(inner::nextSentence)

    override fun back() = notBack(inner::back)

    override fun forward() = notBack(inner::forward)

    override fun playPause() = notBack(inner::playPause)

    override fun stop() = notBack(inner::stop)

    private fun notBack(move: () -> Unit) {
        onMove(false)
        move()
    }
}

/**
 * A move back the user meant from the panel (spec 8.2): once the narration is at another sentence, and that one is
 * before the sentence heard when the move was asked, the text position goes there too, at once.
 */
internal class PanelBackJumps(
    private val core: ReaderCore,
    private val starts: NarrationStarts,
    private val following: NarrationFollowing,
) {
    /** The sentence heard when a move back was asked, until the narration has moved. */
    private var from: HeardSentence? = null

    /** A move from the bar or the panel: with [back], one back the user meant; any other forgets the last one. */
    fun mark(back: Boolean) {
        from = if (back) following.sentence else null
    }

    /** The sentence heard now, of this book's file, or null. */
    fun onHeard(now: HeardSentence?) {
        val before = from ?: return
        if (now != null && now.sectionIndex == before.sectionIndex && now.fragment == before.fragment) return
        from = null
        if (now == null) return
        core.scope.launch {
            val map = starts.map(quiet = true) ?: return@launch
            val was = map.sentenceOf(before.sectionIndex, before.fragment) ?: return@launch
            val landed = map.sentenceOf(now.sectionIndex, now.fragment) ?: return@launch
            if (landed.ordinal < was.ordinal) starts.askPosition(landed)
        }
    }
}
