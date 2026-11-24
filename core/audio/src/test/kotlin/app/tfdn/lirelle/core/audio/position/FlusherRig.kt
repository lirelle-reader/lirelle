package app.tfdn.lirelle.core.audio.position

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.AudioRepository
import app.tfdn.lirelle.core.library.MonotonicClock
import app.tfdn.lirelle.core.library.ReadingChanges
import app.tfdn.lirelle.core.library.model.AudioAsset
import app.tfdn.lirelle.core.library.model.AudioManifest
import app.tfdn.lirelle.core.library.model.PlaybackState
import app.tfdn.lirelle.core.library.model.PlaybackWrite
import app.tfdn.lirelle.core.library.model.PlaybackWriteResult
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.reader.sync.RetryPolicy
import app.tfdn.lirelle.core.testing.FakeAudioRepository
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime

/** What the tests of the [PlaybackFlusher] share: fakes, answers and what the flusher told. */
abstract class FlusherRig {
    protected val position = samplePlayback()
    protected val repository = FakeAudioRepository()
    protected val outbox = InMemoryPlaybackOutbox()
    protected val store = InMemoryPlaybackStore()
    protected val changes = ReadingChanges()
    /** The books whose position was dropped against a state written elsewhere, as [writeEvents] heard. */
    protected val conflicts = mutableListOf<BookId>()
    protected val manifestChanges = mutableListOf<BookId>()
    protected val sessions = InMemorySessionQueue()

    /** What the flusher told of each write: "written <op> <acknowledged>", "dropped <op> <version>". */
    protected val writeEvents = mutableListOf<String>()
    protected val writes = object : PlaybackWriteEvents {
        override suspend fun written(position: LocalPlayback, acknowledged: Boolean) {
            writeEvents += "written ${position.operationId} $acknowledged"
        }

        override suspend fun dropped(position: LocalPlayback, server: PlaybackState?) {
            writeEvents += "dropped ${position.operationId} ${server?.version}"
            conflicts += position.bookId
        }
    }
    private val policy = RetryPolicy(firstWait = 5.seconds, longestWait = 40.seconds, unclearAttempts = 3)

    protected fun TestScope.flusher(
        audio: AudioRepository = repository,
        baselines: ReadingBaselines? = null,
    ) = PlaybackFlusher(
        outbox = outbox,
        store = store,
        repository = audio,
        scope = backgroundScope,
        changes = changes,
        policy = policy,
        onManifestChanged = { manifestChanges += it },
    ).also {
        it.clock = MonotonicClock { currentTime }
        it.sessions = sessions
        it.writes = writes
        it.baselines = baselines
    }

    protected fun saved(version: String): ApiResult<PlaybackWriteResult> =
        ApiResult.Success(PlaybackWriteResult.Saved(state(version, position.at)))

    /** A newer state stands elsewhere: the write was not made over it. */
    protected val conflict: ApiResult<PlaybackWriteResult> = ApiResult.Success(PlaybackWriteResult.Overtaken)

    protected val manifestChanged: ApiResult<PlaybackWriteResult> =
        ApiResult.Success(PlaybackWriteResult.ContentChanged)

    protected fun failed(error: ApiError) = ApiResult.Failure(error)

    /** The server's state of book 12 at [version], captured at [capturedAt]. */
    protected fun state(version: String, capturedAt: Instant?) =
        PlaybackState("aud_one", 80_000, 1.0, false, capturedAt, version, SampleContent)

    protected val sentWrites: List<PlaybackWrite> get() = repository.writes.map { it.second }

    protected companion object {
        val NewContent = "b".repeat(64)

        /** The manifest of book 12 once its files changed: [count] files of [fileMs] each. */
        fun newFiles(count: Int = 2, fileMs: Long = 60_000) = AudioManifest(
            bookId = SampleBook,
            contentVersion = NewContent,
            title = "A Sample Book",
            authors = listOf("Ann Author"),
            narrators = emptyList(),
            hasCover = false,
            assets = (1..count).map { AudioAsset("aud_new$it", FileId("${100 + it}"), it, "mp3", fileMs, null, null) },
            chapters = emptyList(),
            totalDurationMs = count * fileMs,
        )
    }
}
