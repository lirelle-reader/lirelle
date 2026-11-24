package app.tfdn.lirelle.core.downloads.update

import app.tfdn.lirelle.core.downloads.engine.FakePlans
import app.tfdn.lirelle.core.downloads.engine.FormatPlan
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.FormatUpdate
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.downloads.store.DataStoreDownloadRegistry
import app.tfdn.lirelle.core.downloads.store.DataStores
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow

/** The check over a fake server, the registry and the hour on a real store, and a clock the test moves. */
internal class UpdateFixture(dataStores: DataStores) {
    val dataStore = dataStores.create()
    val registry = DataStoreDownloadRegistry(dataStore)
    val clock = DataStoreCheckClock(dataStore)
    val plans = FakePlans()
    val canDownload = MutableStateFlow(true)
    var online = true
    var now: Instant = Instant.parse("2026-11-17T10:00:00Z")

    val updates: DownloadUpdates = RegistryDownloadUpdates(
        registry = registry,
        check = UpdateCheck(registry, plans),
        clock = clock,
        inputs = UpdateInputs({ online }, canDownload),
        now = { now },
    )

    fun later(minutes: Long) {
        now += Duration.ofMinutes(minutes)
    }

    /** A text's EPUB, of the file whose id its [name] is made of, as the planner names a text. */
    fun epub(name: String = "12.epub", bytes: Long? = 40, version: String? = "v1") = PlannedFile(
        name,
        FileSource.Whole(FileId(name.substringBefore('.'))),
        version,
        bytes,
        resumable = true,
        kind = FileKind.Epub,
    )

    fun audio(name: String, bytes: Long? = 100, version: String? = "v1") =
        PlannedFile(name, FileSource.AudioAsset(name), version, bytes, resumable = true, kind = FileKind.Audio)

    fun complete(
        format: DownloadFormat,
        vararg files: PlannedFile,
        version: String? = "v1",
        update: FormatUpdate? = null,
    ) = FormatRecord(
        format = format,
        state = FormatState.Complete(files.sumOf { it.expectedBytes ?: 0 }),
        files = files.toList(),
        fileId = null,
        version = version,
        askedAt = now,
        update = update,
    )

    suspend fun keep(
        bookId: BookId,
        vararg formats: FormatRecord,
        change: (DownloadedBook) -> DownloadedBook = { it },
    ) =
        registry.update(bookId) { change(DownloadedBook(bookId, formats.toList(), now, lastOpenedAt = null)) }

    fun serve(bookId: BookId, format: DownloadFormat, vararg files: PlannedFile, version: String? = "v1") {
        plans.answers[bookId to format] = ApiResult.Success(FormatPlan(fileId = null, version, files.toList()))
    }

    fun fail(bookId: BookId, format: DownloadFormat, error: ApiError) {
        plans.answers[bookId to format] = ApiResult.Failure(error)
    }

    suspend fun book(bookId: BookId): DownloadedBook = checkNotNull(registry.book(bookId))

    suspend fun update(bookId: BookId, format: DownloadFormat): FormatUpdate? =
        book(bookId).formats.first { it.format == format }.update
}
