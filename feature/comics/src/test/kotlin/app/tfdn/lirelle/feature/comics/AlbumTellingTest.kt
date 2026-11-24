package app.tfdn.lirelle.feature.comics

import app.tfdn.lirelle.core.comics.panels.WholePage
import app.tfdn.lirelle.core.comics.panels.Zone
import app.tfdn.lirelle.core.comics.panels.ZoneKind
import app.tfdn.lirelle.core.comics.settings.ComicSettingsStore
import app.tfdn.lirelle.core.comics.settings.DirectionScope
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.testing.InMemoryComicSettingsStore
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private val Series = DirectionScope.Series(SeriesId("4"))
private val Panels =
    listOf(Zone(0.1f, 0.1f, 0.9f, 0.4f, ZoneKind.Filled), Zone(0.1f, 0.5f, 0.9f, 0.9f, ZoneKind.Filled))

/** A store that says whether an album was told only once the test lets it: the time a real one takes. */
private class SlowStore(private val kept: ComicSettingsStore) : ComicSettingsStore by kept {
    val answers = CompletableDeferred<Unit>()

    override suspend fun albumTold(bookId: BookId): Boolean {
        answers.await()
        return kept.albumTold(bookId)
    }
}

/** An album that lends itself badly to reading panel by panel says so, once. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AlbumTellingTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val kept = InMemoryComicSettingsStore(panels = setOf(Series), noticeSeen = true)

    /** A reader of the album, in the mode, on its first page; none of its first pages is cut but [cut]. */
    private fun TestScope.album(vararg cut: Int, store: ComicSettingsStore = kept): Reading {
        val harness = ComicHarness(this, settings = store)
        (0 until 12).forEach {
            if (it in cut) harness.panels.cut(it, Panels) else harness.panels.whole(it, WholePage.NoPanels)
        }
        return Reading(harness, harness.reader())
    }

    private class Reading(val harness: ComicHarness, val reader: ComicReaderViewModel) {
        val banner: Boolean get() = reader.state.value.panels.albumBanner

        /** Reads on until the page [index] is shown, from the page before it. */
        fun readTo(index: Int) = repeat(index - reader.state.value.spreadIndex) { reader.next() }
    }

    @Test
    fun `six pages read of which none is cut raise the banner`() = runTest {
        val album = album()

        album.readTo(4)
        assertThat(album.banner).isFalse()

        album.readTo(5)
        assertThat(album.banner).isTrue()
    }

    @Test
    fun `the album is told once its banner is answered, not when it is raised`() = runTest {
        val album = album()
        album.readTo(5)
        assertThat(kept.told).isEmpty()

        album.reader.closeAlbumBanner()

        assertThat(kept.told).containsExactly(AlbumBook)
    }

    @Test
    fun `turning the mode off from the banner tells the album too`() = runTest {
        val album = album()
        album.readTo(5)

        // What the banner's button does.
        album.reader.setPanelMode(false)
        album.reader.closeAlbumBanner()

        assertThat(kept.told).containsExactly(AlbumBook)
    }

    @Test
    fun `a banner raised and never answered shows again at the next opening`() = runTest {
        album().readTo(5)
        assertThat(kept.told).isEmpty()

        val again = album()
        again.readTo(5)

        assertThat(again.banner).isTrue()
    }

    @Test
    fun `a banner that waits behind the offer of a newer page is not answered by the mode turned off`() = runTest {
        val album = album()
        album.harness.serverHas(page = 20, secondsFromStart = 60)
        album.reader.onForeground()
        assertThat(album.reader.state.value.newerPage).isEqualTo(20)
        album.readTo(5)
        assertThat(album.banner).isTrue()

        album.reader.setPanelMode(false)

        assertThat(album.banner).isFalse()
        assertThat(kept.told).isEmpty()
    }

    @Test
    fun `the mode turned off while the store is asked, the banner is not raised, nor the album told`() = runTest {
        val slow = SlowStore(kept)
        val album = album(store = slow)
        album.readTo(5)
        assertThat(album.banner).isFalse()

        album.reader.setPanelMode(false)
        slow.answers.complete(Unit)

        assertThat(album.banner).isFalse()
        assertThat(kept.told).isEmpty()
    }

    @Test
    fun `the mode still on when the store answers, the banner is raised`() = runTest {
        val slow = SlowStore(kept)
        val album = album(store = slow)
        album.readTo(5)
        assertThat(album.banner).isFalse()

        slow.answers.complete(Unit)

        assertThat(album.banner).isTrue()
    }

    @Test
    fun `six pages of which one is cut raise it too, two cut pages never do`() = runTest {
        val one = album(3)
        one.readTo(5)
        assertThat(one.banner).isTrue()

        val two = album(1, 3, store = InMemoryComicSettingsStore(panels = setOf(Series), noticeSeen = true))
        two.readTo(11)
        assertThat(two.banner).isFalse()
    }

    @Test
    fun `a cut that failed is not counted`() = runTest {
        val album = album()
        album.harness.panels.whole(2, WholePage.Failed)

        album.readTo(5)
        assertThat(album.banner).isFalse()

        album.readTo(6)
        assertThat(album.banner).isTrue()
    }

    @Test
    fun `a page seen twice counts once`() = runTest {
        val album = album()

        repeat(4) {
            album.reader.next()
            album.reader.next()
            album.reader.previous()
            album.reader.previous()
        }

        assertThat(album.banner).isFalse()
    }

    @Test
    fun `a page counts when what it is becomes known, though it was shown before`() = runTest {
        val album = album()
        album.harness.panels.forget(5)
        album.readTo(5)
        assertThat(album.banner).isFalse()

        album.harness.panels.whole(5, WholePage.NoPanels)

        assertThat(album.banner).isTrue()
    }

    @Test
    fun `pages read with the mode off are not counted, the zones shown or not`() = runTest {
        val album = album(store = InMemoryComicSettingsStore(noticeSeen = true))
        album.reader.showZones(true)

        album.readTo(8)

        assertThat(album.banner).isFalse()
    }

    @Test
    fun `closing the banner leaves the mode on, and it does not come back`() = runTest {
        val album = album()
        album.readTo(5)

        album.reader.closeAlbumBanner()

        assertThat(album.banner).isFalse()
        assertThat(album.reader.state.value.panels.on).isTrue()

        album.readTo(11)
        assertThat(album.banner).isFalse()
    }

    @Test
    fun `turning the mode off closes the banner`() = runTest {
        val album = album()
        album.readTo(5)

        album.reader.setPanelMode(false)

        assertThat(album.banner).isFalse()
        assertThat(album.reader.state.value.panels.on).isFalse()
        assertThat(kept.panels).isEmpty()
    }

    @Test
    fun `an album already told is not told again at its next opening`() = runTest {
        val first = album()
        first.readTo(5)
        first.reader.closeAlbumBanner()

        val again = album()
        again.readTo(11)

        assertThat(again.banner).isFalse()
        assertThat(kept.told).containsExactly(AlbumBook)
    }

    @Test
    fun `the pages of a spread of two both count`() = runTest {
        val album = album()
        album.reader.windowSize(widthDp = 1280, heightDp = 800)
        assertThat(album.reader.state.value.twoPages).isTrue()

        // The cover, then the pages 1 and 2, then 3 and 4: five pages.
        album.readTo(2)
        assertThat(album.banner).isFalse()

        album.readTo(3)
        assertThat(album.banner).isTrue()
    }
}
