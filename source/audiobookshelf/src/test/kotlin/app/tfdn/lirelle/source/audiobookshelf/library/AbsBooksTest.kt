package app.tfdn.lirelle.source.audiobookshelf.library

import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.library.model.AuthorRef
import app.tfdn.lirelle.core.library.model.CoverShape
import app.tfdn.lirelle.core.library.model.MediaProfile
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.core.library.model.SeriesRef
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsItemDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsMediaDto
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsMetadataDto
import app.tfdn.lirelle.source.audiobookshelf.testing.RecordedOn
import app.tfdn.lirelle.source.audiobookshelf.testing.expandedItem
import app.tfdn.lirelle.source.audiobookshelf.testing.recordedItem
import app.tfdn.lirelle.source.audiobookshelf.testing.recordedItems
import app.tfdn.lirelle.source.audiobookshelf.testing.recordedReadings
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.time.Instant
import org.junit.Test

private const val Server = "https://abs.example/audiobookshelf"

/** An item made up with the author and series names given, as a list's item names them. */
private fun named(authorName: String? = null, seriesName: String? = null) = AbsItemDto(
    id = "made-up",
    media = AbsMediaDto(metadata = AbsMetadataDto(title = "Made up", authorName = authorName, seriesName = seriesName)),
)

/**
 * An item is a book: its id the item's UUID, its title as the server gives it, its authors and its series from a
 * list's names or a whole item's links, its cover at the server's cover route, its read state from the account's
 * progress. Nothing the server does not say is made up.
 */
class AbsBooksTest {

    private val books = AbsBooks(Server)

    @Test
    fun `a list's item is a book, its series and place read from the series name`() {
        val item = recordedItem("The Ultimate Hitchhiker’s Guide to the Galaxy")

        val book = books.summary(item, AbsReadings.None)

        assertThat(book.id).isEqualTo(BookId(item.id))
        assertThat(book.title).isEqualTo("The Ultimate Hitchhiker’s Guide to the Galaxy")
        assertThat(book.authors).containsExactly("Douglas Adams")
        assertThat(book.series).isEqualTo(SeriesRef(null, "Hitchhiker's Guide to the Galaxy", "0.5"))
        assertThat(book.coverUrl).isEqualTo("$Server/api/items/${item.id}/cover?width=400&ts=${item.updatedAt}")
        assertThat(book.coverShape).isEqualTo(CoverShape.Portrait)
        assertThat(book.media).isEqualTo(MediaProfile.TextOnly)
        assertThat(book.readState).isEqualTo(ReadState.Unread)
        assertThat(book.progressPercent).isNull()
        assertThat(book.lastActivityAt).isNull()
        assertThat(book.pagedKind).isNull()
        assertThat(book.hasAudioFiles).isFalse()
    }

    @Test
    fun `titles are kept as the server gives them, on both versions`() {
        RecordedOn.entries.forEach { on ->
            val item = recordedItem("Parlons Espéranto [fr] (1998)", on = on)

            assertWithMessage(on.name).that(books.summary(item, AbsReadings.None).title)
                .isEqualTo("Parlons Espéranto [fr] (1998)")
        }
    }

    @Test
    fun `a book in no series has none, and one without a cover has no address`() {
        val conquest = books.summary(recordedItem("Conquest"), AbsReadings.None)
        val noCover = books.summary(recordedItem("11_22_63 [en] (2011)"), AbsReadings.None)

        assertThat(conquest.series).isNull()
        assertThat(noCover.coverUrl).isNull()
    }

    @Test
    fun `several authors are each named, a series without a number has no place`() {
        val book = books.summary(named("Terry Pratchett, Neil Gaiman", "Discworld"), AbsReadings.None)

        assertThat(book.authors).containsExactly("Terry Pratchett", "Neil Gaiman").inOrder()
        assertThat(book.series).isEqualTo(SeriesRef(null, "Discworld", null))
    }

    @Test
    fun `of several series, the first is the book's`() {
        val book = books.summary(named(seriesName = "Discworld #12, Witches #3"), AbsReadings.None)

        assertThat(book.series).isEqualTo(SeriesRef(null, "Discworld", "12"))
    }

    @Test
    fun `no author named is no author`() {
        assertThat(books.summary(named(authorName = ""), AbsReadings.None).authors).isEmpty()
        assertThat(books.summary(named(authorName = null), AbsReadings.None).authors).isEmpty()
    }

    @Test
    fun `a series' item knows its series by id, on both versions`() {
        RecordedOn.entries.forEach { on ->
            val items = recordedItems("items-series-h2g2", on)
            val first = books.summary(items.first(), AbsReadings.None)

            assertWithMessage(on.name).that(first.series?.id?.value).isNotEmpty()
            assertWithMessage(on.name).that(first.series?.name).isEqualTo("Hitchhiker's Guide to the Galaxy")
            assertWithMessage(on.name).that(first.series?.index).isEqualTo("0.5")
        }
    }

    @Test
    fun `the read state and the progress come from the account's progress`() {
        RecordedOn.entries.forEach { on ->
            val readings = recordedReadings(on)
            val guide = books.summary(recordedItem("The Hitchhiker's Guide to the Galaxy", on = on), readings)
            val prince = books.summary(recordedItem("Le Petit Prince", on = on), readings)

            assertWithMessage(on.name).that(guide.readState).isEqualTo(ReadState.Reading)
            assertWithMessage(on.name).that(guide.progressPercent).isEqualTo(92)
            assertWithMessage(on.name).that(guide.isInProgress).isTrue()
            assertWithMessage(on.name).that(guide.lastActivityAt).isNotNull()
            assertWithMessage(on.name).that(prince.readState).isEqualTo(ReadState.Read)
            assertWithMessage(on.name).that(prince.isRead).isTrue()
        }
    }

