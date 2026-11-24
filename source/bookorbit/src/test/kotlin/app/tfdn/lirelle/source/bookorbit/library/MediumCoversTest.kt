package app.tfdn.lirelle.source.bookorbit.library

import app.tfdn.lirelle.core.library.model.CoverShape
import app.tfdn.lirelle.core.library.model.MediaProfile
import app.tfdn.lirelle.core.library.model.MediumCover
import app.tfdn.lirelle.source.bookorbit.library.dto.BookCardDto
import app.tfdn.lirelle.source.bookorbit.library.dto.BookDetailDto
import app.tfdn.lirelle.source.bookorbit.library.dto.BookFileDto
import app.tfdn.lirelle.source.bookorbit.library.dto.BooksPageDto
import app.tfdn.lirelle.source.bookorbit.library.dto.MediaOverlayDto
import app.tfdn.lirelle.source.bookorbit.library.testing.Fixtures
import app.tfdn.lirelle.source.bookorbit.network.ApiJson
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** One cover per medium, and the audio files a book has to play. */
class MediumCoversTest {

    private val mapper = LibraryMapper("https://books.example.com")

    private fun detail(name: String) =
        mapper.detail(ApiJson.decodeFromString<BookDetailDto>(Fixtures.read("handmade/$name.json")), null)

    @Test
    fun `a book with an audio cover has it, in full and small, with its own version`() {
        val book = detail("book-two-covers")

        assertThat(book.audioCover).isEqualTo(
            MediumCover(
                url = "https://books.example.com/api/v1/books/31/cover?medium=audio&t=2026-10-02T10%3A30%3A00.000Z",
                thumbnailUrl =
                    "https://books.example.com/api/v1/books/31/thumbnail?medium=audio&t=2026-10-02T10%3A30%3A00.000Z",
            ),
        )
        // The book's cover stays the ebook's.
        assertThat(book.coverUrl).startsWith("https://books.example.com/api/v1/books/31/cover?t=ebook")
    }

    @Test
    fun `a book without an audio cover has none, nor does one whose answer has no covers`() {
        assertThat(detail("book-ebook-cover-only").audioCover).isNull()
        assertThat(mapper.detail(BookDetailDto(id = 33, addedAt = "not a date"), null).audioCover).isNull()
    }

    @Test
    fun `audio files are an audiobook file, not the narration of a read-along EPUB`() {
        assertThat(detail("book-two-covers").hasAudioFiles).isTrue()
        val readAlongOnly = detail("book-ebook-cover-only")
        assertThat(readAlongOnly.media).isEqualTo(MediaProfile.ReadAlong)
        assertThat(readAlongOnly.hasAudioFiles).isFalse()
    }

    private fun card(files: List<BookFileDto>) =
        BookCardDto(id = 34, title = "A card", files = files, hasCover = true, coverVersion = "ebook:t:-")

    @Test
    fun `a card knows its audio files in the three cases`() {
        val audio = card(listOf(BookFileDto(1, "epub", "primary"), BookFileDto(2, "mp3", "content")))
        val readAlongOnly = card(listOf(BookFileDto(1, "epub", "primary", mediaOverlay = MediaOverlayDto(true, 60.0))))
        val textOnly = card(listOf(BookFileDto(1, "epub", "primary")))

        assertThat(mapper.summary(audio).hasAudioFiles).isTrue()
        assertThat(mapper.summary(readAlongOnly).hasAudioFiles).isFalse()
        assertThat(mapper.summary(textOnly).hasAudioFiles).isFalse()
    }

    private fun recordedCard(id: Long) =
        ApiJson.decodeFromString<BooksPageDto>(Fixtures.recorded("books-page")).items.single { it.id == id }

    @Test
    fun `a recorded audiobook's card has the server's cover, its own, square and versioned by it`() {
        // "audio:<audio cover time>:-": the medium the cover prefers, its time, then the other's.
        val book = mapper.summary(recordedCard(54))

        assertThat(book.media).isEqualTo(MediaProfile.AudioOnly)
        assertThat(book.hasAudioFiles).isTrue()
        assertThat(book.coverShape).isEqualTo(CoverShape.Square)
        assertThat(book.coverUrl).isEqualTo(
            "https://books.example.com/api/v1/books/54/thumbnail?t=audio%3A2026-11-10T11%3A46%3A45.215Z%3A-",
        )
    }

    @Test
    fun `a recorded book that prefers its ebook cover keeps it, though it has an audio one`() {
        val book = mapper.summary(recordedCard(45))

        assertThat(book.media).isEqualTo(MediaProfile.ReadAlong)
        assertThat(book.hasAudioFiles).isTrue()
        assertThat(book.coverShape).isEqualTo(CoverShape.Portrait)
        assertThat(book.coverUrl).contains("thumbnail?t=ebook%3A")
    }
}
