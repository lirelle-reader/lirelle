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
    fun `volumes are ordered by their numbers, which keep their decimals and ranges`() {
        fun volume(id: Int, min: Float, max: Float = min) = VolumeDto(
            id = id,
            number = min.toInt(),
            minNumber = min,
            maxNumber = max,
            chapters = listOf(ChapterDto(id = id * 10, number = DefaultChapter)),
        )
        val detail = SeriesDetailDto(listOf(volume(1, 10.5f), volume(2, 10f), volume(3, 1f, 3f)))

        val books = detail.books()

        assertThat(books.map { it.item.bookId.value }).containsExactly("v3", "v2", "v1").inOrder()
        assertThat(books.map { it.index }).containsExactly("1-3", "10", "10.5").inOrder()
    }

    @Test
    fun `a volume without decimal numbers keeps its whole number`() {
        assertThat(VolumeDto(id = 1, number = 4).index).isEqualTo("4")
        assertThat(VolumeDto(id = 1, number = 0).index).isNull()
    }

    @Test
    fun `the loose-leaf and specials volumes have no number`() {
        assertThat(VolumeDto(id = 1, number = -100000, minNumber = -100000f, maxNumber = -100000f).index).isNull()
        assertThat(VolumeDto(id = 2, number = 100000, minNumber = 100000f, maxNumber = 100000f).index).isNull()
        assertThat(VolumeDto(id = 3, number = -100000).index).isNull()
    }

    @Test
    fun `a chapter seen alone with its volume is the book it belongs to`() {
        val dune = KavitaJson.decodeFromString<VolumeDto>(fixtureBody("volume-dune"))
        val asterix = KavitaJson.decodeFromString<VolumeDto>(fixtureBody("volume-asterix"))

        assertThat(chapter("chapter-asterix").book(asterix).item).isEqualTo(KavitaItem.Chapter(44))
        assertThat(chapter("continue-dune").book(dune).item).isEqualTo(KavitaItem.Volume(407))
        assertThat(chapter("continue-dune").book(dune).index).isEqualTo("1")
        assertThat(chapter("chapter-lone").book(asterix).item).isEqualTo(KavitaItem.Chapter(1857))
    }

    @Test
    fun `a default chapter in the loose-leaf volume is a book of its own, as on the series page`() {
        val loose = VolumeDto(id = 407, number = -100000, minNumber = -100000f, maxNumber = -100000f)

        val book = chapter("continue-dune").book(loose)

        assertThat(book.item).isEqualTo(KavitaItem.Chapter(1586))
        assertThat(book.index).isNull()
        assertThat(book.special).isFalse()
    }

    @Test
    fun `a chapter seen alone has the index of its book`() {
        val dune = KavitaJson.decodeFromString<VolumeDto>(fixtureBody("volume-dune"))

        assertThat(chapter("chapter-asterix").book(volumeIndex = null).index).isEqualTo("1")
        assertThat(chapter("continue-dune").book(dune.index).item).isEqualTo(KavitaItem.Volume(407))
        assertThat(chapter("continue-dune").book(dune.index).index).isEqualTo("1")
        assertThat(chapter("chapter-lone").book(volumeIndex = null).index).isNull()
        assertThat(chapter("chapter-lone").book(volumeIndex = null).special).isTrue()
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
