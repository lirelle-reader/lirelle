package app.tfdn.lirelle.core.audio.position

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.AudioRepository
import app.tfdn.lirelle.core.library.ReaderRepository
import app.tfdn.lirelle.core.library.model.NarrationPlace
import app.tfdn.lirelle.core.library.model.PlaybackState
import app.tfdn.lirelle.core.library.model.PlaybackWrite
import app.tfdn.lirelle.core.library.model.PlaybackWriteResult
import app.tfdn.lirelle.core.library.model.SaveOutcome
import app.tfdn.lirelle.core.library.model.SavedPosition
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeAudioRepository
import app.tfdn.lirelle.core.testing.FakeReaderRepository
import java.time.Instant

/** Where the server puts the listening of book 12 when a reading is stored: the sentence read. */
const val SentenceMs = 311_000L

/**
 * A server that keeps the text and the audio of one read-along book in step, as the real one does: every
 * text or narration position it stores moves the listening state to the sentence read ([SentenceMs]),
 * dated [now] by its own clock, in a new version ("s1", "s2"…); a listening write made over another version
 * than the state's is overtaken.
 *
 * @property state the listening state it holds.
 * @property calls what it was asked, in order: "reading", "listening <position>" or "listening refused".
 */
class InStepServer(private val now: Instant) {
    var state: PlaybackState? = null
    val calls = mutableListOf<String>()
    private var stores = 0

    private fun stored(positionMs: Long, capturedAt: Instant) =
        PlaybackState("aud_one", positionMs, 1.0, false, capturedAt, "s${++stores}", SampleContent)

    private fun readingStored(): ApiResult<SavedPosition> {
        calls += "reading"
        state = stored(SentenceMs, now)
        return ApiResult.Success(SavedPosition(SaveOutcome.Saved, now))
    }

    val reader: ReaderRepository = object : ReaderRepository by FakeReaderRepository() {
        override suspend fun savePosition(
            fileId: FileId,
            cfi: String?,
            percent: Double,
            editedAt: Instant?,
            page: Int?,
        ) = readingStored()

        override suspend fun saveNarration(fileId: FileId, place: NarrationPlace, percent: Double, editedAt: Instant?) =
            readingStored()
    }

    val audio: AudioRepository = object : AudioRepository by FakeAudioRepository() {
        override suspend fun playbackState(bookId: BookId): ApiResult<PlaybackState?> = ApiResult.Success(state)

        override suspend fun savePlaybackState(bookId: BookId, write: PlaybackWrite): ApiResult<PlaybackWriteResult> {
            if (write.baseVersion != state?.version) {
                calls += "listening refused"
                return ApiResult.Success(PlaybackWriteResult.Overtaken)
            }
            calls += "listening ${write.positionMs}"
            val written = stored(write.positionMs, write.capturedAt)
            state = written
            return ApiResult.Success(PlaybackWriteResult.Saved(written))
        }
    }
}
