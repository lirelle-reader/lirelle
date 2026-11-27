package app.tfdn.lirelle.source.kavita.history

import app.tfdn.lirelle.source.kavita.library.dto.HistoryChapterDto
import app.tfdn.lirelle.source.kavita.library.dto.HistoryItemDto
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.LocalDate
import org.junit.Test

/** The reads of a book built from Kavita's reading sessions: one per completion, the real sessions' time in it. */
class KavitaReadsTest {

    @Test
    fun `a chapter read over three real sessions, the last completed, is one read of their time`() {
        val reads = readsOf(
            listOf(
                item(session = 1, day = "2026-11-20", chapter = 44, completed = false, seconds = 600),
                item(session = 2, day = "2026-11-21", chapter = 44, completed = false, seconds = 300),
                item(session = 3, day = "2026-11-23", chapter = 44, completed = true, seconds = 120),
            ),
        )

        assertThat(reads).containsExactly(
            read(44, started = "2026-11-20", ended = "2026-11-23", readNumber = 1, seconds = 1020, sessions = 3),
        )
    }

    @Test
    fun `a chapter completed by a generated session only is a read with no time nor sessions`() {
        val reads = readsOf(
            listOf(
                item(session = 5, day = "2026-11-26", chapter = 44, completed = true, seconds = 1046, generated = true),
            ),
        )

        assertThat(reads).containsExactly(
            read(44, started = "2026-11-26", ended = "2026-11-26", readNumber = 1, seconds = null, sessions = null),
        )
    }

    @Test
    fun `real sessions then a generated completion count the real ones only`() {
        val reads = readsOf(
            listOf(
                item(session = 1, day = "2026-11-20", chapter = 44, completed = false, seconds = 600),
                item(session = 2, day = "2026-11-21", chapter = 44, completed = true, seconds = 947, generated = true),
            ),
        )

        assertThat(reads).containsExactly(
            read(44, started = "2026-11-20", ended = "2026-11-21", readNumber = 1, seconds = 600, sessions = 1),
        )
    }

    @Test
    fun `a real session of 0 seconds is a read of no time, but one session`() {
        val reads = readsOf(listOf(item(session = 1, day = "2026-11-20", chapter = 44, completed = true, seconds = 0)))

        assertThat(reads).containsExactly(
            read(44, started = "2026-11-20", ended = "2026-11-20", readNumber = 1, seconds = 0, sessions = 1),
        )
        assertThat(reads.single().duration).isEqualTo(Duration.ZERO)
    }

    @Test
    fun `a session over two days counts once`() {
        val reads = readsOf(
            listOf(
                item(session = 1, day = "2026-11-20", chapter = 44, completed = false, seconds = 600),
                item(session = 1, day = "2026-11-21", chapter = 44, completed = true, seconds = 60),
            ),
        )

        assertThat(reads).containsExactly(
            read(44, started = "2026-11-20", ended = "2026-11-21", readNumber = 1, seconds = 660, sessions = 1),
        )
    }

    @Test
    fun `a book read again is a second read, starting at the first session after the first end`() {
        val reads = readsOf(
            listOf(
                item(session = 1, day = "2026-11-01", chapter = 44, completed = true, seconds = 900),
                item(session = 2, day = "2026-11-05", chapter = 44, completed = false, seconds = 300),
                item(session = 3, day = "2026-11-06", chapter = 44, completed = true, seconds = 200),
            ),
        )

        assertThat(reads).containsExactly(
            read(44, started = "2026-11-05", ended = "2026-11-06", readNumber = 2, seconds = 500, sessions = 2),
            read(44, started = "2026-11-01", ended = "2026-11-01", readNumber = 1, seconds = 900, sessions = 1),
        ).inOrder()
    }

    @Test
    fun `sessions after the last completion give no read`() {
        val reads = readsOf(
            listOf(
                item(session = 1, day = "2026-11-01", chapter = 44, completed = true, seconds = 900),
                item(session = 2, day = "2026-11-05", chapter = 44, completed = false, seconds = 300),
                item(session = 3, day = "2026-11-05", chapter = 535, completed = false, seconds = 4),
            ),
        )

        assertThat(reads).containsExactly(
            read(44, started = "2026-11-01", ended = "2026-11-01", readNumber = 1, seconds = 900, sessions = 1),
        )
    }

    @Test
    fun `two chapters read in one session are each their own reads`() {
        val both = HistoryItemDto(
            sessionId = 4,
            startTimeUtc = "2026-11-10T10:04:00",
            localDate = "2026-11-10T00:00:00",
            seriesId = 65,
            seriesName = "Lucky Luke",
            chapters = listOf(
                HistoryChapterDto(861, "2026-11-10T10:04:00", durationSeconds = 300, completed = true),
                HistoryChapterDto(844, "2026-11-10T10:09:00", durationSeconds = 200, completed = false),
            ),
        )
        val later = item(session = 5, day = "2026-11-11", chapter = 844, completed = true, seconds = 100)
            .copy(seriesId = 65, seriesName = "Lucky Luke")

        val reads = readsOf(listOf(both, later))

        assertThat(reads).containsExactly(
            ChapterRead(
                chapterId = 844,
                seriesId = 65,
                seriesName = "Lucky Luke",
                startedOn = LocalDate.parse("2026-11-10"),
                endedOn = LocalDate.parse("2026-11-11"),
                readNumber = 1,
                duration = Duration.ofSeconds(300),
                sessions = 2,
            ),
            ChapterRead(
                chapterId = 861,
                seriesId = 65,
                seriesName = "Lucky Luke",
                startedOn = LocalDate.parse("2026-11-10"),
                endedOn = LocalDate.parse("2026-11-10"),
                readNumber = 1,
                duration = Duration.ofSeconds(300),
                sessions = 1,
            ),
        ).inOrder()
    }

