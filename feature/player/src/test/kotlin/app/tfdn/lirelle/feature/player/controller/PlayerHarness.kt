package app.tfdn.lirelle.feature.player.controller

import app.tfdn.lirelle.core.audio.narration.DefaultNarrationCursor
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.audio.position.ListeningConflicts
import app.tfdn.lirelle.core.audio.position.LocalPlayback
import app.tfdn.lirelle.core.audio.position.PlaybackFlusher
import app.tfdn.lirelle.core.audio.position.PlaybackOutbox
import app.tfdn.lirelle.core.audio.position.PlaybackSyncEvents
import app.tfdn.lirelle.core.audio.position.ReadingBaselines
import app.tfdn.lirelle.core.audio.bookmarks.AudioBookmarks
import app.tfdn.lirelle.core.downloads.DeviceCopySources
import app.tfdn.lirelle.core.library.AudioRepository
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.library.ReaderRepository
import app.tfdn.lirelle.core.library.ReadingChanges
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.PlaybackState
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeAudioRepository
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.core.testing.FakeReaderRepository
import app.tfdn.lirelle.feature.player.PlayerViewModel
import app.tfdn.lirelle.feature.player.bookmarks.FakeAudioBookmarks
import app.tfdn.lirelle.feature.player.service.FakeClosingDeletions
import app.tfdn.lirelle.feature.player.service.FakeDownloads
import app.tfdn.lirelle.feature.player.service.FinishedBook
import app.tfdn.lirelle.feature.player.service.InMemoryPlaybackStore
import app.tfdn.lirelle.feature.player.service.InMemoryPlayerSettingsStore
import app.tfdn.lirelle.feature.player.service.Manifests
import app.tfdn.lirelle.feature.player.service.PlayerCommand
import app.tfdn.lirelle.feature.player.service.PlayerSessionExtras
import app.tfdn.lirelle.feature.player.service.SessionBook
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/** A session that records what it is asked; the test sets what it shows. */
class FakePlayerConnection : PlayerConnection {
    override val snapshot = MutableStateFlow(SessionSnapshot())

    /** [PlayerCommand]s and the player calls, as "playPause", "seekTo chapter position". */
    val sent = mutableListOf<Any>()

    override fun send(command: PlayerCommand) {
        sent += command
    }

    override fun playPause() {
        sent += "playPause"
    }

    override fun setPlaying(playing: Boolean) {
        sent += if (playing) "play" else "pause"
    }

    override fun seekTo(chapterIndex: Int, positionMs: Long) {
        sent += "seekTo $chapterIndex $positionMs"
    }

    /** Reached at once, unless a test says otherwise. */
    override val connected = MutableStateFlow(true)

    override fun clear() {
        sent += "clear"
        snapshot.value = SessionSnapshot()
    }
}

class InMemoryPlaybackOutbox : PlaybackOutbox {
    val positions = mutableListOf<LocalPlayback>()

    override suspend fun enqueue(p: LocalPlayback) {
        positions.removeAll { it.bookId == p.bookId }
        positions += p
    }

    override suspend fun pending(): List<LocalPlayback> = positions.toList()

    override suspend fun markSent(p: LocalPlayback) {
        positions.remove(p)
    }

    override suspend fun replace(old: LocalPlayback, new: LocalPlayback) {
        val index = positions.indexOf(old)
        if (index >= 0) positions[index] = new
    }
}

/** The book of [Manifests.twoFiles] as the session shows it: chapters 0–60 s, 60–100 s, 100–160 s. */
object SessionBooks {
    val book = SessionBook(
        id = BookId("42"),
        title = "The Long Road",
        authors = listOf("Anne Verlaine"),
        narrators = listOf("Thomas Rivière"),
        coverUrl = "https://books.example/cover/42",
        totalMs = 160_000,
    )

    val chapters = listOf(
        SessionChapter("Opening", 0, 60_000, chapterId = "c1"),
        SessionChapter("Middle", 60_000, 100_000, chapterId = "c2"),
        SessionChapter("Closing", 100_000, 160_000, chapterId = "c3"),
    )

    fun at(
        chapter: Int = 1,
        positionMs: Long = 10_000,
        playing: Boolean = true,
        extras: PlayerSessionExtras = PlayerSessionExtras(book),
        speed: Float = 1f,
    ) = SessionSnapshot(extras, chapters, chapter, positionMs, playWhenReady = playing, speed = speed)

    fun finished(extras: PlayerSessionExtras = PlayerSessionExtras(book)) = at(
        chapter = 2,
        positionMs = 60_000,
        playing = false,
        extras = extras.copy(finished = FinishedBook(null)),
    )

