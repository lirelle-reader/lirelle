package app.tfdn.lirelle.core.downloads.store

import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatUpdate
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.downloads.model.TextName
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** An instant as ISO-8601 text, so that it comes back exactly as it was written. */
internal object InstantAsText : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Instant = try {
        Instant.parse(decoder.decodeString())
    } catch (failure: DateTimeParseException) {
        throw SerializationException("Not an instant", failure)
    }
}

/**
 * What is kept on this device. A value written before the profiles also names an `owner`, which is no longer
 * read: the file is in a profile's folder, and holds nobody else's.
 */
@Serializable
internal data class StoredDownloads(val books: List<StoredBook> = emptyList())

@Serializable
internal data class StoredBook(
    val bookId: BookId,
    val formats: List<StoredFormat> = emptyList(),
    @Serializable(InstantAsText::class) val downloadedAt: Instant,
    @Serializable(InstantAsText::class) val lastOpenedAt: Instant? = null,
    val gone: Boolean = false,
    val deleteAtClose: Boolean = false,
)

/**
 * [format] is the name of a [DownloadFormat]. A format asked before [expectedBytes] was kept has none, and one
 * kept before updates were known has no [update]. [textName] is the name of a [TextName], absent for a text
 * named as such and for one kept before it had a name. A format made of pages has its [pages] and no [files]:
 * see [StoredPages].
 */
@Serializable
internal data class StoredFormat(
    val format: String,
    val state: StoredState,
    val files: List<StoredFile> = emptyList(),
    val fileId: FileId? = null,
    val version: String? = null,
    @Serializable(InstantAsText::class) val askedAt: Instant,
    val anyNetwork: Boolean = false,
    val removing: Boolean = false,
    val expectedBytes: Long? = null,
    val update: StoredUpdate? = null,
    val textName: String? = null,
    val pages: StoredPages? = null,
    val keptPages: Int = 0,
)

/**
 * A [FormatUpdate]; without a [state] it waits for the user. Its [pages] are in place of its [files]. Its
 * [textName] is the name of a [TextName], the text's own written too: none is an update that said none.
 */
@Serializable
internal data class StoredUpdate(
    val version: String? = null,
    val files: List<StoredFile> = emptyList(),
    val bytes: Long = 0,
    val state: StoredState? = null,
    val anyNetwork: Boolean = false,
    val pages: StoredPages? = null,
    val keptPages: Int = 0,
    val textName: String? = null,
)

/**
 * [kind] is the name of a [FileKind]. A file kept before [cacheKey] was has none. [source] says what the file
 * is on the server, and no address: that is asked when the file is fetched.
 */
@Serializable
internal data class StoredFile(
    val name: String,
    val source: StoredSource,
    val version: String? = null,
    val expectedBytes: Long? = null,
    val resumable: Boolean = false,
    val kind: String,
    val cacheKey: String? = null,
)

internal fun DownloadedBook.stored() =
    StoredBook(bookId, formats.map { it.stored() }, downloadedAt, lastOpenedAt, gone, deleteAtClose)

/** A format this version does not know is left out, as is one with a file whose source it does not know. */
internal fun StoredBook.book() =
    DownloadedBook(bookId, formats.mapNotNull { it.record() }, downloadedAt, lastOpenedAt, gone, deleteAtClose)

private fun FormatRecord.stored(): StoredFormat {
    val pages = files.storedPages(version)
    return StoredFormat(
        format = format.name,
        state = state.stored(),
        files = if (pages == null) files.map { it.stored() } else emptyList(),
        fileId = fileId,
        version = version,
        askedAt = askedAt,
        anyNetwork = anyNetwork,
        removing = removing,
        expectedBytes = expectedBytes,
        update = update?.stored(),
        textName = textName.stored(),
        pages = pages,
        keptPages = keptPages,
    )
}

private fun StoredFormat.record(): FormatRecord? {
    val known = DownloadFormat.entries.firstOrNull { it.name == format } ?: return null
    val kept = (if (pages != null) pages.files(version) else files.knownFiles()) ?: return null
    return FormatRecord(
        format = known,
        state = state.state(),
        files = kept,
        fileId = fileId,
        version = version,
        askedAt = askedAt,
        anyNetwork = anyNetwork,
        removing = removing,
        expectedBytes = expectedBytes,
        // An update this version cannot read is no longer offered: the next check finds it again.
        update = update?.update(),
        // Only the text has a name of its own: the narration's EPUB and the audiobook's files say none.
        textName = if (known == DownloadFormat.Text) textName(textName, kept) else TextName.Text,
        keptPages = keptPages,
    )
}

private fun FormatUpdate.stored(): StoredUpdate {
    val pages = files.storedPages(version)
    val entries = if (pages == null) files.map { it.stored() } else emptyList()
    return StoredUpdate(version, entries, bytes, state?.stored(), anyNetwork, pages, keptPages, textName?.name)
}

private fun StoredUpdate.update(): FormatUpdate? = FormatUpdate(
    version = version,
    files = (if (pages != null) pages.files(version) else files.knownFiles()) ?: return null,
    bytes = bytes,
    state = state?.state(),
    anyNetwork = anyNetwork,
    keptPages = keptPages,
    textName = TextName.entries.firstOrNull { it.name == textName },
)

private fun PlannedFile.stored() =
    StoredFile(name, source.stored(), version, expectedBytes, resumable, kind.name, cacheKey)

/** Null when one of them is of a source this version does not know: it could not be fetched again. */
private fun List<StoredFile>.knownFiles(): List<PlannedFile>? = map { it.file() ?: return null }

/**
 * A kind this version does not know is audio, which is checked by nobody and opened by its address only: a
 * file of a later version stays where it is, is counted and can be deleted, and is not handed to a reader.
 * A source this version does not know is null: the file could not be fetched again, so its whole format is
 * dropped from the registry (see [knownFiles]); its files stay on disk, in no record.
 */
private fun StoredFile.file(): PlannedFile? = PlannedFile(
    name = name,
    source = source.source() ?: return null,
    version = version,
    expectedBytes = expectedBytes,
    resumable = resumable,
    kind = FileKind.entries.firstOrNull { it.name == kind } ?: FileKind.Audio,
    cacheKey = cacheKey,
)