    @Test
    fun `entries given out of order make the same reads`() {
        val entries = listOf(
            item(session = 1, day = "2026-11-01", chapter = 44, completed = true, seconds = 900),
            item(session = 2, day = "2026-11-05", chapter = 44, completed = false, seconds = 300),
            item(session = 3, day = "2026-11-06", chapter = 44, completed = true, seconds = 200),
            item(session = 4, day = "2026-11-07", chapter = 44, completed = false, seconds = 50),
        )

        assertThat(readsOf(listOf(entries[2], entries[0], entries[3], entries[1]))).isEqualTo(readsOf(entries))
        assertThat(readsOf(entries.reversed())).isEqualTo(readsOf(entries))
    }

    @Test
    fun `entries are put in order by the chapter's start time, not the item's`() {
        val first = item(session = 1, day = "2026-11-20", chapter = 44, completed = false, seconds = 600)
            .copy(startTimeUtc = "2026-11-20T12:00:00")
        val second = item(session = 2, day = "2026-11-20", chapter = 44, completed = true, seconds = 60)
            .copy(startTimeUtc = "2026-11-20T09:00:00")

        assertThat(readsOf(listOf(second, first))).containsExactly(
            read(44, started = "2026-11-20", ended = "2026-11-20", readNumber = 1, seconds = 660, sessions = 2),
        )
    }

    @Test
    fun `entries starting at the same time are put in order by session`() {
        fun at(entry: HistoryItemDto) = entry.copy(
            startTimeUtc = "2026-11-20T10:00:00",
            chapters = entry.chapters.map { it.copy(startTimeUtc = "2026-11-20T10:00:00") },
        )
        val first = at(item(session = 1, day = "2026-11-20", chapter = 44, completed = false, seconds = 600))
        val second = at(item(session = 2, day = "2026-11-20", chapter = 44, completed = true, seconds = 60))

        assertThat(readsOf(listOf(second, first))).containsExactly(
            read(44, started = "2026-11-20", ended = "2026-11-20", readNumber = 1, seconds = 660, sessions = 2),
        )
    }

    @Test
    fun `reads come newest end first, then by chapter, whatever the input order`() {
        val reads = readsOf(
            listOf(
                item(session = 1, day = "2026-11-10", chapter = 30, completed = true, seconds = 10),
                item(session = 2, day = "2026-11-20", chapter = 50, completed = true, seconds = 10),
                item(session = 3, day = "2026-11-20", chapter = 44, completed = true, seconds = 10),
            ),
        )

        assertThat(reads.map { it.chapterId }).containsExactly(44, 50, 30).inOrder()
    }

    @Test
    fun `an item of chapter 0 is left out`() {
        assertThat(
            readsOf(listOf(item(session = 1, day = "2026-11-20", chapter = 0, completed = true, seconds = 100))),
        ).isEmpty()
    }

    @Test
    fun `the day is Kavita's local date, else the UTC day of the start`() {
        val local = item(session = 1, day = "2026-11-21", chapter = 44, completed = true, seconds = 10)
            .let { item -> item.copy(chapters = item.chapters.map { it.copy(startTimeUtc = "2026-11-20T23:30:00") }) }
        val noLocal = item(session = 2, day = "2026-11-25", chapter = 50, completed = true, seconds = 10)
            .copy(localDate = null)

        val reads = readsOf(listOf(local, noLocal))

        assertThat(reads.map { it.chapterId to it.endedOn }).containsExactly(
            50 to LocalDate.parse("2026-11-25"),
            44 to LocalDate.parse("2026-11-21"),
        ).inOrder()
    }

    @Test
    fun `no sessions make no reads`() {
        assertThat(readsOf(emptyList())).isEmpty()
    }

    /** One item of Astérix with one chapter, started on [day] at 10:00 and [session] minutes. */
    @Suppress("LongParameterList") // Tests name every parameter they set.
    private fun item(
        session: Int,
        day: String,
        chapter: Int,
        completed: Boolean,
        seconds: Long,
        generated: Boolean = false,
    ): HistoryItemDto {
        val start = "${day}T10:${session.toString().padStart(2, '0')}:00"
        return HistoryItemDto(
            sessionId = session,
            isGeneratedSession = generated,
            startTimeUtc = start,
            localDate = "${day}T00:00:00",
            seriesId = 7,
            seriesName = "Astérix",
            libraryId = 1,
            chapters = listOf(HistoryChapterDto(chapter, start, seconds, completed)),
        )
    }

    /** A read of an Astérix chapter. */
    @Suppress("LongParameterList") // Tests name every parameter they set.
    private fun read(
        chapter: Int,
        started: String,
        ended: String,
        readNumber: Int,
        seconds: Long?,
        sessions: Int?,
    ) = ChapterRead(
        chapterId = chapter,
        seriesId = 7,
        seriesName = "Astérix",
        startedOn = LocalDate.parse(started),
        endedOn = LocalDate.parse(ended),
        readNumber = readNumber,
        duration = seconds?.let(Duration::ofSeconds),
        sessions = sessions,
    )
}
