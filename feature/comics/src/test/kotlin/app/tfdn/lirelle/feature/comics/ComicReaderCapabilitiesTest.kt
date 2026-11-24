package app.tfdn.lirelle.feature.comics

import app.cash.turbine.test
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.NextVolume
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.KomgaLikeCapabilities
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** The end of an album from a Komga-like source: no rating, the next album kept (spec 2c). */
@OptIn(ExperimentalCoroutinesApi::class)
class ComicReaderCapabilitiesTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val following = NextVolume(bookId = BookId("15"), title = "Arctic-Nation", seriesIndex = "2")

    private fun TestScope.atTheEnd(harness: ComicHarness): ComicReaderViewModel {
        harness.repository.nextVolumeResult = ApiResult.Success(following)
        val reader = harness.reader()
        runCurrent()
        reader.goTo(Pages - 1)
        reader.next()
        runCurrent()
        return reader
    }

    @Test
    fun `the panel has no rating without ratings, and keeps the next album`() = runTest {
        val harness = ComicHarness(this, capabilities = KomgaLikeCapabilities)

        val reader = atTheEnd(harness)

        assertThat(reader.state.value.endPanel).isEqualTo(ComicEndPanel(nextVolume = following, ratings = false))
    }

    @Test
    fun `without ratings no reading is asked nor rated, and the next album opens at once`() = runTest {
        val harness = ComicHarness(this, capabilities = KomgaLikeCapabilities)
        val reader = atTheEnd(harness)
        reader.setRating(4)

        reader.events.test {
            reader.readNext()
            runCurrent()

            assertThat(awaitItem()).isEqualTo(ComicReaderEvent.OpenNext(BookId("15")))
        }
        assertThat(harness.repository.calls.filter { it.startsWith("finishedReading") }).isEmpty()
        assertThat(harness.repository.calls.filter { it.startsWith("rate") }).isEmpty()
    }
}
