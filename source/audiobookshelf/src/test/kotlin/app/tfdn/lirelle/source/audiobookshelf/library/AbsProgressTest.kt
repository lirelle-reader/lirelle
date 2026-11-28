package app.tfdn.lirelle.source.audiobookshelf.library

import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsMediaProgressDto
import app.tfdn.lirelle.source.audiobookshelf.testing.RecordedOn
import app.tfdn.lirelle.source.audiobookshelf.testing.recordedItem
import app.tfdn.lirelle.source.audiobookshelf.testing.recordedReadings
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test

private val Start: Instant = Instant.parse("2026-11-28T10:00:00Z")

/**
 * The account's progress, all of it in `GET api/me`: no record, or one at nothing yet, is unread, a record moved and
 * not finished is reading, a finished one read; the furthest of the audio's and the ebook's progress is shown; a podcast's is not a book's.
 * It is read again when what was read is more than ten seconds old.
 */
class AbsProgressTest {

    @Test
    fun `no record is unread, a record not finished reading, a finished one read, on both versions`() {
        RecordedOn.entries.forEach { on ->
            val readings = recordedReadings(on)
            fun idOf(title: String) = recordedItem(title, on = on).id

            assertWithMessage(on.name).that(readings.of(idOf("Mostly Harmless"))).isNull()
            assertWithMessage(on.name).that(readings.state(idOf("Mostly Harmless"))).isEqualTo(ReadState.Unread)
            assertWithMessage(on.name).that(readings.state(idOf("Le Petit Prince"))).isEqualTo(ReadState.Read)
            val guide = readings.of(idOf("The Hitchhiker's Guide to the Galaxy"))!!
            assertWithMessage(on.name).that(guide.state).isEqualTo(ReadState.Reading)
            assertWithMessage(on.name).that(guide.percent).isEqualTo(92)
        }
    }

    @Test
    fun `the furthest of the audio's and the ebook's progress is shown`() {
        val readings = recordedReadings()
        val ebook = readings.of(recordedItem("Les Indes noires").id)!!

        assertThat(ebook.state).isEqualTo(ReadState.Reading)
        assertThat(ebook.percent).isEqualTo(44)
        assertThat(ebook.lastUpdate).isEqualTo(Instant.ofEpochMilli(1_795_438_469_090))
        assertThat(ebook.startedAt).isEqualTo(Instant.ofEpochMilli(1_795_438_469_090))

        val both = AbsReadings.of(listOf(record("b", progress = 0.3, ebookProgress = 0.555)))
        assertThat(both.of("b")!!.percent).isEqualTo(55)
    }

    @Test
    fun `a record not finished at nothing yet is unread, as the server's not-started filter says`() {
        val readings = AbsReadings.of(
            listOf(
                record("nothing", progress = 0.0, ebookProgress = null),
                record("zero", progress = 0.0, ebookProgress = 0.0, currentTime = 0.0),
                record("empty", progress = null, ebookProgress = null),
            ),
        )

        listOf("nothing", "zero", "empty").forEach { id ->
            assertWithMessage(id).that(readings.state(id)).isEqualTo(ReadState.Unread)
            assertWithMessage(id).that(readings.of(id)!!.percent).isNull()
        }
    }

    @Test
    fun `a record moved in its audio or its ebook is reading, as the server's in-progress filter says`() {
        val readings = AbsReadings.of(
            listOf(
                record("audio", progress = 0.0, currentTime = 12.0),
                record("ebook", progress = 0.0, ebookProgress = 0.001),
                record("finished", progress = 0.0, finished = true),
            ),
        )

        assertThat(readings.state("audio")).isEqualTo(ReadState.Reading)
        assertThat(readings.state("ebook")).isEqualTo(ReadState.Reading)
        assertThat(readings.state("finished")).isEqualTo(ReadState.Read)
    }

    @Test
    fun `a podcast episode's record is no book's`() {
        RecordedOn.entries.forEach { on ->
            val readings = recordedReadings(on)

            // The episode's record names its podcast: that item is no book, and is not said started.
            assertWithMessage(on.name).that(readings.size).isEqualTo(7)
        }
        val episode = record("podcast", progress = 0.5, episodeId = "episode")
        assertThat(AbsReadings.of(listOf(episode)).of("podcast")).isNull()
    }

    @Test
    fun `what was read is kept ten seconds, then read again`() = runBlocking {
        var now = Start
        var reads = 0
        val progress = AbsProgress(read = { reads++; listOf(record("a", progress = 0.1 * reads)) }, now = { now })

        assertThat(progress.readings().of("a")!!.percent).isEqualTo(10)
        now = Start.plusSeconds(10)
        assertThat(progress.readings().of("a")!!.percent).isEqualTo(10)
        assertThat(reads).isEqualTo(1)

        now = Start.plusSeconds(11)
        assertThat(progress.readings().of("a")!!.percent).isEqualTo(20)
        assertThat(reads).isEqualTo(2)
    }

    @Test
    fun `one screen asking many times at once reads once`() = runBlocking {
        var reads = 0
        val progress = AbsProgress(
            read = { reads++; delay(50); listOf(record("a", progress = 0.5)) },
            now = { Start },
        )

        val all = (1..5).map { async(Dispatchers.Default) { progress.readings() } }.awaitAll()

        assertThat(all.map { it.of("a")!!.percent }.distinct()).containsExactly(50)
        assertThat(reads).isEqualTo(1)
    }

    @Test
    fun `a read that fails is tried again on the next ask`() = runBlocking {
        var reads = 0
        val progress = AbsProgress(
            read = {
                reads++
                if (reads == 1) error("down") else listOf(record("a", progress = 0.5))
            },
            now = { Start },
        )

        assertThat(runCatching { progress.readings() }.isFailure).isTrue()
        assertThat(progress.readings().of("a")!!.percent).isEqualTo(50)
    }
}

private fun record(
    itemId: String,
    progress: Double? = 0.0,
    ebookProgress: Double? = 0.0,
    episodeId: String? = null,
    finished: Boolean = false,
    currentTime: Double? = null,
) = AbsMediaProgressDto(
    libraryItemId = itemId,
    episodeId = episodeId,
    progress = progress,
    ebookProgress = ebookProgress,
    isFinished = finished,
    currentTime = currentTime,
)
