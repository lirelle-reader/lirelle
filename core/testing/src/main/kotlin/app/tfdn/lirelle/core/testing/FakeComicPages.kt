package app.tfdn.lirelle.core.testing

import app.tfdn.lirelle.core.comics.ComicKey
import app.tfdn.lirelle.core.comics.ComicPages
import app.tfdn.lirelle.core.comics.ComicPagesOpener
import app.tfdn.lirelle.core.comics.OpeningProgress
import app.tfdn.lirelle.core.comics.PageCountResult
import app.tfdn.lirelle.core.comics.PageState
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

private const val DefaultPageCount = 48

/**
 * Scriptable [ComicPages]: set [countResult] and the state of each page, then read in [calls] what the
 * reader asked, in order, as "show 3", "settle 3", "jump 3", "retry 3", "sharpen 3 4096". Every page is
 * loading until set.
 */
class FakeComicPages(
    var countResult: PageCountResult = PageCountResult.Known(DefaultPageCount),
) : ComicPages {
    private val states = mutableMapOf<Int, MutableStateFlow<PageState>>()

    /** What came of the document being fetched; null, as for an album fetched page by page, until set. */
    override val opening = MutableStateFlow<OpeningProgress?>(null)

    val calls = mutableListOf<String>()

    /** What the page count waits for before it answers, as a document being fetched; nothing until set. */
    var countGate: CompletableDeferred<Unit>? = null

    /** How many times the page count was asked. */
    var countAsked = 0
        private set
    var closed = false
        private set

    /** The page [index] is now in [state], for whoever watches it. */
    fun set(index: Int, state: PageState) {
        page(index).value = state
    }

    /** The page [index] is on the device, [width] by [height] pixels. */
    fun ready(index: Int, width: Int = 800, height: Int = 1200) =
        set(index, PageState.Ready(File("page-$index.jpg"), width, height))

    override suspend fun pageCount(): PageCountResult {
        countAsked++
        countGate?.await()
        return countResult
    }

    override fun page(index: Int): MutableStateFlow<PageState> =
        states.getOrPut(index) { MutableStateFlow(PageState.Loading) }

    override fun show(index: Int) {
        calls += "show $index"
    }

    override fun settle(index: Int) {
        calls += "settle $index"
    }

    override fun jump(index: Int) {
        calls += "jump $index"
    }

    override fun retry(index: Int) {
        calls += "retry $index"
    }

    override fun sharpen(index: Int, longSidePx: Int) {
        calls += "sharpen $index $longSidePx"
    }

    override fun close() {
        closed = true
    }
}

/** Opens the same [pages] for every album, and lists in [opened] the albums asked. */
class FakeComicPagesOpener(val pages: FakeComicPages = FakeComicPages()) : ComicPagesOpener {
    val opened = mutableListOf<ComicKey>()

    override fun open(key: ComicKey, scope: CoroutineScope): ComicPages {
        opened += key
        return pages
    }
}
