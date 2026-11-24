package app.tfdn.lirelle.feature.comics

import androidx.lifecycle.ViewModelStore
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** Pages turned while the server is slow to answer, and the reader left before it did: none is lost. */
@OptIn(ExperimentalCoroutinesApi::class)
class ComicReaderLeavingTest {

    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun `pages turned while the server is asked for a newer position are written on leaving`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()
        val slow = CompletableDeferred<Unit>()
        harness.repository.gate = slow
        reader.onForeground()
        harness.clocks.advance(40.seconds)

        reader.next()
        reader.next()
        reader.leave()

        assertThat(harness.store.positions[AlbumFile]?.page).isEqualTo(3)
        assertThat(harness.outbox.positions.single().page).isEqualTo(3)
        assertThat(harness.outbox.log.last()).startsWith("session $AlbumFile")

        slow.complete(Unit)
        runCurrent()
        assertThat(harness.repository.savedPositions.last().page).isEqualTo(3)
    }

    @Test
    fun `pages turned while the server is asked are written when the reader is cleared`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()
        val owner = ViewModelStore().apply { put("reader", reader) }
        val slow = CompletableDeferred<Unit>()
        harness.repository.gate = slow
        reader.onForeground()

        reader.next()
        reader.next()
        owner.clear()

        assertThat(harness.store.positions[AlbumFile]?.page).isEqualTo(3)
        assertThat(harness.outbox.positions.single().page).isEqualTo(3)
        slow.complete(Unit)
    }

    @Test
    fun `a page turned and turned back while the server is asked leaves the last one`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()
        val slow = CompletableDeferred<Unit>()
        harness.repository.gate = slow
        reader.onForeground()

        reader.next()
        reader.next()
        reader.previous()
        reader.leave()

        assertThat(harness.outbox.positions.single().page).isEqualTo(2)
        slow.complete(Unit)
    }
}