    /** The server's state: 20 s into the second file, the book's 2:00. */
    fun server(at: Instant, version: String = "v7", assetId: String = "a2", positionMs: Long = 20_000) =
        PlaybackState(assetId, positionMs, 75.0, false, at, version, "rev-1")
}

/**
 * The narration of book 42 (read-along file 1) as the session shows it: section 1 "Opening" from 0 to 15 s,
 * section 3, without a title, from 15 to 25 s.
 */
object SessionNarrations {
    val book = SessionBooks.book.copy(totalMs = 25_000)

    val chapters = listOf(
        SessionChapter("Opening", 0, 15_000, sectionIndex = 1),
        SessionChapter("", 15_000, 25_000, sectionIndex = 3),
    )

    val extras = PlayerSessionExtras(book, narrationFileId = FileId("1"))

    fun at(
        chapter: Int = 1,
        positionMs: Long = 4_000,
        playing: Boolean = true,
        extras: PlayerSessionExtras = this.extras,
        speed: Float = 1f,
    ) = SessionSnapshot(extras, chapters, chapter, positionMs, playWhenReady = playing, speed = speed)
}

/** The wall clock of the harness: what "now" is for a position written at once. */
val Now: Instant = Instant.parse("2026-11-15T12:00:00Z")

/** Everything the controller needs, faked; [controller] is made on first use, in a scope of the test. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerHarness(private val test: TestScope) {
    val connection = FakePlayerConnection()

    /** Whether the playback service runs; it does, unless a test stops it. */
    val running = MutableStateFlow(true)
    val audio = FakeAudioRepository().apply { manifestResult = ApiResult.Success(Manifests.twoFiles()) }
    val reader = FakeReaderRepository()
    val store = InMemoryPlaybackStore()
    val outbox = InMemoryPlaybackOutbox()
    val changes = ReadingChanges()
    val settings = InMemoryPlayerSettingsStore()
    val syncEvents = PlaybackSyncEvents()
    var kicks = 0
    val conflicts = ListeningConflicts(store, outbox, Clock.fixed(Now, ZoneOffset.UTC)) { kicks++ }
    val scope = CoroutineScope(test.backgroundScope.coroutineContext + UnconfinedTestDispatcher(test.testScheduler))

    val baselines = ReadingBaselines(reader)

    /** What the device keeps of the books, and the deletions asked from the end panel. */
    val downloads = FakeDownloads()
    val deletions = FakeClosingDeletions(downloads)

    /** Where the narration is, as the service tells it. */
    val cursor = DefaultNarrationCursor()

    /** Another reader repository than [reader], set before the controller is made. */
    var readerRepository: ReaderRepository = reader

    /** Another audio repository than [audio], set before the controller is made. */
    var audioRepository: AudioRepository = audio

    /** What the profile's source can do, set before the controller is made. */
    var capabilities: SourceCapabilities = SourceCapabilities.Everything

    val controller: PlayerController by lazy {
        val sources = PlayerSources(
            audioRepository, readerRepository, store, outbox, changes, settings, syncEvents, conflicts, baselines,
        )
        val device = DeviceCopySources(downloads, deletions)
        val narration = NarrationSources(cursor) { "Section $it" }
        PlayerController(connection, sources, narration, device, scope, running, capabilities)
    }

    /** The app's flusher, as wired in the app: what stands, and the baselines. */
    fun flusher(): PlaybackFlusher = PlaybackFlusher(outbox, store, audio, scope, changes).also {
        it.writes = conflicts
        it.baselines = baselines
    }

    /** The controller's state, watched as a screen would. */
    fun watched(): StateFlow<PlayerUiState> {
        scope.launch { controller.state.collect {} }
        return controller.state
    }

    /** The controller's narration, watched as the reader would. */
    fun watchedNarration(): StateFlow<NarrationUi?> {
        scope.launch { controller.narration.collect {} }
        return controller.narration
    }

    /** The failure of a narration asked for and not held, watched as the reader would. */
    fun watchedFailure(): StateFlow<NarrationFailure?> {
        scope.launch { controller.narrationFailure.collect {} }
        return controller.narrationFailure
    }

    fun show(snapshot: SessionSnapshot) {
        connection.snapshot.value = snapshot
    }

    /** The player screen's view model over this controller; its bookmarks are changed in [scope]. */
    fun viewModel(
        library: LibraryRepository = FakeLibraryRepository(),
        bookmarks: AudioBookmarks = FakeAudioBookmarks(),
        capabilities: SourceCapabilities = SourceCapabilities.Everything,
    ): PlayerViewModel = PlayerViewModel(controller, library, changes, bookmarks, scope, capabilities)
}
