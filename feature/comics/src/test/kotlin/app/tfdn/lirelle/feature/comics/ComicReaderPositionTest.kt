package app.tfdn.lirelle.feature.comics

import androidx.lifecycle.ViewModelStore
import app.cash.turbine.test
import app.tfdn.lirelle.core.comics.layout.Spread
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** The position written as the pages turn, the reading time, a newer position elsewhere, and leaving. */
@OptIn(ExperimentalCoroutinesApi::class)
class ComicReaderPositionTest {

    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun `a page turned is the position, with the server's number and its share of the album`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()

        reader.next()

        val kept = checkNotNull(harness.store.positions[AlbumFile])
        assertThat(kept.page).isEqualTo(2)
        assertThat(kept.cfi).isNull()
        assertThat(kept.percent).isWithin(1e-9).of(2 * 100.0 / Pages)
        assertThat(harness.repository.savedPositions).isEmpty()

        advanceTimeBy(3.seconds)

        val sent = harness.repository.savedPositions.single()
        assertThat(sent.page).isEqualTo(2)
        assertThat(sent.percent).isWithin(1e-9).of(2 * 100.0 / Pages)
    }

    @Test
    fun `with two pages the position is the first page, read up to the second`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()
        reader.windowSize(widthDp = 1280, heightDp = 800)

        reader.next()

        assertThat(reader.state.value.spread).isEqualTo(Spread(1, 2))
        val kept = checkNotNull(harness.store.positions[AlbumFile])
        assertThat(kept.page).isEqualTo(2)
        assertThat(kept.percent).isWithin(1e-9).of(3 * 100.0 / Pages)
    }

    @Test
    fun `the last page is the whole album`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()

        reader.goTo(Pages - 1)

        val kept = checkNotNull(harness.store.positions[AlbumFile])
        assertThat(kept.page).isEqualTo(Pages)
        assertThat(kept.percent).isEqualTo(100.0)
    }

    @Test
    fun `leaving writes the position at once, then the reading session`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()
        harness.clocks.advance(40.seconds)
        reader.next()

        reader.events.test {
            reader.leave()

            assertThat(awaitItem()).isEqualTo(ComicReaderEvent.Leave)
        }
        assertThat(harness.outbox.log.first()).isEqualTo("position $AlbumFile")
        assertThat(harness.outbox.log.last()).startsWith("session $AlbumFile")
        runCurrent()
        assertThat(harness.repository.savedPositions.single().page).isEqualTo(2)
        val session = harness.repository.savedSessions.single().second
        assertThat(session.activeSeconds).isEqualTo(40)
        assertThat(session.endPercent).isWithin(1e-9).of(2 * 100.0 / Pages)
    }

    @Test
    fun `a reader cleared without leaving writes the same, and gives the pages back`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()
        val owner = ViewModelStore().apply { put("reader", reader) }
        harness.clocks.advance(40.seconds)
        reader.next()

        owner.clear()

        assertThat(harness.outbox.log.first()).isEqualTo("position $AlbumFile")
        assertThat(harness.outbox.log.last()).startsWith("session $AlbumFile")
        assertThat(harness.pages.closed).isTrue()
    }

    @Test
    fun `in the background the position and the session so far are written, and no time counts`() = runTest {
        val harness = ComicHarness(this)
        val reader = harness.reader()
        harness.clocks.advance(40.seconds)
        reader.next()

        reader.onBackground()

        assertThat(harness.outbox.log.first()).isEqualTo("position $AlbumFile")
        assertThat(harness.outbox.sessions.single().session.activeSeconds).isEqualTo(40)

        harness.clocks.advance(2.minutes)
        reader.onForeground()
        harness.clocks.advance(20.seconds)
        reader.next()
        reader.leave()

        assertThat(harness.outbox.sessions.single().session.activeSeconds).isEqualTo(60)
    }

    @Test
    fun `a newer position on the server is offered when the reader comes back, and followed`() = runTest {
        val harness = ComicHarness(this)
        harness.serverHas(page = 12)
        val reader = harness.reader()
        reader.onForeground()
        assertThat(reader.state.value.newerPage).isNull()

        harness.serverHas(page = 20, secondsFromStart = 60)
        reader.onForeground()
        assertThat(reader.state.value.newerPage).isEqualTo(20)
        assertThat(reader.state.value.spread).isEqualTo(Spread(11))

        reader.acceptNewer()

        assertThat(reader.state.value.newerPage).isNull()
        assertThat(reader.state.value.spread).isEqualTo(Spread(19))
        assertThat(harness.pages.calls).contains("jump 19")
        advanceTimeBy(5.seconds)
        assertThat(harness.repository.savedPositions).isEmpty()
    }

    @Test
    fun `a newer position ignored is not offered again`() = runTest {
        val harness = ComicHarness(this)
        harness.serverHas(page = 12)
        val reader = harness.reader()
        harness.serverHas(page = 20, secondsFromStart = 60)
        reader.onForeground()

        reader.ignoreNewer()
        assertThat(reader.state.value.newerPage).isNull()
        assertThat(reader.state.value.spread).isEqualTo(Spread(11))

        reader.onForeground()
        assertThat(reader.state.value.newerPage).isNull()
    }
}
