package app.tfdn.lirelle.feature.player.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.audio.narration.NarrationPositionWriter
import app.tfdn.lirelle.core.audio.position.PlaybackFlusher
import app.tfdn.lirelle.core.audio.position.PositionWriter
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.library.ReadingChanges
import app.tfdn.lirelle.core.reader.sync.OutboxFlusher
import app.tfdn.lirelle.core.testing.FakeAudioRepository
import app.tfdn.lirelle.core.testing.FakeReaderRepository
import app.tfdn.lirelle.core.testing.InMemoryOutbox
import com.google.common.truth.Truth.assertThat
import java.time.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** Keeps the last book in memory. */
class MemoryLastBookStore(var book: LastBook? = null) : LastBookStore {
    override suspend fun get() = book


    override suspend fun put(book: LastBook) {
        this.book = book
    }

    override suspend fun clear() {
        book = null
    }
}

/** What follows each book in the service: where its positions go, whether it is the last book, its activity. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class BookFollowersTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val audiobook = LoadedBook(
        BookId("42"),
        BookPlaylist.of(Manifests.twoFiles(), null, { "Part $it" }) { "https://b/$it" }!!,
        SeriesId("4"),
        0,
        1f,
    )
    private val narration = narrationOf(ReadAlongMaps.inventedMap())

    private fun TestScope.writers(): Pair<PositionWriter, NarrationPositionWriter> {
        val clock = MonotonicClock { 0 }
        val outbox = InMemoryOutbox()
        val store = InMemoryPlaybackStore()
        val playbackFlusher = PlaybackFlusher(
            RecordingOutbox(), store, FakeAudioRepository(), backgroundScope, ReadingChanges(),
        )
        val narrationFlusher = OutboxFlusher(
            outbox, FakeReaderRepository(), backgroundScope, Clock.systemUTC(), ReadingChanges(),
        )
        return PositionWriter(store, RecordingOutbox(), playbackFlusher, clock) to
            NarrationPositionWriter(InMemoryNarrationStore(), outbox, narrationFlusher, clock)
    }

    /** The app has a launcher, as the real one does. */
    private fun withLauncher() {
        val launcher = ComponentName(context, "app.tfdn.lirelle.MainActivity")
        val packages = shadowOf(context.packageManager)
        packages.addActivityIfNotPresent(launcher)
        packages.addIntentFilterForActivity(
            launcher,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    @Test
    fun `an audiobook's positions go to the playback writer, a narration's to the narration writer`() = runTest {
        val (playback, narrations) = writers()

        assertThat(sinkOf(audiobook, playback, narrations, Clock.systemUTC())).isInstanceOf(PlaybackSink::class.java)
        assertThat(sinkOf(narration, playback, narrations, Clock.systemUTC())).isInstanceOf(NarrationSink::class.java)
    }

    @Test
    fun `an audiobook opened is the last book, until it is closed`() = runTest {
        val store = MemoryLastBookStore()
        val keeper = LastBookKeeper(store, backgroundScope)

        keeper.opened(audiobook)
        runCurrent()
        assertThat(store.book).isEqualTo(LastBook(BookId("42"), "The Long Road", null))

        keeper.closed()
        runCurrent()
        assertThat(store.book).isNull()
    }

    /** With the profile's real store: the mark is what a play button's receiver looks for. */
    @Test
    fun `closed because its profile closes, the audiobook stays the last book, with its mark`() = runTest {
        val mark = newLastBookMark()
        val store = lastBookStore(mark = mark)
        val keeper = LastBookKeeper(store, backgroundScope)
        keeper.opened(audiobook)
        runCurrent()

        keeper.profileCloses()
        keeper.closed()
        runCurrent()

        assertThat(store.get()).isEqualTo(LastBook(BookId("42"), "The Long Road", null))
        assertThat(mark.exists()).isTrue()
    }

    @Test
    fun `closed by the user, the audiobook is forgotten in the profile's store, with its mark`() = runTest {
        val mark = newLastBookMark()
        val store = lastBookStore(mark = mark)
        val keeper = LastBookKeeper(store, backgroundScope)
        keeper.opened(audiobook)
        runCurrent()
        assertThat(mark.exists()).isTrue()

        keeper.closed()
        runCurrent()

        assertThat(store.get()).isNull()
        assertThat(mark.exists()).isFalse()
    }

    /** No audiobook was kept while it played: the profile closing brings none back, and forgets none. */
    @Test
    fun `a narration closed because its profile closes leaves the store as it is`() = runTest {
        val store = MemoryLastBookStore()
        val keeper = LastBookKeeper(store, backgroundScope)
        keeper.opened(narration)
        runCurrent()

        keeper.profileCloses()
        keeper.closed()
        runCurrent()

        assertThat(store.book).isNull()
    }

    @Test
    fun `a narration opened over an audiobook forgets it, and closing the narration changes nothing more`() =
        runTest {
            val store = MemoryLastBookStore()
            val keeper = LastBookKeeper(store, backgroundScope)
            keeper.opened(audiobook)
            runCurrent()

            keeper.opened(narration)
            runCurrent()
            assertThat(store.book).isNull()

            // Whatever is kept by then stays: closing a narration does not touch the store.
            store.book = LastBook(BookId("7"), "Elsewhere", null)
            keeper.closed()
            runCurrent()
            assertThat(store.book).isEqualTo(LastBook(BookId("7"), "Elsewhere", null))
        }

    @Test
    fun `an audiobook opened after a narration is the last book again`() = runTest {
        val store = MemoryLastBookStore()
        val keeper = LastBookKeeper(store, backgroundScope)
        keeper.opened(narration)

        keeper.opened(audiobook)
        runCurrent()
        assertThat(store.book).isEqualTo(LastBook(BookId("42"), "The Long Road", null))

        keeper.closed()
        runCurrent()
        assertThat(store.book).isNull()
    }

    @Test
    fun `a narration's notification opens the app asking for the reader of its book`() {
        withLauncher()

        val intent = shadowOf(context.sessionActivity(narration.readerBookId, "p1")).savedIntent

        assertThat(intent.getStringExtra(PlayerSessionContract.EXTRA_OPEN_READER_BOOK_ID)).isEqualTo("42")
        assertThat(intent.component!!.className).isEqualTo("app.tfdn.lirelle.MainActivity")
    }

    /** The same id is another book for the account that signs in next. */
    @Test
    fun `a narration's notification names the profile its book is a book of`() {
        withLauncher()

        val first = shadowOf(context.sessionActivity(narration.readerBookId, "p1")).savedIntent
        assertThat(first.getStringExtra(PlayerSessionContract.EXTRA_OPEN_READER_PROFILE_ID)).isEqualTo("p1")

        // The same pending intent, made again for the next profile: not left with the id of the one before.
        val next = shadowOf(context.sessionActivity(narration.readerBookId, "p2")).savedIntent
        assertThat(next.getStringExtra(PlayerSessionContract.EXTRA_OPEN_READER_PROFILE_ID)).isEqualTo("p2")
    }

    @Test
    fun `an audiobook's notification opens the app as it always did, never with the reader's intent`() {
        withLauncher()

        val player = shadowOf(context.sessionActivity(audiobook.readerBookId, "p1"))
        val reader = shadowOf(context.sessionActivity(narration.readerBookId, "p1"))

        assertThat(player.savedIntent.hasExtra(PlayerSessionContract.EXTRA_OPEN_READER_BOOK_ID)).isFalse()
        assertThat(player.requestCode).isNotEqualTo(reader.requestCode)
        assertThat(shadowOf(context.sessionActivity(null, "p1")).savedIntent.extras).isNull()
    }

    @Test
    fun `the session's extras name the reader of a narration only`() {
        val book = SessionBook(BookId("42"), "The Long Road", emptyList(), emptyList(), null, 25_000)

        assertThat(PlayerSessionExtras(book = book, narrationFileId = FileId("1")).readerBookId)
            .isEqualTo(BookId("42"))
        assertThat(PlayerSessionExtras(book = book).readerBookId).isNull()
        assertThat(PlayerSessionExtras().readerBookId).isNull()
    }
}
