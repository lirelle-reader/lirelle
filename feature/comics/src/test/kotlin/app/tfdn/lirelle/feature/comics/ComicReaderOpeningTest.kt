package app.tfdn.lirelle.feature.comics

import app.tfdn.lirelle.core.comics.ComicKey
import app.tfdn.lirelle.core.comics.PageCountResult
import app.tfdn.lirelle.core.comics.layout.Spread
import app.tfdn.lirelle.core.comics.settings.DirectionScope
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import app.tfdn.lirelle.core.testing.sampleBookDetail
import app.tfdn.lirelle.core.testing.sampleComicDetail
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** How an album opens: what it is, where, and what is said when it cannot. */
@OptIn(ExperimentalCoroutinesApi::class)
class ComicReaderOpeningTest {

    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun `an album opens with its title, its series and its pages`() = runTest {
        val harness = ComicHarness(this)

        val state = harness.reader().state.value

        assertThat(state.phase).isEqualTo(ComicPhase.Ready)
        assertThat(state.title).isEqualTo("A Complete Album")
        assertThat(state.series).isEqualTo(SeriesLine("A Saga", "2"))
        assertThat(state.directionScope).isEqualTo(DirectionScope.Series(SeriesId("4")))
        assertThat(state.pageCount).isEqualTo(Pages)
        assertThat(state.spreads).hasSize(Pages)
        assertThat(state.spread).isEqualTo(Spread(0))
        val file = checkNotNull(sampleComicDetail().pagedFile)
        assertThat(harness.opener.opened)
            .containsExactly(ComicKey(file.id, file.contentVersion, PagedKind.Comic, AlbumBook))
    }

    @Test
    fun `an album opens at the page the server holds, which counts from 1`() = runTest {
        val harness = ComicHarness(this)
        harness.serverHas(page = 12)

        val state = harness.reader().state.value

        assertThat(state.spread).isEqualTo(Spread(11))
        assertThat(harness.pages.calls).contains("show 11")
    }

    @Test
    fun `an album opens at the page of the device when it is newer and was never sent`() = runTest {
        val harness = ComicHarness(this)
        harness.serverHas(page = 12)
        harness.deviceHas(page = 24, secondsFromStart = 60)

        assertThat(harness.reader().state.value.spread).isEqualTo(Spread(23))
    }

    @Test
    fun `an album read again opens at its first page`() = runTest {
        val harness = ComicHarness(this)
        harness.serverHas(page = 12)

        assertThat(harness.reader(fromStart = true).state.value.spread).isEqualTo(Spread(0))
    }

    @Test
    fun `a position without a page opens at the page its percentage gives`() = runTest {
        val harness = ComicHarness(this)
        harness.serverHas(page = null, percent = 50.0)

        assertThat(harness.reader().state.value.spread).isEqualTo(Spread(23))
    }

    @Test
    fun `opening an album writes no position`() = runTest {
        val harness = ComicHarness(this)

        harness.reader()
        advanceTimeBy(5.seconds)

        assertThat(harness.store.positions).isEmpty()
        assertThat(harness.outbox.positions).isEmpty()
    }

    @Test
    fun `a book without a comic file says so`() = runTest {
        val harness = ComicHarness(this, detail = sampleBookDetail())

        val state = harness.reader().state.value

        assertThat(state.phase).isEqualTo(ComicPhase.Failed(ComicFailure.NoComic))
        assertThat(state.title).isEqualTo("A Complete Book")
        assertThat(harness.opener.opened).isEmpty()
    }

    @Test
    fun `a book the server does not give fails with its reason`() = runTest {
        val harness = ComicHarness(this)
        harness.library.bookResult = ApiResult.Failure(ApiError.ServerUnreachable)

        val phase = harness.reader().state.value.phase

        assertThat(phase).isEqualTo(ComicPhase.Failed(ComicFailure.Load(ApiError.ServerUnreachable)))
    }

    @Test
    fun `a page count that fails is asked again with the retry`() = runTest {
        val harness = ComicHarness(this)
        harness.serverHas(page = 12)
        harness.pages.countResult = PageCountResult.Failed(ApiError.ServerUnreachable)
        val reader = harness.reader()
        assertThat(reader.state.value.phase)
            .isEqualTo(ComicPhase.Failed(ComicFailure.Load(ApiError.ServerUnreachable)))

        harness.pages.countResult = PageCountResult.Known(Pages)
        reader.retryOpening()

        assertThat(reader.state.value.phase).isEqualTo(ComicPhase.Ready)
        assertThat(reader.state.value.spread).isEqualTo(Spread(11))
        assertThat(harness.pages.countAsked).isEqualTo(2)
        assertThat(harness.opener.opened).hasSize(1)
    }

    @Test
    fun `an album without a page cannot be read`() = runTest {
        val harness = ComicHarness(this)
        harness.pages.countResult = PageCountResult.Known(0)

        assertThat(harness.reader().state.value.phase).isEqualTo(ComicPhase.Failed(ComicFailure.NoComic))
    }
}
