package app.tfdn.lirelle.feature.library.series

import app.tfdn.lirelle.core.downloads.engine.offeredFormats
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Audiobook
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Narration
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Text
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.testing.samplePagedDetail
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SeriesDownloadTest {

    private fun ids(vararg texts: String) = texts.map(::BookId)

    @Test
    fun `finished books are skipped while the box is checked, and counted`() {
        val offers = listOf(offer("1", finished = true), offer("2"), offer("3", finished = true), offer("4"))

        val skipping = seriesPlan(offers, setOf(Text), skipFinished = true)
        assertThat(skipping.finishedSkipped).isEqualTo(2)
        assertThat(skipping.rows.single { it.format == Text }.of).isEqualTo(2)
        assertThat(skipping.asks.map { it.first }).containsExactlyElementsIn(ids("2", "4")).inOrder()

        val all = seriesPlan(offers, setOf(Text), skipFinished = false)
        assertThat(all.finishedSkipped).isEqualTo(0)
        assertThat(all.rows.single { it.format == Text }.of).isEqualTo(4)
        assertThat(all.asks.map { it.first }).containsExactlyElementsIn(ids("1", "2", "3", "4")).inOrder()
    }

    @Test
    fun `a book that keeps a format is not counted for it, nor asked for it`() {
        val offers = listOf(offer("1", kept = setOf(Text)), offer("2"), offer("3", kept = setOf(Text, Audiobook)))

        val plan = seriesPlan(offers, setOf(Text, Audiobook), skipFinished = true)

        assertThat(plan.rows.single { it.format == Text })
            .isEqualTo(SeriesFormatRow(Text, books = 1, of = 3, bytes = 3 * Mb, estimated = false, checked = true))
        assertThat(plan.rows.single { it.format == Audiobook }.books).isEqualTo(2)
        assertThat(plan.rows.single { it.format == Audiobook }.bytes).isEqualTo(800 * Mb)
        assertThat(plan.asks)
            .containsExactly(BookId("1") to setOf(Audiobook), BookId("2") to setOf(Text, Audiobook))
            .inOrder()
        assertThat(plan.books).isEqualTo(2)
        assertThat(plan.bytes).isEqualTo(803 * Mb)
    }

    @Test
    fun `the PDFs, the Kindle books, the comics and the DjVu of a series are all counted as texts`() {
        val formats = listOf("EPUB", "PDF", "MOBI", "CBZ", "DJVU", "AZW3", "FB2")
        val offers = formats.map { format ->
            val book = samplePagedDetail(format, id = format)
            SeriesBookOffer(book.id, finished = false, offered = book.offeredFormats(), kept = emptySet())
        }

        val plan = seriesPlan(offers, setOf(Text), skipFinished = true)

        assertThat(plan.rows.map { it.format }).containsExactly(Text)
        assertThat(plan.rows.single().books).isEqualTo(7)
        assertThat(plan.rows.single().of).isEqualTo(7)
        assertThat(plan.asks.map { it.first }).containsExactlyElementsIn(ids(*formats.toTypedArray())).inOrder()
        assertThat(plan.books).isEqualTo(7)
    }

    @Test
    fun `a format no candidate offers has no row, even when it was chosen before`() {
        val offers = listOf(offer("1", offered = listOf(text("1"))), offer("2", offered = listOf(text("2"))))

        val plan = seriesPlan(offers, setOf(Text, Audiobook, Narration), skipFinished = true)

        assertThat(plan.rows.map { it.format }).containsExactly(Text)
        assertThat(plan.rows.single().locked).isFalse()
        assertThat(plan.asks).containsExactly(BookId("1") to setOf(Text), BookId("2") to setOf(Text)).inOrder()
    }

    @Test
    fun `a format only a finished book offers has no row while finished books are skipped`() {
        val offers = listOf(offer("1", finished = true), offer("2", offered = listOf(text("2"))))

        assertThat(seriesPlan(offers, setOf(Text), skipFinished = true).rows.map { it.format }).containsExactly(Text)
        assertThat(seriesPlan(offers, setOf(Text), skipFinished = false).rows.map { it.format })
            .containsExactly(Text, Audiobook)
            .inOrder()
    }

    @Test
    fun `the narration checks the text and locks it`() {
        val offers = listOf(offer("1", offered = ReadAlong), offer("2"))

        val plan = seriesPlan(offers, setOf(Narration), skipFinished = true)

        val text = plan.rows.single { it.format == Text }
        assertThat(text.checked).isTrue()
        assertThat(text.locked).isTrue()
        assertThat(plan.rows.single { it.format == Narration }.checked).isTrue()
        // The book without a narration is asked for the text the narration checked.
        assertThat(plan.asks)
            .containsExactly(BookId("1") to setOf(Text, Narration), BookId("2") to setOf(Text))
            .inOrder()
    }

    @Test
    fun `a sum with an estimate, or with a size nobody knows, is an estimate`() {
        val estimated = offer("1", offered = listOf(text("1", bytes = 2 * Mb, estimated = true)))
        val unknown = offer("2", offered = listOf(text("2", bytes = null)))
        val exact = offer("3", offered = listOf(text("3")))

        assertThat(seriesPlan(listOf(exact, estimated), setOf(Text), true).rows.single())
            .isEqualTo(SeriesFormatRow(Text, books = 2, of = 2, bytes = 5 * Mb, estimated = true, checked = true))
        assertThat(seriesPlan(listOf(exact, unknown), setOf(Text), true).rows.single().estimated).isTrue()
        assertThat(seriesPlan(listOf(exact), setOf(Text), true).rows.single().estimated).isFalse()
    }

    @Test
    fun `the button counts each book once and each file once`() {
        // The text of the first book comes inside its narration's file; the second has a text of its own.
        val offers = listOf(offer("1", offered = ReadAlong), offer("2"), offer("3", kept = setOf(Text, Audiobook)))

        val plan = seriesPlan(offers, setOf(Text, Audiobook, Narration), skipFinished = true)

        assertThat(plan.books).isEqualTo(2)
        // The narration's file, not the text beside it; then a text and an audiobook.
        assertThat(plan.bytes).isEqualTo(900 * Mb + 3 * Mb + 400 * Mb)
        assertThat(plan.rows.single { it.format == Text }.bytes).isEqualTo(2 * Mb + 3 * Mb)
    }

    @Test
    fun `the row of the text is named by the file the books share, and as the text when they differ`() {
        fun name(vararg names: TextName, skipFinished: Boolean = true): TextName {
            val offers = pagedOffers(*names).mapIndexed { index, offer -> offer.copy(finished = index == 0) }
            return seriesPlan(offers, setOf(Text), skipFinished).rows.single { it.format == Text }.textName
        }

        assertThat(name(TextName.Cbz, TextName.Cbz, TextName.Cbz)).isEqualTo(TextName.Cbz)
        assertThat(name(TextName.Pdf, TextName.Pdf)).isEqualTo(TextName.Pdf)
        assertThat(name(TextName.Text, TextName.Text)).isEqualTo(TextName.Text)
        assertThat(name(TextName.Text, TextName.Cbz, TextName.Cbr)).isEqualTo(TextName.Text)
        assertThat(name(TextName.Text, TextName.Cbz, TextName.Text)).isEqualTo(TextName.Text)
        // The books the row covers are those that say it: a finished book left out does not.
        assertThat(name(TextName.Cbr, TextName.Cbz, TextName.Cbz)).isEqualTo(TextName.Cbz)
        assertThat(name(TextName.Cbr, TextName.Cbz, TextName.Cbz, skipFinished = false)).isEqualTo(TextName.Text)
    }

    @Test
    fun `the name is the text's row alone, and a book that keeps its text still says it`() {
        val offers = pagedOffers(TextName.Djvu, TextName.Djvu).map { it.copy(kept = setOf(Text)) }

        val rows = seriesPlan(offers, setOf(Text, Audiobook), skipFinished = true).rows

        assertThat(rows.single { it.format == Text }.textName).isEqualTo(TextName.Djvu)
        assertThat(rows.single { it.format == Audiobook }.textName).isEqualTo(TextName.Text)
    }

    @Test
    fun `nothing checked, or everything kept, downloads nothing`() {
        val offers = listOf(offer("1"), offer("2", kept = setOf(Text)))

        assertThat(seriesPlan(offers, emptySet(), true).books).isEqualTo(0)
        assertThat(seriesPlan(offers, emptySet(), true).asks).isEmpty()

        val kept = seriesPlan(listOf(offer("2", kept = setOf(Text))), setOf(Text), true)
        assertThat(kept.books).isEqualTo(0)
        assertThat(kept.bytes).isEqualTo(0)
        assertThat(kept.rows.single { it.format == Text }.books).isEqualTo(0)
    }

    @Test
    fun `a tap on the text flips it, unless the narration locks it`() {
        assertThat(setOf(Text).seriesToggled(Text, locked = false)).isEmpty()
        assertThat(emptySet<DownloadFormat>().seriesToggled(Text, false))
            .containsExactly(Text)
        // A narration chosen for another series, which this one does not offer, does not hold the text.
        assertThat(setOf(Text, Narration).seriesToggled(Text, locked = false)).containsExactly(Narration)
        assertThat(setOf(Text, Narration).seriesToggled(Text, locked = true)).containsExactly(Text, Narration)
        assertThat(setOf(Text).seriesToggled(Narration, locked = false)).containsExactly(Text, Narration)
        assertThat(setOf(Text).seriesToggled(Audiobook, locked = false)).containsExactly(Text, Audiobook)
    }
}
