package app.tfdn.lirelle.feature.book

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookReading
import app.tfdn.lirelle.core.library.model.ReadState
import app.tfdn.lirelle.core.ui.CollapsibleText

// ---------- 3. Facts ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FactsBlock(book: BookDetail, ratings: Boolean = true) {
    val locale = currentLocale()
    // No stars from a source without ratings.
    val rating = book.rating?.takeIf { ratings }?.let { ratingText(it, locale) }
    val published = listOfNotNull(book.publishedYear?.toString(), book.publisher).joinToString(" · ")
    val facts = listOfNotNull(
        book.pageCount?.let { pluralStringResource(R.plurals.fact_pages, it, it) },
        published.takeIf { it.isNotEmpty() },
        book.language?.takeIf { it.isNotBlank() }?.let { languageName(it, locale) },
    )
    if (rating == null && facts.isEmpty()) return
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (rating != null) {
            val description = stringResource(R.string.fact_rating_description, rating)
            Label(
                text = stringResource(R.string.fact_rating, rating),
                modifier = Modifier.semantics { contentDescription = description },
                leading = { Icon(Icons.Outlined.Star, contentDescription = null) },
            )
        }
        facts.forEach { Label(it) }
    }
}

// ---------- 4. My reading ----------

@StringRes
internal fun ReadState.labelRes(): Int = when (this) {
    ReadState.Unread -> R.string.read_state_unread
    ReadState.WantToRead -> R.string.read_state_want_to_read
    ReadState.Reading -> R.string.read_state_reading
    ReadState.Read -> R.string.read_state_read
    ReadState.Abandoned -> R.string.read_state_abandoned
}

internal const val ReadingPageTag = "book-reading-page"

/**
 * Not shown for a book that was never opened and has no status of interest. Under the bar, the page a comic
 * is at: its number alone, the server giving no count of pages here.
 *
 * @param positionNote on a page built without the server, the progress is the device's position: what says so.
 * @param sessions false for a source without reading sessions: no time read, no sessions, and they alone do not
 *   make the block shown.
 */
@Composable
internal fun ReadingBlock(
    book: BookDetail,
    reading: BookReading?,
    @StringRes positionNote: Int? = null,
    sessions: Boolean = true,
) {
    val status = book.readState?.takeIf { it != ReadState.Unread }
    val hasReading = if (sessions) reading?.hasAny == true else reading?.progressPercent != null
    if (!hasReading && status == null && book.startedAt == null) return
    val locale = currentLocale()
    Section(R.string.section_reading, tag = "book-reading") {
        reading?.progressPercent?.let { percent ->
            Fact(R.string.reading_progress, stringResource(R.string.reading_progress_value, percent))
            LinearProgressIndicator(
                progress = { percent / PercentMax },
                modifier = Modifier.fillMaxWidth(),
                // No dot at the end of the track: it reads as progress.
                drawStopIndicator = {},
            )
            reading.page?.let { page ->
                Text(
                    text = stringResource(R.string.reading_page, page),
                    modifier = Modifier.testTag(ReadingPageTag),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (positionNote != null) {
                Text(
                    text = stringResource(positionNote),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        status?.let { Fact(R.string.reading_status, stringResource(it.labelRes())) }
        reading?.lastReadAt?.let { Fact(R.string.reading_last_read, dateText(it, locale)) }
        if (sessions) {
            reading?.totalSeconds?.takeIf { it > 0 }?.let { Fact(R.string.reading_time, durationText(it)) }
            reading?.sessionCount?.takeIf { it > 0 }?.let { Fact(R.string.reading_sessions, it.toString()) }
        }
        book.startedAt?.let { Fact(R.string.reading_started, dateText(it, locale)) }
    }
}

private const val PercentMax = 100f

// ---------- 5. About ----------

/** @param genres false for a source without genres: no genre chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AboutBlock(book: BookDetail, onOpenGenre: (String) -> Unit, genres: Boolean = true) {
    val summary = book.description?.takeIf { it.isNotBlank() }
    val shownGenres = book.genres.takeIf { genres }.orEmpty()
    if (summary == null && shownGenres.isEmpty()) return
    Section(R.string.section_about, tag = "book-about") {
        summary?.let { CollapsibleText(html = it) }
        if (shownGenres.isNotEmpty()) {
            // No space between the lines: the touch target of a genre already extends past its outline.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                shownGenres.forEach { genre -> GenreLink(genre, onOpen = { onOpenGenre(genre) }) }
            }
        }
    }
}

// ---------- 7. Details ----------

@Composable
internal fun DetailsBlock(book: BookDetail) {
    val locale = currentLocale()
    Section(R.string.section_details, tag = "book-details") {
        book.publisher?.let { Fact(R.string.detail_publisher, it) }
        if (book.narrators.isNotEmpty()) Fact(R.string.detail_narrators, book.narrators.joinToString())
        book.isbn?.let { Fact(R.string.detail_isbn, it) }
        if (book.libraryName.isNotBlank()) Fact(R.string.detail_library, book.libraryName)
        book.addedAt?.let { Fact(R.string.detail_added, dateText(it, locale)) }
        if (book.collections.isNotEmpty()) Fact(R.string.detail_collections, book.collections.joinToString())
    }
}
