package app.tfdn.lirelle.core.downloads

import android.content.res.Resources
import androidx.annotation.StringRes
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.PageCount
import app.tfdn.lirelle.core.downloads.model.TextName

/**
 * The name of one format: "Audiobook". The text of a book read by pages is named by its file's format, which
 * [text] says: "CBZ", "PDF"; any other text is "Text".
 */
@StringRes
fun nameRes(format: DownloadFormat, text: TextName): Int = when (format) {
    DownloadFormat.Text -> when (text) {
        TextName.Text -> R.string.downloads_format_text
        TextName.Pdf -> R.string.downloads_format_pdf
        TextName.Cbz -> R.string.downloads_format_cbz
        TextName.Cbr -> R.string.downloads_format_cbr
        TextName.Cb7 -> R.string.downloads_format_cb7
        TextName.Djvu -> R.string.downloads_format_djvu
    }
    DownloadFormat.Audiobook -> R.string.downloads_format_audiobook
    DownloadFormat.Narration -> R.string.downloads_format_narration
}

/**
 * [formats] as one phrase: "Text and narration". The text is named as [text] says, alone or beside another
 * format: "CBZ", "PDF and audiobook".
 */
fun Resources.formatsName(formats: Collection<DownloadFormat>, text: TextName = TextName.Text): String {
    val hasText = DownloadFormat.Text in formats
    val audiobook = DownloadFormat.Audiobook in formats
    val narration = DownloadFormat.Narration in formats
    val named = getString(nameRes(DownloadFormat.Text, text))
    return when {
        hasText && audiobook && narration -> getString(R.string.downloads_formats_all, named)
        hasText && audiobook -> getString(R.string.downloads_formats_text_audiobook, named)
        hasText && narration -> getString(R.string.downloads_formats_text_narration, named)
        audiobook && narration -> getString(R.string.downloads_formats_audiobook_narration)
        audiobook -> getString(R.string.downloads_format_audiobook)
        narration -> getString(R.string.downloads_format_narration)
        else -> named
    }
}

/** How far a download made of pages is, in words: "12 pages of 48", "1 page of 48": the word is of those it has. */
fun Resources.pagesOf(pages: PageCount): String =
    getQuantityString(R.plurals.downloads_pages_of, pages.kept, pages.kept, pages.total)
