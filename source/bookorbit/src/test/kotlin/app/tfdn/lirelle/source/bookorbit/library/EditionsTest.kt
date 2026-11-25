package app.tfdn.lirelle.source.bookorbit.library

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.model.EditionMedia
import app.tfdn.lirelle.core.library.model.EditionProgress
import app.tfdn.lirelle.source.bookorbit.library.dto.BookDetailDto
import app.tfdn.lirelle.source.bookorbit.library.dto.BookFileDto
import app.tfdn.lirelle.source.bookorbit.library.dto.EditionProgressDto
import app.tfdn.lirelle.source.bookorbit.library.dto.LinkedBookDto
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EditionsTest {

    @Suppress("LongParameterList") // Every parameter but the id has a default; tests name the ones they set.
    private fun edition(
        id: Long,
        title: String = "T$id",
        lead: Boolean = false,
        formats: List<String> = listOf("epub"),
        name: String? = null,
        publisher: String? = null,
        year: Int? = null,
        narrators: List<String> = emptyList(),
        library: String = "Books",
        progress: EditionProgressDto? = null,
        readAt: String? = null,
        status: String = "present",
    ) = LinkedBookDto(
        bookId = id,
        libraryName = library,
        title = title,
        formats = formats,
        status = status,
        isLead = lead,
        editionName = name,
        publisher = publisher,
        publishedYear = year,
        narrators = narrators,
        coverUpdatedAt = "2026-01-0$id",
        progress = progress,
        lastReadAt = readAt,
    )

    private fun book(self: LinkedBookDto?, vararg others: LinkedBookDto, lead: Boolean = false) = BookDetailDto(
        id = 1,
        libraryName = "Books",
        addedAt = "2026-01-01T00:00:00.000Z",
        title = "T1",
        files = listOf(BookFileDto(id = 9, format = "EPUB", role = "primary")),
        linkedBooks = others.toList(),
        linkSelf = self,
        linkIsLead = lead,
    )

    private fun editions(dto: BookDetailDto, groups: Boolean = true) =
        editionsOf(dto, groups) { id, version -> "cover/$id?$version" }

    // --- which books

    @Test
    fun `a book without linked books has no editions`() {
        assertThat(editions(book(self = null))).isEmpty()
    }

    @Test
    fun `a book whose only linked book is gone has no editions`() {
        assertThat(editions(book(edition(1), edition(2, status = "missing")))).isEmpty()
    }

    @Test
    fun `the open book is among its editions, marked`() {
        val list = editions(book(edition(1), edition(2)))

        assertThat(list.map { it.bookId }).containsExactly(BookId("1"), BookId("2"))
        assertThat(list.map { it.isOpen }).containsExactly(true, false).inOrder()
        assertThat(list.map { it.title }).containsExactly("T1", "T2").inOrder()
        assertThat(list[1].coverUrl).isEqualTo("cover/2?2026-01-02")
    }

    // --- label

    @Test
    fun `an edition is named by its edition name first`() {
        val list = editions(book(edition(1, name = " Version en français "), edition(2, name = "Version en anglais")))

        assertThat(list.map { it.label }).containsExactly("Version en français", "Version en anglais").inOrder()
    }

    @Test
    fun `an audio-only edition without a name is named by its narrator`() {
        val list = editions(
            book(
                edition(1, publisher = "Albin Michel", year = 2013, narrators = listOf("A Voice")),
                edition(2, formats = listOf("m4b"), publisher = "Audiolib", narrators = listOf("", "Another Voice")),
            ),
        )

        assertThat(list.map { it.label }).containsExactly("Albin Michel · 2013", "Another Voice").inOrder()
    }

    @Test
    fun `publisher and year, then one of them, then the library`() {
        val list = editions(
            book(
                edition(1, publisher = "Scribner", year = 2011),
                edition(2, publisher = "Pygmalion"),
                edition(3, year = 1998),
                edition(4, library = "Comics"),
            ),
        )

        // Listed the newest first, those without a year last.
        assertThat(list.map { it.label }).containsExactly("Scribner · 2011", "1998", "Pygmalion", "Comics").inOrder()
    }

    @Test
    fun `two editions that would share a label get their library, whatever the case`() {
        val list = editions(
            book(
                edition(1, name = "Poche", library = "Books"),
                edition(2, name = "poche", library = "Shelf"),
                edition(3, library = "Books"),
                edition(4, library = "Books"),
            ),
        )

        // The last two are already named by their library: nothing is added to it.
        assertThat(list.map { it.label }).containsExactly("Poche · Books", "poche · Shelf", "Books", "Books").inOrder()
    }

    // --- order

    @Test
    fun `the main edition comes first, then those read, the latest first`() {
        val list = editions(
            book(
                edition(1),
                edition(2, readAt = "2026-02-01T10:00:00.000Z"),
                edition(3, lead = true),
                edition(4, readAt = "2026-03-01T10:00:00.000Z"),
            ),
        )

        assertThat(list.map { it.bookId.value }).containsExactly("3", "4", "2", "1").inOrder()
    }

    @Test
    fun `finished without a date, then text before audio, then the newest, then the id`() {
        val list = editions(
            book(
                edition(1, formats = listOf("mp3"), year = 2020),
                edition(2, year = 1990),
                edition(3, year = 2005),
                edition(4),
                edition(5, year = 2005),
                edition(6, formats = listOf("m4b"), progress = EditionProgressDto(100.0, finished = true)),
            ),
        )

        assertThat(list.map { it.bookId.value }).containsExactly("6", "3", "5", "2", "4", "1").inOrder()
    }

    @Test
    fun `the editions are listed alike from the page of each of them`() {
        val a = edition(1, year = 1998)
        val b = edition(2, lead = true)
        val c = edition(3, readAt = "2026-03-01T10:00:00.000Z")
        val fromA = editions(book(a, b, c))
        val fromC = editions(book(c, a, b).copy(id = 3))

        assertThat(fromA.map { it.bookId }).isEqualTo(fromC.map { it.bookId })
        assertThat(fromA.single { it.isOpen }.bookId).isEqualTo(BookId("1"))
        assertThat(fromC.single { it.isOpen }.bookId).isEqualTo(BookId("3"))
    }

    // --- media and progress

    @Test
    fun `media are read from the formats`() {
        val list = editions(
            book(
                edition(1, formats = listOf("epub", "pdf")),
                edition(2, formats = listOf("M4B", "mp3")),
                edition(3, formats = listOf("epub", "m4b")),
                edition(4, formats = listOf("cbz")),
                edition(5, formats = emptyList()),
            ),
        )

        assertThat(list.associate { it.bookId.value to it.media }).containsExactly(
            "1", EditionMedia.Text,
            "2", EditionMedia.Audio,
            "3", EditionMedia.TextAndAudio,
            "4", EditionMedia.Comic,
            "5", EditionMedia.Text,
        )
    }

    @Test
    fun `progress is the server's, never 0 nor 100 while in progress`() {
        val list = editions(
            book(
                edition(1),
                edition(2, progress = EditionProgressDto(42.5)),
                edition(3, progress = EditionProgressDto(0.2)),
                edition(4, progress = EditionProgressDto(99.8)),
                edition(5, progress = EditionProgressDto(100.0, finished = true)),
            ),
        )

        assertThat(list.associate { it.bookId.value to it.progress }).containsExactly(
            "1", EditionProgress.NotStarted,
            "2", EditionProgress.InProgress(43),
            "3", EditionProgress.InProgress(1),
            "4", EditionProgress.InProgress(99),
            "5", EditionProgress.Finished,
        )
    }

    // --- a server that knows linked books, not editions (3.8, 3.9)

    @Test
    fun `without editions on the server, no label, no progress, the main book then the ids`() {
        val older = book(self = null, edition(5, lead = true), edition(3), lead = false)

        val list = editions(older, groups = false)

        assertThat(list.map { it.bookId.value }).containsExactly("5", "1", "3").inOrder()
        assertThat(list.map { it.isOpen }).containsExactly(false, true, false).inOrder()
        assertThat(list.map { it.label }).containsExactly(null, null, null)
        assertThat(list.map { it.progress }.toSet()).containsExactly(EditionProgress.Unknown)
        assertThat(list[1].title).isEqualTo("T1")
        assertThat(list[1].media).isEqualTo(EditionMedia.Text)
    }

    @Test
    fun `a server whose features could not be read gets the reduced list, even with the richer answer`() {
        val list = editions(book(edition(1, name = "Poche"), edition(2, name = "Relié")), groups = false)

        assertThat(list.map { it.label }).containsExactly(null, null)
        assertThat(list.map { it.progress }.toSet()).containsExactly(EditionProgress.Unknown)
    }
}
