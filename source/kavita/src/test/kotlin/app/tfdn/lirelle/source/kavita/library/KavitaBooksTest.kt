package app.tfdn.lirelle.source.kavita.library

import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.source.kavita.library.dto.ChapterDto
import app.tfdn.lirelle.source.kavita.library.dto.SeriesDetailDto
import app.tfdn.lirelle.source.kavita.library.dto.VolumeDto
import app.tfdn.lirelle.source.kavita.network.KavitaJson
import app.tfdn.lirelle.source.kavita.testing.fixtureBody
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class KavitaBooksTest {

    private fun detail(name: String) = KavitaJson.decodeFromString<SeriesDetailDto>(fixtureBody(name))

    private fun chapter(name: String) = KavitaJson.decodeFromString<ChapterDto>(fixtureBody(name))

    @Test
    fun `a book library's volumes are its books, by number`() {
        val books = detail("detail-dune").books()

        assertThat(books.map { it.item.bookId.value }).containsExactly("v407", "v406", "v405").inOrder()
        assertThat(books.map { it.index }).containsExactly("1", "2", "3").inOrder()
        assertThat(books.none { it.special }).isTrue()
        assertThat(books.first().chapter.id).isEqualTo(1586)
    }

    @Test
    fun `the chapters of a volume, then those outside a volume, each once`() {
        val asterix = detail("detail-asterix")
        // The fixture repeats the volume's chapters in `chapters`, as Kavita does.
        assertThat(asterix.chapters).isNotEmpty()

        val books = asterix.books()

        assertThat(books.map { it.item.bookId.value })
            .containsExactly("c44", "c55", "c56", "c80", "c81", "c82").inOrder()
        assertThat(books.map { it.index }).containsExactly("1", "2", "3", "39", "40", "41").inOrder()
    }

    @Test
    fun `a series without volumes has its chapters for books`() {
        val books = detail("detail-one-piece").books()

        assertThat(books.map { it.item }).containsExactly(
            KavitaItem.Chapter(1101),
            KavitaItem.Chapter(1141),
            KavitaItem.Chapter(1093),
        ).inOrder()
    }

    @Test
    fun `a series of one special is a lone book`() {
        val lone = detail("detail-lone")

        val book = lone.books().single()

        assertThat(book.item.bookId).isEqualTo(BookId("c1857"))
        assertThat(book.special).isTrue()
        assertThat(book.index).isNull()
        assertThat(lone.isLoneBook).isTrue()
        assertThat(detail("detail-dune").isLoneBook).isFalse()
    }

    @Test
    fun `the specials come last`() {
        val special = ChapterDto(id = 9, number = DefaultChapter, range = "Hors-série", isSpecial = true)
        val loose = ChapterDto(id = 8, number = "4", range = "4")
        val volume = VolumeDto(id = 3, number = 1, chapters = listOf(ChapterDto(id = 7, number = DefaultChapter)))
        val detail = SeriesDetailDto(listOf(volume), storylineChapters = listOf(loose), specials = listOf(special))

        assertThat(detail.books().map { it.item.bookId.value }).containsExactly("v3", "c8", "c9").inOrder()
    }

    @Test
    fun `a chapter seen alone is the book it belongs to`() {
        assertThat(chapter("chapter-asterix").item()).isEqualTo(KavitaItem.Chapter(44))
        assertThat(chapter("continue-dune").item()).isEqualTo(KavitaItem.Volume(407))
        assertThat(chapter("chapter-lone").item()).isEqualTo(KavitaItem.Chapter(1857))
    }

    @Test
    fun `a chapter seen alone has the index of its book`() {
        assertThat(chapter("chapter-asterix").book().index).isEqualTo("1")
        assertThat(chapter("continue-dune").book(volumeNumber = 1).index).isEqualTo("1")
        assertThat(chapter("chapter-lone").book().index).isNull()
        assertThat(chapter("chapter-lone").book().special).isTrue()
    }

    @Test
    fun `a book id says what to ask Kavita for`() {
        assertThat(BookId("v12").kavitaItem()).isEqualTo(KavitaItem.Volume(12))
        assertThat(BookId("c34").kavitaItem()).isEqualTo(KavitaItem.Chapter(34))
        assertThat(BookId("x1").kavitaItem()).isNull()
        assertThat(BookId("v").kavitaItem()).isNull()
        assertThat(BookId("c-1").kavitaItem()).isNull()
        assertThat(BookId("12").kavitaItem()).isNull()
    }
}
