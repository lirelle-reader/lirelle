package app.tfdn.lirelle.feature.book.download

import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Audiobook
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Narration
import app.tfdn.lirelle.core.downloads.model.DownloadFormat.Text
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.downloads.model.toggled
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FormatsSheetStateTest {

    private val oneFormat = TextAndAudiobook.take(1)

    @Test
    fun `one format, nothing kept, is a row to check`() {
        val situation = situation(offered = oneFormat)

        assertThat(situation.sheetRows(situation.kept()))
            .containsExactly(SheetRow(Text, 3 * Mb, checked = false, locked = false, downloaded = false, null))
        assertThat(situation.plan(situation.kept()).isEmpty).isTrue()
    }

    @Test
    fun `the row of the text says what it is named by, offered or kept`() {
        val comic = situation(offered = Comic)
        val pdf = situation(offered = PdfAndAudiobook)
        val kept = situation(
            offered = emptyList(),
            book = downloaded(complete(Text).copy(textName = TextName.Djvu), complete(Audiobook)),
            canDownload = false,
        )

        val row = SheetRow(Text, 9 * Mb, checked = false, locked = false, downloaded = false, chapterCount = null)
        assertThat(comic.sheetRows(emptySet())).containsExactly(row.copy(estimated = true, textName = TextName.Cbz))
        assertThat(pdf.sheetRows(emptySet()).map { it.textName })
            .containsExactly(TextName.Pdf, TextName.Text).inOrder()
        assertThat(kept.sheetRows(kept.kept()).map { it.textName })
            .containsExactly(TextName.Djvu, TextName.Text).inOrder()
    }

    @Test
    fun `the row of an album being fetched says how far it is by its pages`() {
        val fetched = album(FormatState.Downloading(receivedBytes = 8 * Mb, totalBytes = 9 * Mb), kept = 12)
        val situation = situation(offered = Comic, book = downloaded(fetched))

        val row = situation.sheetRows(situation.kept()).single()

        assertThat(row.fetching).isTrue()
        assertThat(row.progress).isEqualTo(25)
        assertThat(row.textName).isEqualTo(TextName.Cbz)
    }

    @Test
    fun `two formats, one checked, downloads that one and says its size`() {
        val situation = situation()
        val chosen = situation.kept().toggled(Audiobook)

        val rows = situation.sheetRows(chosen)

        assertThat(rows.map { it.format to it.checked }).containsExactly(Text to false, Audiobook to true).inOrder()
        assertThat(rows[1].chapterCount).isEqualTo(139)
        assertThat(situation.plan(chosen))
            .isEqualTo(SheetPlan(toDownload = setOf(Audiobook), downloadBytes = 412 * Mb))
    }

    @Test
    fun `three formats, the narration checks the text and locks it`() {
        val situation = situation(offered = ThreeFormats)
        val chosen = situation.kept().toggled(Narration)

        val rows = situation.sheetRows(chosen)

        assertThat(rows.map { it.format }).containsExactly(Text, Audiobook, Narration).inOrder()
        assertThat(rows[0]).isEqualTo(
            SheetRow(Text, null, checked = true, locked = true, downloaded = false, null, inNarration = true),
        )
        assertThat(rows[2].checked).isTrue()
        // The text comes in the narration's file: its size is the narration's.
        assertThat(situation.plan(chosen))
            .isEqualTo(SheetPlan(toDownload = setOf(Text, Narration), downloadBytes = 1_080 * Mb))
    }

    @Test
    fun `a locked text cannot be unchecked, and stays when the narration goes`() {
        val chosen = emptySet<DownloadFormat>().toggled(Narration)

        assertThat(chosen.toggled(Text)).containsExactly(Text, Narration)
        assertThat(chosen.toggled(Narration)).containsExactly(Text)
    }

    @Test
    fun `sizes that are not known give a button without a size`() {
        val situation = situation(offered = ThreeFormats)

        assertThat(situation.plan(setOf(Text))).isEqualTo(SheetPlan(toDownload = setOf(Text), downloadBytes = null))
    }

    @Test
    fun `an estimated size is said to be one, until the format is on the device`() {
        val offered = listOf(EstimatedText) + ThreeFormats.drop(1)

        val before = situation(offered = offered).sheetRows(emptySet())[0]
        val after = situation(offered = offered, book = downloaded(complete(Text, 2 * Mb))).sheetRows(setOf(Text))[0]

        assertThat(before.bytes to before.estimated).isEqualTo(3 * Mb to true)
        assertThat(after.bytes to after.estimated).isEqualTo(2 * Mb to false)
    }

    @Test
    fun `a downloaded format is checked, with its size on the device`() {
        val situation = situation(book = downloaded(complete(Text, 2 * Mb)))

        val rows = situation.sheetRows(situation.kept())

        assertThat(rows[0]).isEqualTo(SheetRow(Text, 2 * Mb, checked = true, locked = false, downloaded = true, null))
        assertThat(situation.plan(situation.kept()).isEmpty).isTrue()
    }

    @Test
    fun `a downloaded format unchecked is deleted, and the button says the space freed`() {
        val situation = situation(book = downloaded(complete(Text, 2 * Mb), complete(Audiobook, 982 * Mb)))
        val chosen = situation.kept().toggled(Audiobook)

        assertThat(situation.sheetRows(chosen)[1].checked).isFalse()
        assertThat(situation.plan(chosen)).isEqualTo(SheetPlan(toDelete = setOf(Audiobook), deleteBytes = 982 * Mb))
    }

    @Test
    fun `one format unchecked and another checked are both in the plan`() {
        val situation = situation(book = downloaded(complete(Text, 2 * Mb)))
        val chosen = situation.kept().toggled(Text).toggled(Audiobook)

        assertThat(situation.plan(chosen)).isEqualTo(
            SheetPlan(setOf(Audiobook), downloadBytes = 412 * Mb, toDelete = setOf(Text), deleteBytes = 2 * Mb),
        )
    }

    @Test
    fun `a format being fetched is checked, with its progress`() {
        val fetching = record(Audiobook, FormatState.Downloading(receivedBytes = 103 * Mb, totalBytes = 412 * Mb))
        val situation = situation(book = downloaded(fetching))

        val row = situation.sheetRows(situation.kept())[1]

        assertThat(row.checked).isTrue()
        assertThat(row.downloaded).isFalse()
        assertThat(row.fetching).isTrue()
        assertThat(row.progress).isEqualTo(25)
        // Unchecked, what it received is what goes.
        assertThat(situation.plan(emptySet()))
            .isEqualTo(SheetPlan(toDelete = setOf(Audiobook), deleteBytes = 103 * Mb))
    }

    @Test
    fun `a format being deleted is offered again`() {
        val situation = situation(book = downloaded(record(Text, FormatState.Complete(2 * Mb), removing = true)))

        assertThat(situation.kept()).isEmpty()
        assertThat(situation.sheetRows(situation.kept())[0].downloaded).isFalse()
    }

    @Test
    fun `a kept format the book no longer offers can still be removed`() {
        val situation = situation(offered = oneFormat, book = downloaded(complete(Audiobook, 982 * Mb)))

        assertThat(situation.sheetRows(situation.kept()).map { it.format }).containsExactly(Text, Audiobook).inOrder()
    }

    @Test
    fun `without the permission the sheet only removes`() {
        val book = downloaded(complete(Text, 2 * Mb), complete(Narration, 941 * Mb))
        val situation = situation(offered = ThreeFormats, book = book, canDownload = false)

        val rows = situation.sheetRows(situation.kept())

        // The audiobook, not kept, is not offered.
        assertThat(rows.map { it.format }).containsExactly(Text, Narration).inOrder()
        assertThat(rows[0].locked).isTrue()
        assertThat(situation.plan(situation.kept().toggled(Narration)))
            .isEqualTo(SheetPlan(toDelete = setOf(Narration), deleteBytes = 941 * Mb))
        // Whatever is asked, nothing is downloaded.
        assertThat(situation.plan(setOf(Text, Audiobook, Narration)).toDownload).isEmpty()
    }

    @Test
    fun `a kept format with an update that waits for the user says so, and only that one`() {
        val fetching = FormatState.Downloading(receivedBytes = Mb, totalBytes = 3 * Mb)
        val book = downloaded(updatable(Text), complete(Audiobook), updatable(Narration, fetching))
        val situation = situation(offered = ThreeFormats, book = book)

        val rows = situation.sheetRows(situation.kept())

        assertThat(rows.map { it.updateAvailable }).containsExactly(true, false, false).inOrder()
        // Nothing starts from the sheet: confirmed as it opened, it does nothing.
        assertThat(situation.plan(situation.kept()).isEmpty).isTrue()
    }
}
