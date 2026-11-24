package app.tfdn.lirelle.source.bookorbit.library.dto

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.source.bookorbit.library.LibraryMapper
import app.tfdn.lirelle.source.bookorbit.library.testing.Fixtures
import app.tfdn.lirelle.source.bookorbit.network.ApiJson
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReaderDtosTest {

    private inline fun <reified T> recorded(name: String): T = ApiJson.decodeFromString(Fixtures.recorded(name))

    @Test
    fun `a file never opened has a percentage of 0 and nothing else`() {
        val item = recorded<PositionsDto>("reader-progress-none").items.single()

        assertThat(item.percentage).isEqualTo(0.0)
        assertThat(item.updatedAt).isNull()
        assertThat(item.text).isEqualTo(PositionTextDto(cfi = null, updatedAt = null))
    }

    @Test
    fun `a position carries its place, its percentage out of 100 and its dates`() {
        val item = recorded<PositionsDto>("reader-progress").items.single()

        assertThat(item.fileId).isEqualTo(1)
        assertThat(item.percentage).isEqualTo(30.0)
        assertThat(item.updatedAt).isEqualTo("2026-11-14T07:45:01.809Z")
        assertThat(item.text).isEqualTo(PositionTextDto("epubcfi(/6/8!/4/2/1:0)", "2026-11-14T07:45:01.809Z"))
    }

    @Test
    fun `the answer to a write says what happened to each file`() {
        val result = recorded<PositionWriteResultsDto>("reader-progress-saved").results.single()

        assertThat(result.fileId).isEqualTo(1)
        assertThat(result.status).isEqualTo("saved")
        assertThat(result.progress?.updatedAt).isEqualTo("2026-11-14T07:45:01.809Z")
        assertThat(result.progress?.text?.updatedAt).isEqualTo("2026-11-14T07:45:01.809Z")
    }

    @Test
    fun `readings carry their outcome, their rating and when they were written`() {
        assertThat(recorded<ReadingAttemptsDto>("reader-attempts").items).containsExactly(
            ReadingAttemptDto(
                id = 2,
                outcome = "completed",
                rating = null,
                endedOn = "2026-11-14",
                updatedAt = "2026-11-14T07:45:03.015Z",
            ),
        )
    }

    @Test
    fun `the next volume, or none`() {
        assertThat(recorded<NextVolumeAnswerDto>("reader-next-volume").next)
            .isEqualTo(NextVolumeDto(bookId = 4, title = "Book 1", seriesIndex = "3"))
        assertThat(recorded<NextVolumeAnswerDto>("reader-next-volume-none").next).isNull()
    }

    @Test
    fun `a comic's position carries its page`() {
        val item = ApiJson.decodeFromString<PositionsDto>(Fixtures.read("handmade/comic-progress.json")).items.single()

        assertThat(item.text).isEqualTo(PositionTextDto(cfi = null, updatedAt = "2026-11-17T09:12:44.501Z", page = 12))
    }

    @Test
    fun `the positions of a book's files carry their pages too`() {
        val recorded = recorded<ReadingProgressListDto>("book-reading-progress").items
        val comic = ApiJson.decodeFromString<ReadingProgressListDto>(
            Fixtures.read("handmade/book-reading-progress-comic.json"),
        ).items

        assertThat(recorded.map { it.text }.distinct()).containsExactly(PositionTextDto())
        assertThat(comic.map { it.text?.page }).containsExactly(null, 12).inOrder()
    }

    @Test
    fun `the text of a write has its page or its place, and never a null`() {
        assertThat(ApiJson.encodeToString(PositionTextWriteDto(cfi = null, page = 12))).isEqualTo("""{"page":12}""")
        assertThat(ApiJson.encodeToString(PositionTextWriteDto("epubcfi(/6/2)")))
            .isEqualTo("""{"cfi":"epubcfi(/6/2)"}""")
        assertThat(ApiJson.encodeToString(PositionTextWriteDto(cfi = null))).isEqualTo("{}")
    }

    @Test
    fun `a write declares exactly the fields the server accepts`() {
        val write = PositionWritesDto(listOf(PositionWriteDto(12, 42.5, PositionTextWriteDto("epubcfi(/6/2)"))))

        assertThat(ApiJson.encodeToString(write))
            .isEqualTo("""{"writes":[{"fileId":12,"percentage":42.5,"text":{"cfi":"epubcfi(/6/2)"},"kind":"text"}]}""")
    }

    @Test
    fun `a book with two EPUBs is read from its primary, read-along one`() {
        val dto = recorded<BookDetailDto>("reader-book-two-epubs")

        val book = LibraryMapper("https://books.example.com").detail(dto, audioChapterCount = null)

        assertThat(book.files.map { it.id }).containsExactly(FileId("10"), FileId("11"), FileId("12")).inOrder()
        assertThat(book.files.map { it.contentVersion }).containsExactly(
            "2026-11-11T13:01:19.621Z",
            "2026-11-11T13:01:20.971Z",
            "2026-11-11T20:57:00.828Z",
        ).inOrder()
        val file = checkNotNull(book.readerFile)
        assertThat(file.id).isEqualTo(FileId("12"))
        assertThat(file.hasReadAlong).isTrue()
        assertThat(book.files[0].hasReadAlong).isFalse()
    }
}
