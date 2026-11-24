package app.tfdn.lirelle.core.comics.pdf

import app.tfdn.lirelle.core.comics.ComicKey
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.reader.cache.CacheState
import app.tfdn.lirelle.core.reader.cache.CachedBookKey
import app.tfdn.lirelle.core.reader.cache.ReaderCache
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

private val Patience = 10.seconds

/**
 * A cache whose file comes when the test says, and whose flow is still being collected after it: the place a
 * reader that leaves is cancelled at.
 */
private class LateCache : ReaderCache {
    val collecting = CompletableDeferred<Unit>()
    val coming = CompletableDeferred<Unit>()
    val given = CompletableDeferred<Unit>()
    val held = AtomicInteger()

    override fun open(bookId: BookId, key: CachedBookKey, audioless: Boolean): Flow<CacheState> = flow {
        collecting.complete(Unit)
        coming.await()
        emit(CacheState.Ready(File("document.pdf")))
        given.complete(Unit)
        awaitCancellation()
    }

    override fun hold(key: CachedBookKey) {
        held.incrementAndGet()
    }

    override fun release(key: CachedBookKey) {
        held.decrementAndGet()
    }

    override suspend fun discard(key: CachedBookKey) = Unit

    override fun cached(key: CachedBookKey): File? = null
}

/** The reader left while the document's file was on its way. */
class DocumentLeftTest {

    @Test
    fun `a file that comes once the reader left is not left held`(): Unit = runBlocking {
        val cache = LateCache()
        val source = DocumentSource(ComicKey(PdfFileId, "v1", PagedKind.Pdf, PdfBookId), cache)
        val document = OpenDocument(source) { error("Nobody is there to open it") }
        val count = launch(Dispatchers.Default) { document.count() }
        withTimeout(Patience) { cache.collecting.await() }

        // As the reader's scope ends: the document is closed, and what asked for its page count is cancelled.
        document.close()
        cache.coming.complete(Unit)
        withTimeout(Patience) { cache.given.await() }
        count.cancelAndJoin()

        assertThat(cache.held.get()).isEqualTo(0)
    }
}
