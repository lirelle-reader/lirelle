package app.tfdn.lirelle.core.audio.narration

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** How many skipped sections wait for a slow collector before the oldest is dropped. */
private const val SkippedBuffer = 8

/**
 * The sentence a narration is at, shared in the process between the service and the reader.
 *
 * @property fragment the sentence's element id in [textHref], as the read-along map's sentence gives it (no
 *   `textHref#`); empty when the whole file is meant.
 * @property textHref the text file of the sentence.
 * @property playing whether the narration is playing, or paused on this sentence.
 */
data class HeardSentence(
    val bookId: BookId,
    val fileId: FileId,
    val sectionIndex: Int,
    val fragment: String,
    val textHref: String,
    val playing: Boolean,
)

/** A section the narration went past without reading it aloud; [title] is null when it has none. */
data class SkippedNotice(val bookId: BookId, val sectionIndex: Int, val title: String?)

/**
 * Where the narration is, for whoever shows it: the player's service tells, the reader follows. One for
 * the process.
 */
interface NarrationCursor {
    /** The sentence heard, the latest one to a new collector; null when no narration is loaded. */
    val sentence: StateFlow<HeardSentence?>

    /** Each section skipped, to the collectors active when it is; never replayed to a later one. */
    val skipped: SharedFlow<SkippedNotice>

    /**
     * The book whose narration ended. It stays until [finish] is told null, or a sentence of another
     * book is published; a sentence of the same book, or none, leaves it.
     */
    val finished: StateFlow<BookId?>

    /** A sentence equal to the one published last is not told again. */
    fun publish(sentence: HeardSentence?)

    fun announce(notice: SkippedNotice)

    fun finish(bookId: BookId?)
}

class DefaultNarrationCursor : NarrationCursor {
    private val heard = MutableStateFlow<HeardSentence?>(null)
    private val skips = MutableSharedFlow<SkippedNotice>(extraBufferCapacity = SkippedBuffer)
    private val ended = MutableStateFlow<BookId?>(null)

    override val sentence: StateFlow<HeardSentence?> = heard.asStateFlow()
    override val skipped: SharedFlow<SkippedNotice> = skips.asSharedFlow()
    override val finished: StateFlow<BookId?> = ended.asStateFlow()

    override fun publish(sentence: HeardSentence?) {
        val ending = ended.value
        if (sentence != null && ending != null && ending != sentence.bookId) ended.value = null
        heard.value = sentence
    }

    override fun announce(notice: SkippedNotice) {
        skips.tryEmit(notice)
    }

    override fun finish(bookId: BookId?) {
        ended.value = bookId
    }
}
