package app.tfdn.lirelle.core.audio.timeline

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.AudioAsset
import app.tfdn.lirelle.core.library.model.AudioChapter
import app.tfdn.lirelle.core.library.model.AudioManifest
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BookTimelineTest {

    // Three files of 10 min, 5 min and 20 min; the second file holds two chapters.
    private val first = asset("aud_a", sequence = 0, durationMs = 600_000)
    private val second = asset("aud_b", sequence = 1, durationMs = 300_000)
    private val third = asset("aud_c", sequence = 2, durationMs = 1_200_000)

    private val chapters = listOf(
        chapter("1", "aud_a", startMs = 0, endMs = 600_000, assetOffsetMs = 0),
        chapter("2", "aud_b", startMs = 600_000, endMs = 720_000, assetOffsetMs = 0),
        chapter("3", "aud_b", startMs = 720_000, endMs = 900_000, assetOffsetMs = 120_000),
        chapter("4", "aud_c", startMs = 900_000, endMs = 2_100_000, assetOffsetMs = 0),
    )

    // Given out of order, as nothing promises the server sorts them.
    private val timeline = BookTimeline(manifest(listOf(third, first, second), chapters.reversed()))

    @Test
    fun `the total is the sum of the files`() {
        assertThat(timeline.totalMs).isEqualTo(2_100_000)
    }

    @Test
    fun `chapters are in book order`() {
        assertThat(timeline.chapters.map { it.id }).containsExactly("1", "2", "3", "4").inOrder()
    }

    @Test
    fun `a position inside a file maps to book time`() {
        assertThat(timeline.bookTimeOf("aud_a", 0)).isEqualTo(0)
        assertThat(timeline.bookTimeOf("aud_a", 599_999)).isEqualTo(599_999)
        assertThat(timeline.bookTimeOf("aud_b", 0)).isEqualTo(600_000)
        assertThat(timeline.bookTimeOf("aud_c", 1_199_999)).isEqualTo(2_099_999)
    }

    @Test
    fun `a position beyond its file is clamped to the file`() {
        assertThat(timeline.bookTimeOf("aud_b", 999_999)).isEqualTo(900_000)
        assertThat(timeline.bookTimeOf("aud_b", -5)).isEqualTo(600_000)
    }

    @Test
    fun `an unknown file has no book time`() {
        assertThat(timeline.bookTimeOf("aud_x", 0)).isNull()
    }

    @Test
    fun `book time maps to a file and a position inside it`() {
        assertThat(timeline.assetPositionOf(0)).isEqualTo(first to 0L)
        assertThat(timeline.assetPositionOf(599_999)).isEqualTo(first to 599_999L)
        assertThat(timeline.assetPositionOf(600_000)).isEqualTo(second to 0L)
        assertThat(timeline.assetPositionOf(750_000)).isEqualTo(second to 150_000L)
        assertThat(timeline.assetPositionOf(900_000)).isEqualTo(third to 0L)
        assertThat(timeline.assetPositionOf(2_099_999)).isEqualTo(third to 1_199_999L)
    }

    @Test
    fun `book time outside the book is clamped to it`() {
        assertThat(timeline.assetPositionOf(2_100_000)).isEqualTo(third to 1_200_000L)
        assertThat(timeline.assetPositionOf(9_000_000)).isEqualTo(third to 1_200_000L)
        assertThat(timeline.assetPositionOf(-1)).isEqualTo(first to 0L)
    }

    @Test
    fun `the chapter at a time spans files`() {
        assertThat(timeline.chapterAt(0)).isEqualTo(0)
        assertThat(timeline.chapterAt(599_999)).isEqualTo(0)
        assertThat(timeline.chapterAt(600_000)).isEqualTo(1)
        assertThat(timeline.chapterAt(719_999)).isEqualTo(1)
        assertThat(timeline.chapterAt(720_000)).isEqualTo(2)
        assertThat(timeline.chapterAt(2_099_999)).isEqualTo(3)
        assertThat(timeline.chapterAt(9_000_000)).isEqualTo(3)
        assertThat(timeline.chapterAt(-1)).isEqualTo(0)
    }

    @Test
    fun `previous chapter within the grace goes to the previous chapter`() {
        assertThat(timeline.previousChapterTarget(720_000)).isEqualTo(600_000)
        assertThat(timeline.previousChapterTarget(722_999)).isEqualTo(600_000)
    }

    @Test
    fun `previous chapter after the grace goes to the start of the current one`() {
        assertThat(timeline.previousChapterTarget(723_000)).isEqualTo(720_000)
        assertThat(timeline.previousChapterTarget(800_000)).isEqualTo(720_000)
    }

    @Test
    fun `previous chapter honours a custom grace`() {
        assertThat(timeline.previousChapterTarget(725_000, graceMs = 10_000)).isEqualTo(600_000)
    }

    @Test
    fun `previous chapter on the first chapter goes to the start of the book`() {
        assertThat(timeline.previousChapterTarget(1_000)).isEqualTo(0)
        assertThat(timeline.previousChapterTarget(100_000)).isEqualTo(0)
    }

    @Test
    fun `next chapter goes to the start of the following one`() {
        assertThat(timeline.nextChapterTarget(0)).isEqualTo(600_000)
        assertThat(timeline.nextChapterTarget(719_999)).isEqualTo(720_000)
    }

    @Test
    fun `there is no next chapter on the last one`() {
        assertThat(timeline.nextChapterTarget(900_000)).isNull()
        assertThat(timeline.nextChapterTarget(2_099_999)).isNull()
    }

    @Test
    fun `percent of the whole book`() {
        assertThat(timeline.percentAt(0)).isEqualTo(0.0)
        assertThat(timeline.percentAt(1_050_000)).isEqualTo(50.0)
        assertThat(timeline.percentAt(2_100_000)).isEqualTo(100.0)
        assertThat(timeline.percentAt(9_000_000)).isEqualTo(100.0)
        assertThat(timeline.percentAt(-1)).isEqualTo(0.0)
    }

    @Test
    fun `time left follows the speed`() {
        assertThat(timeline.timeLeftMs(600_000, speed = 1f)).isEqualTo(1_500_000)
        assertThat(timeline.timeLeftMs(600_000, speed = 1.5f)).isEqualTo(1_000_000)
        assertThat(timeline.timeLeftMs(600_000, speed = 0.5f)).isEqualTo(3_000_000)
        assertThat(timeline.timeLeftMs(9_000_000, speed = 1f)).isEqualTo(0)
    }

    @Test
    fun `a book without chapters has no chapter to point at`() {
        val bare = BookTimeline(manifest(listOf(first, second), chapters = emptyList()))

        assertThat(bare.chapterAt(100)).isEqualTo(-1)
        assertThat(bare.previousChapterTarget(100_000)).isEqualTo(0)
        assertThat(bare.nextChapterTarget(100)).isNull()
    }

    @Test
    fun `before the first chapter, previous goes to the start of the book and next to the first chapter`() {
        val late = chapters.map { it.copy(startMs = it.startMs + 30_000) }.drop(1)
        val intro = BookTimeline(manifest(listOf(first, second, third), late))

        assertThat(intro.previousChapterTarget(10_000)).isEqualTo(0)
        assertThat(intro.previousChapterTarget(629_999)).isEqualTo(0)
        assertThat(intro.nextChapterTarget(10_000)).isEqualTo(630_000)
        assertThat(intro.nextChapterTarget(630_000)).isEqualTo(750_000)
    }

    @Test
    fun `a single file of unknown duration lasts what the book lasts`() {
        val unknown = asset("aud_u", sequence = 0, durationMs = null)
        val book = BookTimeline(manifest(listOf(unknown), emptyList(), totalDurationMs = 3_600_000))

        assertThat(book.totalMs).isEqualTo(3_600_000)
        assertThat(book.bookTimeOf("aud_u", 1_000_000)).isEqualTo(1_000_000)
        assertThat(book.bookTimeOf("aud_u", 9_000_000)).isEqualTo(3_600_000)
        assertThat(book.assetPositionOf(1_000_000)).isEqualTo(unknown to 1_000_000L)
        assertThat(book.percentAt(1_800_000)).isEqualTo(50.0)
    }

    @Test
    fun `a duration nobody knows is never clamped to`() {
        val unknown = asset("aud_u", sequence = 0, durationMs = null)
        val book = BookTimeline(manifest(listOf(unknown), emptyList(), totalDurationMs = 0))

        assertThat(book.bookTimeOf("aud_u", 5_000_000)).isEqualTo(5_000_000)
        assertThat(book.assetPositionOf(0)).isEqualTo(unknown to 0L)
        assertThat(book.assetPositionOf(5_000_000)).isEqualTo(unknown to 5_000_000L)
        assertThat(book.percentAt(5_000_000)).isEqualTo(0.0)
    }

    @Test
    fun `among several files, one of unknown duration is not clamped and the first file starts the book`() {
        val unknown = asset("aud_u", sequence = 1, durationMs = null)
        val book = BookTimeline(manifest(listOf(first, unknown, third), emptyList()))

        assertThat(book.assetPositionOf(0)).isEqualTo(first to 0L)
        assertThat(book.assetPositionOf(599_999)).isEqualTo(first to 599_999L)
        assertThat(book.bookTimeOf("aud_u", 999_999)).isEqualTo(1_599_999)
        assertThat(book.assetPositionOf(1_599_999)).isEqualTo(unknown to 999_999L)
        // Nothing says where the file after it starts.
        assertThat(book.bookTimeOf("aud_c", 0)).isNull()
        assertThat(book.bookTimeOf("aud_a", 1_000)).isEqualTo(1_000)
        assertThat(book.durationsKnown).isFalse()
    }

    @Test
    fun `durations are known when every file has one, or the only file takes the book's`() {
        val unknown = asset("aud_u", sequence = 0, durationMs = null)

        assertThat(timeline.durationsKnown).isTrue()
        assertThat(BookTimeline(manifest(listOf(unknown), emptyList(), totalDurationMs = 60_000)).durationsKnown)
            .isTrue()
        assertThat(BookTimeline(manifest(listOf(unknown), emptyList(), totalDurationMs = 0)).durationsKnown).isFalse()
    }

    @Test
    fun `a book without files places nothing and never fails`() {
        val empty = BookTimeline(manifest(emptyList(), emptyList()))

        assertThat(empty.totalMs).isEqualTo(0)
        assertThat(empty.assetPositionOf(0)).isNull()
        assertThat(empty.bookTimeOf("aud_a", 0)).isNull()
        assertThat(empty.percentAt(1_000)).isEqualTo(0.0)
        assertThat(empty.timeLeftMs(1_000, speed = 1f)).isEqualTo(0)
    }

    private fun asset(id: String, sequence: Int, durationMs: Long?) = AudioAsset(
        assetId = id,
        fileId = FileId("$sequence"),
        sequence = sequence,
        format = "mp3",
        durationMs = durationMs,
        sizeBytes = null,
        etag = null,
    )

    private fun chapter(id: String, assetId: String, startMs: Long, endMs: Long, assetOffsetMs: Long) =
        AudioChapter(
            id = id,
            title = "Chapter $id",
            assetId = assetId,
            sequence = id.toInt() - 1,
            startMs = startMs,
            endMs = endMs,
            assetOffsetMs = assetOffsetMs,
        )

    private fun manifest(
        assets: List<AudioAsset>,
        chapters: List<AudioChapter>,
        totalDurationMs: Long = assets.sumOf { it.durationMs ?: 0 },
    ) = AudioManifest(
        bookId = BookId("1"),
        contentVersion = "rev",
        title = "The Lantern Keeper",
        authors = listOf("Anne Verlaine"),
        narrators = listOf("Thomas Rivière"),
        hasCover = false,
        assets = assets,
        chapters = chapters,
        totalDurationMs = totalDurationMs,
    )
}