    @Test
    fun `an audiobook with an EPUB is text and audio, a comic is read by pages`() {
        val harmless = books.summary(recordedItem("Mostly Harmless"), AbsReadings.None)
        val comic = books.summary(recordedItems("items-bd-added").first(), AbsReadings.None)

        assertThat(harmless.media).isEqualTo(MediaProfile.TextAndAudio)
        assertThat(harmless.hasAudioFiles).isTrue()
        assertThat(comic.pagedKind).isEqualTo(PagedKind.Comic)
    }

    @Test
    fun `a book's cover is square only when the book is audio only, in a list and on its page`() {
        RecordedOn.entries.forEach { on ->
            val audio = recordedItem("Albert Einstein. L'homme le plus surveillé d'Amérique", on = on)
            val both = recordedItem("Mostly Harmless", on = on)

            assertWithMessage(on.name).that(books.summary(audio, AbsReadings.None).coverShape)
                .isEqualTo(CoverShape.Square)
            assertWithMessage(on.name).that(books.summary(both, AbsReadings.None).coverShape)
                .isEqualTo(CoverShape.Portrait)
            val page = books.detail(expandedItem("item-h2g2-expanded", on), AbsReadings.None, "Livres")
            assertWithMessage(on.name).that(page.coverShape).isEqualTo(CoverShape.Portrait)
        }
    }

    @Test
    fun `a whole item is a book's page, on both versions`() {
        RecordedOn.entries.forEach { on ->
            val item = expandedItem("item-h2g2-expanded", on)
            val author = item.media.metadata.authors.single()
            val series = item.media.metadata.series.single()

            val page = books.detail(item, recordedReadings(on), libraryName = "Livres")

            assertWithMessage(on.name).that(page.id).isEqualTo(BookId(item.id))
            assertWithMessage(on.name).that(page.title).isEqualTo("The Hitchhiker's Guide to the Galaxy")
            assertWithMessage(on.name).that(page.subtitle).isNull()
            assertWithMessage(on.name).that(page.authors)
                .containsExactly(AuthorRef(AuthorId(author.id), "Douglas Adams"))
            assertWithMessage(on.name).that(page.series)
                .isEqualTo(SeriesRef(SeriesId(series.id), "Hitchhiker's Guide to the Galaxy", "1"))
            assertWithMessage(on.name).that(page.coverUrl)
                .isEqualTo("$Server/api/items/${item.id}/cover?width=800&ts=${item.updatedAt}")
            assertWithMessage(on.name).that(page.media).isEqualTo(MediaProfile.TextAndAudio)
            assertWithMessage(on.name).that(page.description).startsWith("<p>Seconds before the Earth")
            assertWithMessage(on.name).that(page.genres).containsExactly("Humour", "Science-fiction").inOrder()
            assertWithMessage(on.name).that(page.publishedYear).isEqualTo(1979)
            assertWithMessage(on.name).that(page.publisher).isEqualTo("Del Rey")
            assertWithMessage(on.name).that(page.language).isEqualTo("en")
            assertWithMessage(on.name).that(page.isbn).isNull()
            assertWithMessage(on.name).that(page.libraryName).isEqualTo("Livres")
            assertWithMessage(on.name).that(page.addedAt).isEqualTo(Instant.ofEpochMilli(item.addedAt!!))
            assertWithMessage(on.name).that(page.narrators).isEmpty()
            assertWithMessage(on.name).that(page.readState).isEqualTo(ReadState.Reading)
            assertWithMessage(on.name).that(page.startedAt).isNotNull()
            assertWithMessage(on.name).that(page.rating).isNull()
            assertWithMessage(on.name).that(page.pageCount).isNull()
        }
    }

    @Test
    fun `a book's page keeps the description's paragraphs, and names its narrators`() {
        val page = books.detail(expandedItem("item-petit-prince-expanded"), recordedReadings(), libraryName = "Livres")

        assertThat(page.description).startsWith("<p>Imaginez-vous perdu dans le désert")
        assertThat(page.narrators).containsExactly("Bernard Giraudeau")
        assertThat(page.publishedYear).isEqualTo(1943)
        assertThat(page.readState).isEqualTo(ReadState.Read)
        assertThat(page.series).isNull()
    }

    @Test
    fun `a comic without author nor year has none`() {
        val page = books.detail(expandedItem("item-asterix-expanded"), AbsReadings.None, libraryName = "BD")

        assertThat(page.authors).isEmpty()
        assertThat(page.publishedYear).isNull()
        assertThat(page.language).isNull()
        assertThat(page.readState).isEqualTo(ReadState.Unread)
        assertThat(page.series?.name).isEqualTo("Astérix")
        assertThat(page.series?.index).isEqualTo("1")
    }

    @Test
    fun `a list's narrator names are each a narrator`() {
        val item = AbsItemDto(
            id = "made-up",
            media = AbsMediaDto(metadata = AbsMetadataDto(title = "Made up", narratorName = "Ann One, Bob Two")),
        )

        assertThat(books.detail(item, AbsReadings.None, libraryName = "Livres").narrators)
            .containsExactly("Ann One", "Bob Two").inOrder()
    }
}
