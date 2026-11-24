package app.tfdn.lirelle.feature.book

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookFile
import app.tfdn.lirelle.core.library.model.BookFileKind

/**
 * One row of the Files block: a file, or the audio files of one format taken together. An
 * audiobook often comes as dozens of tracks, which are of no interest one by one.
 */
internal const val PrimaryFileTag = "file-primary"

internal data class FileEntry(
    val kind: BookFileKind,
    val format: String,
    val fileCount: Int,
    val sizeBytes: Long?,
    val narrationSeconds: Long?,
    val chapterCount: Int?,
    val isPrimary: Boolean,
)

private fun BookFile.toEntry() = FileEntry(kind, format, 1, sizeBytes, narrationSeconds, chapterCount, isPrimary)

/** The sum of the values that are known, or null when none is. */
private fun List<Long?>.sumOrNull(): Long? = filterNotNull().takeIf { it.isNotEmpty() }?.sum()

/**
 * The rows for [files]: the row of the primary file first, the others in the order of the server.
 * Several audio files of a format share the row of the first.
 */
internal fun fileEntries(files: List<BookFile>): List<FileEntry> {
    val audioByFormat = files.filter { it.kind == BookFileKind.Audio }.groupBy { it.format }
    return files.mapNotNull { file ->
        val group = audioByFormat[file.format].orEmpty()
        when {
            file.kind != BookFileKind.Audio || group.size == 1 -> file.toEntry()
            file !== group.first() -> null
            else -> FileEntry(
                kind = BookFileKind.Audio,
                format = file.format,
                fileCount = group.size,
                sizeBytes = group.map { it.sizeBytes }.sumOrNull(),
                narrationSeconds = group.map { it.narrationSeconds }.sumOrNull(),
                chapterCount = group.firstNotNullOfOrNull { it.chapterCount },
                isPrimary = group.any { it.isPrimary },
            )
        }
    }.sortedByDescending { it.isPrimary } // Stable: the other rows keep their order.
}

@Composable
internal fun FilesBlock(book: BookDetail) {
    if (book.files.isEmpty()) return
    Section(R.string.section_files, tag = "book-files") {
        fileEntries(book.files).forEachIndexed { index, file -> FileRow(file, Modifier.testTag("book-file-$index")) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FileRow(file: FileEntry, modifier: Modifier = Modifier) {
    val kind = when (file.kind) {
        BookFileKind.ReadAlongEpub -> stringResource(R.string.file_read_along, file.format)
        BookFileKind.Audio -> stringResource(R.string.file_audio, file.format)
        BookFileKind.Text -> file.format
    }
    val facts = listOfNotNull(
        file.fileCount.takeIf { it > 1 }?.let { pluralStringResource(R.plurals.file_count, it, it) },
        file.sizeBytes?.let { sizeText(it) },
        file.narrationSeconds?.let { stringResource(R.string.file_narration, durationText(it)) },
        file.chapterCount?.let { pluralStringResource(R.plurals.file_chapters, it, it) },
    )
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // The mark is a label of its own next to the kind, under it when the two do not fit on a
        // line. It says what it is in words: its colour only sets it apart.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(kind, style = MaterialTheme.typography.bodyLarge)
            if (file.isPrimary) {
                Label(
                    text = stringResource(R.string.file_primary),
                    modifier = Modifier.testTag(PrimaryFileTag),
                    ground = MaterialTheme.colorScheme.primaryContainer,
                    ink = MaterialTheme.colorScheme.onPrimaryContainer,
                    small = true,
                )
            }
        }
        if (facts.isNotEmpty()) {
            Text(
                text = facts.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
