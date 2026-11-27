package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.FileKind
import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.PlannedFile
import app.tfdn.lirelle.core.downloads.model.TextName
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.offline.DevicePlaces
import app.tfdn.lirelle.core.downloads.offline.InMemoryNarrations
import app.tfdn.lirelle.core.downloads.offline.InMemoryPlaybackQueue
import app.tfdn.lirelle.core.downloads.offline.InMemoryPlaybacks
import app.tfdn.lirelle.core.downloads.offline.KeptFixture
import app.tfdn.lirelle.core.downloads.offline.KeptPositions
import app.tfdn.lirelle.core.downloads.offline.WaitingBookData
import app.tfdn.lirelle.core.downloads.update.UpdateCheck
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.PagedKind
import app.tfdn.lirelle.core.library.model.ReadingPosition
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.reader.sync.LocalPosition
import app.tfdn.lirelle.core.testing.FakeAudioRepository
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.core.testing.FakeReaderRepository
import app.tfdn.lirelle.core.testing.InMemoryOutbox
import app.tfdn.lirelle.core.testing.InMemoryPositionStore
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import org.junit.Rule
import org.junit.Test

/** Where the source of the tests serves an audiobook's assets: see `FakeAudioRepository.assetUrl`. */
private const val AudioServer = "https://books.example/audio"

/** Where the source of the tests serves a read-along's tracks: see `FakeReadAlongRepository.trackUrl`. */
private const val NarrationServer = "https://books.example/narration"

/** Where the source of the tests serves a whole file: see `FakeReaderRepository.fileUrl`. */
private const val FilesServer = "https://books.example/files"

/** Where the source of the tests serves the pages: see [testPageUrl]. */
private const val PagesServer = "https://books.example/pages"
private val Epub = FileKind.Epub
private val SafeName = Regex("[A-Za-z0-9._-]+")

class FormatPlannerTest {

    @get:Rule
    internal val fixture = KeptFixture()

    private val library = FakeLibraryRepository()
    private val reader = FakeReaderRepository()
    private val audio = FakeAudioRepository()
    private val kept by lazy { fixture.kept() }
    /** The one the kept page is guarded by too. */
    private val registry = fixture.registry
    private val positions = InMemoryPositionStore()
    private val places =
        DevicePlaces(positions, InMemoryOutbox(), InMemoryNarrations(), InMemoryPlaybacks(), InMemoryPlaybackQueue())
    private val served = FakeServedPages()
    private val paged = PagedPlans(served, registry)
    private val planner by lazy {
        val keptPositions = KeptPositions(registry, reader, audio, kept, places)
        val data = PlannedData(kept, WaitingBookData(fixture.files, fixture.maps), registry)
        FormatPlanner(library, audio, fixture.readAlong, fixture.maps, data, keptPositions, paged)
    }

    /** Where the profile's server serves what is planned: the plan says what each file is, these say where. */
    private val locations by lazy { TestLocations({ reader.server }, reader, audio, fixture.readAlong) }

    private fun addresses(files: List<PlannedFile>): List<String?> = files.map { locations.url(TheBook, it.source) }

    private fun serve(book: BookDetail) {
        library.bookResult = ApiResult.Success(book)
    }

    private suspend fun planned(format: DownloadFormat): FormatPlan =
        (planner.plan(TheBook, format) as ApiResult.Success).value

    private suspend fun refused(format: DownloadFormat): ApiError =
        (planner.plan(TheBook, format) as ApiResult.Failure).error

    // --- the positions

    @Test
    fun `planning a format of a downloaded book keeps where the server says the user is`() = runBlocking<Unit> {
        val at = Instant.parse("2026-11-16T10:00:00Z")
        serve(Books.book(Books.epub(11)))
        registry.update(TheBook) { DownloadedBook(TheBook, listOf(queued(DownloadFormat.Text, at)), at, null) }
        reader.positionResult = ApiResult.Success(ReadingPosition(FileId("11"), "epubcfi(/6/8)", 2.0, at))

        planned(DownloadFormat.Text)

        assertThat(positions.get(FileId("11"))).isEqualTo(LocalPosition(FileId("11"), "epubcfi(/6/8)", 2.0, at))
    }

    @Test
    fun `a plan that fails keeps no position, and positions that cannot be had fail no plan`() = runBlocking<Unit> {
        val at = Instant.parse("2026-11-16T10:00:00Z")
        registry.update(TheBook) { DownloadedBook(TheBook, listOf(queued(DownloadFormat.Text, at)), at, null) }
        reader.positionResult = ApiResult.Success(ReadingPosition(FileId("11"), "epubcfi(/6/8)", 2.0, at))
        serve(Books.book(Books.audio(21, 1_000)))

        assertThat(refused(DownloadFormat.Text)).isEqualTo(ApiError.NotFound)
        assertThat(positions.positions).isEmpty()

        serve(Books.book(Books.epub(11)))
        reader.positionResult = ApiResult.Failure(ApiError.ServerUnreachable)
        assertThat(planned(DownloadFormat.Text).fileId).isEqualTo(FileId("11"))
    }

    // --- text

    @Test
    fun `the text of a plain EPUB is the file itself, resumable, at its version and size`() = runBlocking<Unit> {
        serve(Books.book(Books.epub(11, sizeBytes = 1_200_000)))

        val plan = planned(DownloadFormat.Text)

        assertThat(plan).isEqualTo(
            FormatPlan(
                fileId = FileId("11"),
                version = "text-11",
                files = listOf(
                    PlannedFile("11.epub", FileSource.Whole(FileId("11")), "text-11", 1_200_000, true, FileKind.Epub),
                ),
            ),
        )
        assertThat(addresses(plan.files)).containsExactly("$FilesServer/11")
    }

    @Test
    fun `the text of a read-along file is the EPUB without its audio, not resumable, of unknown size`() =
        runBlocking<Unit> {
            serve(Books.book(Books.readAlong(31), Books.audio(21, 1_000)))

            val plan = planned(DownloadFormat.Text)

            assertThat(plan.fileId).isEqualTo(FileId("31"))
            assertThat(plan.version).isEqualTo("along-31")
            assertThat(plan.files).containsExactly(
                PlannedFile(
                    name = "31.epub",
                    source = FileSource.Audioless(FileId("31")),
                    version = "along-31",
                    expectedBytes = null,
                    resumable = false,
                    kind = FileKind.Epub,
                ),
            )
            assertThat(addresses(plan.files)).containsExactly("$FilesServer/31/audioless")
        }

    @Test
    fun `a book without a file to read has no text to plan`() = runBlocking<Unit> {
        serve(Books.book(Books.audio(21, 1_000)))

        assertThat(refused(DownloadFormat.Text)).isEqualTo(ApiError.NotFound)
        assertThat(kept.detail(TheBook)).isNull()
    }

    @Test
    fun `the text of a PDF is the file itself, under its own extension, checked as a PDF`() = runBlocking<Unit> {
        val book = Books.book(Books.epub(11, sizeBytes = 5_000_000, format = "PDF"))
        serve(book)

        val plan = planned(DownloadFormat.Text)

        assertThat(plan).isEqualTo(
            FormatPlan(
                fileId = FileId("11"),
                version = "text-11",
                files = listOf(
                    PlannedFile("11.pdf", FileSource.Whole(FileId("11")), "text-11", 5_000_000, true, FileKind.Pdf),
                ),
                textName = TextName.Pdf,
            ),
        )
        assertThat(addresses(plan.files)).containsExactly("$FilesServer/11")
        assertThat(kept.detail(TheBook)?.pagedFile?.id).isEqualTo(FileId("11"))
    }

    @Test
    fun `the text of a Kindle book or a FictionBook is the file itself, taken as it is`() = runBlocking<Unit> {
        val names = mapOf("MOBI" to "11.mobi", "AZW" to "11.azw", "AZW3" to "11.azw3", "FB2" to "11.fb2")
        names.forEach { (format, name) ->
            // The EPUB beside is not the file the reader opens: it is not kept in the place of the other.
            serve(Books.book(Books.epub(11, format = format), Books.epub(12, primary = false)))

            val plan = planned(DownloadFormat.Text)

            assertThat(plan.fileId).isEqualTo(FileId("11"))
            assertThat(plan.files).containsExactly(
                PlannedFile(name, FileSource.Whole(FileId("11")), "text-11", 1_200_000, true, FileKind.Book),
            )
            assertThat(addresses(plan.files)).containsExactly("$FilesServer/11")
        }
        serve(Books.book(Books.epub(11, format = "KEPUB")))
        assertThat(planned(DownloadFormat.Text).files.single().name).isEqualTo("11.epub")
        assertThat(planned(DownloadFormat.Text).files.single().kind).isEqualTo(FileKind.Epub)
    }

    @Test
    fun `the text of a comic is its pages, as many as the server counts, where the source serves a comic's`() =
        runBlocking<Unit> {
            val names = mapOf("CBZ" to TextName.Cbz, "CBR" to TextName.Cbr, "CB7" to TextName.Cb7)
            served.count = ApiResult.Success(3)
            names.forEach { (format, name) ->
                serve(Books.book(Books.epub(11, sizeBytes = 48_000_000, format = format)))

                val plan = planned(DownloadFormat.Text)

                assertThat(plan).isEqualTo(
                    FormatPlan(
                        fileId = FileId("11"),
                        version = "text-11",
                        files = pageFiles(FileId("11"), PagedKind.Comic, 3, "text-11"),
                        textName = name,
                        expectedBytes = 48_000_000,
                    ),
                )
            }
            assertThat(served.asked.distinct()).containsExactly(PagedKind.Comic to FileId("11"))
            assertThat(addresses(planned(DownloadFormat.Text).files)).containsExactly(
                "$PagesServer/comic/11/0", "$PagesServer/comic/11/1", "$PagesServer/comic/11/2",
            ).inOrder()
            assertThat(kept.detail(TheBook)?.pagedFile?.id).isEqualTo(FileId("11"))
        }

    @Test
    fun `the text of a DjVu is its pages, counted and fetched as a DjVu's`() = runBlocking<Unit> {
        served.count = ApiResult.Success(2)
        serve(Books.book(Books.epub(11, sizeBytes = 9_000_000, format = "DJVU")))

        val plan = planned(DownloadFormat.Text)

        assertThat(served.asked).containsExactly(PagedKind.Djvu to FileId("11"))
        assertThat(addresses(plan.files))
            .containsExactly("$PagesServer/djvu/11/0", "$PagesServer/djvu/11/1").inOrder()
        assertThat(plan.files.map { it.kind }.distinct()).containsExactly(FileKind.Page)
        assertThat(plan.textName).isEqualTo(TextName.Djvu)
        assertThat(plan.expectedBytes).isEqualTo(9_000_000)
    }

    @Test
    fun `an album whose pages cannot be counted is not planned, and keeps nothing`() = runBlocking<Unit> {
        serve(Books.book(Books.epub(11, format = "CBZ")))

        served.count = ApiResult.Failure(ApiError.ServerUnreachable)
        assertThat(refused(DownloadFormat.Text)).isEqualTo(ApiError.ServerUnreachable)
        // An album of no page is nothing to read.
        served.count = ApiResult.Success(0)
        assertThat(refused(DownloadFormat.Text)).isEqualTo(ApiError.Unexpected)

        assertThat(kept.detail(TheBook)).isNull()
    }

    @Test
    fun `a reflowable text is planned under the name of a text`() = runBlocking<Unit> {
        listOf("EPUB", "KEPUB", "MOBI", "AZW3", "FB2").forEach { format ->
            serve(Books.book(Books.epub(11, format = format)))

            assertThat(planned(DownloadFormat.Text).textName).isEqualTo(TextName.Text)
            assertThat(planned(DownloadFormat.Text).expectedBytes).isNull()
        }
        assertThat(served.asked).isEmpty()
    }

    @Test
    fun `planning the text of a PDF keeps the page the server says the user is at`() = runBlocking<Unit> {
        val at = Instant.parse("2026-11-16T10:00:00Z")
        serve(Books.book(Books.epub(11, format = "PDF")))
        registry.update(TheBook) { DownloadedBook(TheBook, listOf(queued(DownloadFormat.Text, at)), at, null) }
        reader.positionResult =
            ApiResult.Success(ReadingPosition(FileId("11"), cfi = null, percent = 25.0, at, page = 75))

        planned(DownloadFormat.Text)

        assertThat(positions.get(FileId("11"))).isEqualTo(LocalPosition(FileId("11"), null, 25.0, at, page = 75))
    }

    // --- audiobook

    @Test
    fun `an audiobook is its manifest's files in order, each at its own version and size`() = runBlocking<Unit> {
        val manifest = Recorded.manifest("mp3").copy(bookId = TheBook)
        serve(Books.book(Books.audio(50, 27_807_684)))
        audio.manifestResult = ApiResult.Success(manifest)

        val plan = planned(DownloadFormat.Audiobook)

        assertThat(plan.fileId).isNull()
        assertThat(plan.version).isEqualTo(manifest.contentVersion)
        assertThat(plan.files).hasSize(40)
        assertThat(plan.files.take(2)).containsExactly(
            PlannedFile(
                name = "0-aud_49",
                source = FileSource.AudioAsset("aud_49"),
                version = manifest.assets[0].etag,
                expectedBytes = 27_807_684,
                resumable = true,
                kind = FileKind.Audio,
                cacheKey = "aud_49:${manifest.assets[0].etag}",
            ),
            PlannedFile(
                name = "1-aud_51",
                source = FileSource.AudioAsset("aud_51"),
                version = manifest.assets[1].etag,
                expectedBytes = 14_711_768,
                resumable = true,
                kind = FileKind.Audio,
                cacheKey = "aud_51:${manifest.assets[1].etag}",
            ),
        ).inOrder()
        assertThat(addresses(plan.files.take(2))).containsExactly(
            "$AudioServer/$TheBook/aud_49",
            "$AudioServer/$TheBook/aud_51",
        ).inOrder()
        assertThat(plan.files.map { it.name }).isEqualTo(manifest.assets.map { "${it.sequence}-${it.assetId}" })
        assertThat(plan.files.last().name).isEqualTo("39-aud_127")
        assertThat(plan.files.sumOf { it.expectedBytes!! }).isEqualTo(444_171_405)
    }

    @Test
    fun `the manifest's files are planned by their sequence, under names that are safe on the device`() =
        runBlocking<Unit> {
            val recorded = Recorded.manifest("mp3").copy(bookId = TheBook)
            val odd = recorded.assets[0].copy(assetId = "../a b/é.epub", sequence = 7)
            val manifest = recorded.copy(assets = listOf(odd, recorded.assets[1], recorded.assets[0]))
            serve(Books.book(Books.audio(50, 1_000)))
            audio.manifestResult = ApiResult.Success(manifest)

            val names = planned(DownloadFormat.Audiobook).files.map { it.name }

            assertThat(names.take(2)).containsExactly("0-aud_49", "1-aud_51").inOrder()
            assertThat(names.last()).startsWith("7-")
            names.forEach { assertThat(it).matches(SafeName.pattern) }
            assertThat(names.last()).doesNotMatch(".*\\.epub")
        }

    @Test
    fun `an audiobook's plan keeps its manifest`() = runBlocking<Unit> {
        val manifest = Recorded.manifest("m4b").copy(bookId = TheBook)
        serve(Books.book(Books.audio(3, 606_399_910)))
        audio.manifestResult = ApiResult.Success(manifest)

        planned(DownloadFormat.Audiobook)

        assertThat(fixture.kept().manifest(TheBook)).isEqualTo(manifest)
        assertThat(audio.calls).containsExactly("manifest $TheBook")
    }

    @Test
    fun `a plan failing on the manifest fails without keeping half`() = runBlocking<Unit> {
        serve(Books.book(Books.audio(50, 1_000)))
        audio.manifestResult = ApiResult.Failure(ApiError.ServerFailed)

        assertThat(refused(DownloadFormat.Audiobook)).isEqualTo(ApiError.ServerFailed)

        assertThat(kept.detail(TheBook)).isNull()
        assertThat(kept.manifest(TheBook)).isNull()
        assertThat(fixture.files.bookDir(TheBook).list().orEmpty()).isEmpty()
    }

    // --- narration

    @Test
    fun `a narration is the EPUB without audio, then the map's tracks in reading order`() = runBlocking<Unit> {
        serve(Books.book(Books.readAlong(31)))

        val plan = planned(DownloadFormat.Narration)

        // The map lists track003 last, after two tracks no sentence uses.
        assertThat(plan.fileId).isEqualTo(FileId("31"))
        assertThat(plan.version).isEqualTo("along-31")
        assertThat(plan.files).containsExactly(
            PlannedFile("31.epub", FileSource.Audioless(FileId("31")), "along-31", null, false, Epub),
            track("000-OEBPS_Audio_track001.mp4", "OEBPS/Audio/track001.mp4", 42_274_000),
            track("001-OEBPS_Audio_track002.mp4", "OEBPS/Audio/track002.mp4", 27_148_619),
            track("002-OEBPS_Audio_track003.mp4", "OEBPS/Audio/track003.mp4", 46_797_680),
        ).inOrder()
        assertThat(addresses(plan.files)).containsExactly(
            "$FilesServer/31/audioless",
            "$NarrationServer/$TheBook/31/OEBPS/Audio/track001.mp4",
            "$NarrationServer/$TheBook/31/OEBPS/Audio/track002.mp4",
            "$NarrationServer/$TheBook/31/OEBPS/Audio/track003.mp4",
        ).inOrder()
    }

    private fun track(name: String, href: String, bytes: Long) = PlannedFile(
        name = name,
        source = FileSource.ReadAlongTrack(FileId("31"), href),
        version = "31:along-31",
        expectedBytes = bytes,
        resumable = true,
        kind = FileKind.Audio,
    )

    @Test
    fun `a track read in several places is planned once, under a name that is safe on the device`() =
        runBlocking<Unit> {
            serve(Books.book(Books.readAlong(31)))
            val map = fixture.maps.map(TheBook, FileId("31"), "along-31")!!

            val tracks = planned(DownloadFormat.Narration).files.drop(1)

            assertThat(tracks).hasSize(map.tracks.map { it.href }.distinct().size)
            assertThat(tracks.map { it.name }).containsNoDuplicates()
            tracks.forEach {
                assertThat(it.name).matches(SafeName.pattern)
                assertThat(it.name).doesNotMatch(".*\\.epub")
            }
        }

    @Test
    fun `the narration and the text of a read-along file plan the same EPUB`() = runBlocking<Unit> {
        serve(Books.book(Books.readAlong(31)))

        val text = planned(DownloadFormat.Text).files.single()
        val narration = planned(DownloadFormat.Narration).files.first()

        assertThat(narration).isEqualTo(text)
    }

    @Test
    fun `beside a plain EPUB the reader opens, the narration takes the read-along file's own EPUB`() =
        runBlocking<Unit> {
            serve(Books.book(Books.epub(11), Books.readAlong(31, primary = false)))

            assertThat(planned(DownloadFormat.Text).files.single().name).isEqualTo("11.epub")
            assertThat(planned(DownloadFormat.Narration).files.first().name).isEqualTo("31.epub")
        }

    @Test
    fun `a narration's plan keeps its map`() = runBlocking<Unit> {
        serve(Books.book(Books.readAlong(31)))

        planned(DownloadFormat.Narration)
        fixture.dropOwnMaps()

        val map = fixture.kept().map(TheBook, FileId("31"))!!
        assertThat(map.contentVersion).isEqualTo("along-31")
        assertThat(map.tracks.map { it.href }.distinct()).hasSize(3)
    }

    @Test
    fun `a narration whose map cannot be had fails as the server said, keeping nothing`() = runBlocking<Unit> {
        serve(Books.book(Books.readAlong(31)))
        fixture.readAlong.spineResult = ApiResult.Failure(ApiError.ServerFailed)

        assertThat(refused(DownloadFormat.Narration)).isEqualTo(ApiError.ServerFailed)

        assertThat(fixture.files.bookDir(TheBook).list().orEmpty()).isEmpty()
    }

    @Test
    fun `a book without a read-along file has no narration to plan`() = runBlocking<Unit> {
        serve(Books.book(Books.epub(11)))

        assertThat(refused(DownloadFormat.Narration)).isEqualTo(ApiError.NotFound)
    }

    // --- a format the device already has

    @Test
    fun `a plan for a check keeps neither manifest nor map`() = runBlocking<Unit> {
        serve(Books.book(Books.readAlong(31), Books.audio(3, 606_399_910)))
        audio.manifestResult = ApiResult.Success(Recorded.manifest("m4b").copy(bookId = TheBook))

        val audiobook = planner.plan(TheBook, DownloadFormat.Audiobook, PlanFor.Check)
        val narration = planner.plan(TheBook, DownloadFormat.Narration, PlanFor.Check)

        assertThat(audiobook).isInstanceOf(ApiResult.Success::class.java)
        assertThat(narration).isInstanceOf(ApiResult.Success::class.java)
        assertThat(kept.manifest(TheBook)).isNull()
        assertThat(kept.map(TheBook, FileId("31"))).isNull()
        assertThat(fixture.files.bookDir(TheBook).list().orEmpty().toList()).containsExactly("detail.json")
    }

    @Test
    fun `a plan for an update puts the manifest and the map beside the kept ones`() = runBlocking<Unit> {
        serve(Books.book(Books.readAlong(31), Books.audio(3, 606_399_910)))
        audio.manifestResult = ApiResult.Success(Recorded.manifest("m4b").copy(bookId = TheBook))

        planner.plan(TheBook, DownloadFormat.Audiobook, PlanFor.Update)
        planner.plan(TheBook, DownloadFormat.Narration, PlanFor.Update)

        assertThat(kept.manifest(TheBook)).isNull()
        assertThat(kept.map(TheBook, FileId("31"))).isNull()
        val updates = fixture.files.updates
        assertThat(updates.file(TheBook, DownloadFormat.Audiobook, "manifest.json").isFile).isTrue()
        assertThat(updates.file(TheBook, DownloadFormat.Narration, "map.gz").isFile).isTrue()
    }

    /** The device keeps the three pages of the comic 11, whole, in [version]. */
    private suspend fun keepAlbum(version: String) {
        val at = Instant.parse("2026-11-16T10:00:00Z")
        val record = queued(DownloadFormat.Text, at).copy(
            state = FormatState.Complete(21),
            files = pageFiles(FileId("11"), PagedKind.Comic, 3, version),
            fileId = FileId("11"),
            version = version,
            textName = TextName.Cbz,
        )
        registry.update(TheBook) { DownloadedBook(TheBook, listOf(record), at, null) }
        serve(Books.book(Books.epub(11, sizeBytes = 48_000_000, format = "CBZ")))
    }

    @Test
    fun `a check of a kept album whose file did not change counts no page`() = runBlocking<Unit> {
        keepAlbum(version = "text-11")
        served.count = ApiResult.Failure(ApiError.NotFound)

        val plan = planner.plan(TheBook, DownloadFormat.Text, PlanFor.Check)

        val pages = pageFiles(FileId("11"), PagedKind.Comic, 3, "text-11")
        val expected = FormatPlan(FileId("11"), "text-11", pages, TextName.Cbz, 48_000_000)
        assertThat(plan).isEqualTo(ApiResult.Success(expected))
        assertThat(served.asked).isEmpty()
    }

    @Test
    fun `a check of a kept album whose file changed counts the pages of the new one`() = runBlocking<Unit> {
        keepAlbum(version = "older")
        served.count = ApiResult.Success(5)

        val plan = planner.plan(TheBook, DownloadFormat.Text, PlanFor.Check)

        assertThat((plan as ApiResult.Success).value.files)
            .isEqualTo(pageFiles(FileId("11"), PagedKind.Comic, 5, "text-11"))
        assertThat(served.asked).containsExactly(PagedKind.Comic to FileId("11"))
    }

    @Test
    fun `pages the server will not count, of a book it answers for, do not say the kept book is gone`() =
        runBlocking<Unit> {
            keepAlbum(version = "older")
            served.count = ApiResult.Failure(ApiError.NotFound)
            val before = registry.book(TheBook)

            val answered = UpdateCheck(registry, planner).check(TheBook, canDownload = true)

            assertThat(planner.plan(TheBook, DownloadFormat.Text, PlanFor.Check))
                .isEqualTo(ApiResult.Failure(ApiError.Unexpected))
            assertThat(answered).isFalse()
            assertThat(registry.book(TheBook)).isEqualTo(before)
            // A download says what the server said.
            assertThat(refused(DownloadFormat.Text)).isEqualTo(ApiError.NotFound)
        }

    @Test
    fun `a kept album whose files the server cannot serve for now is neither gone nor updated, until it can`() =
        runBlocking<Unit> {
            keepAlbum(version = "text-11")
            val album = Books.book(Books.epub(11, sizeBytes = 48_000_000, format = "CBZ"))
            kept.keep(album)
            // Komga analyses the album's new file: it answers for the book, without its file.
            serve(album.copy(files = emptyList(), filesUnavailable = true))
            val before = registry.book(TheBook)
            val check = UpdateCheck(registry, planner)

            assertThat(check.check(TheBook, canDownload = true)).isFalse()

            assertThat(registry.book(TheBook)).isEqualTo(before)
            assertThat(planner.plan(TheBook, DownloadFormat.Text, PlanFor.Check))
                .isEqualTo(ApiResult.Failure(ApiError.Unexpected))
            assertThat(kept.detail(TheBook)).isEqualTo(album)
            assertThat(served.asked).isEmpty()

            // Analysed: the file is served again, its version the date of the new one.
            val newer = "2026-11-23T10:00:00Z"
            serve(album.copy(files = album.files.map { it.copy(contentVersion = newer) }))
            served.count = ApiResult.Success(4)

            assertThat(check.check(TheBook, canDownload = true)).isTrue()

            val book = checkNotNull(registry.book(TheBook))
            assertThat(book.gone).isFalse()
            assertThat(book.formats.single().update?.version).isEqualTo(newer)
            assertThat(book.formats.single().update?.pages?.total).isEqualTo(4)
        }

    @Test
    fun `an update of a kept album whose files the server cannot serve for now fails for now, not as gone`() =
        runBlocking<Unit> {
            keepAlbum(version = "text-11")
            val album = Books.book(Books.epub(11, sizeBytes = 48_000_000, format = "CBZ"))
            kept.keep(album)
            // The update was offered; Komga then analyses the album's file again before it is fetched.
            serve(album.copy(files = emptyList(), filesUnavailable = true))

            val plan = planner.plan(TheBook, DownloadFormat.Text, PlanFor.Update)

            // Something went wrong, to retry: the book is not said to be no longer there.
            assertThat(plan).isEqualTo(ApiResult.Failure(ApiError.Unexpected))
            assertThat(kept.detail(TheBook)).isEqualTo(album)
            assertThat(fixture.files.updates.file(TheBook, DownloadFormat.Text, "detail.json").exists()).isFalse()
            assertThat(served.asked).isEmpty()
        }

    @Test
    fun `a download of a book whose files the server cannot serve for now fails for want of its file`() =
        runBlocking<Unit> {
            serve(Books.book(Books.epub(11, format = "CBZ")).copy(files = emptyList(), filesUnavailable = true))

            assertThat(refused(DownloadFormat.Text)).isEqualTo(ApiError.NotFound)
        }

    @Test
    fun `a text file that cannot be kept is no download, and no answer for a check or an update`() =
        runBlocking<Unit> {
            keepAlbum(version = "text-11")
            val epub = Books.book(Books.epub(11, format = "EPUB"))
            kept.keep(epub)
            // The chapter gained a second file: the server now sends a zip of them.
            serve(epub.copy(files = epub.files.map { it.copy(keepable = false) }))

            assertThat(planner.plan(TheBook, DownloadFormat.Text, PlanFor.Download))
                .isEqualTo(ApiResult.Failure(ApiError.NotFound))
            assertThat(planner.plan(TheBook, DownloadFormat.Text, PlanFor.Check))
                .isEqualTo(ApiResult.Failure(ApiError.Unexpected))
            assertThat(planner.plan(TheBook, DownloadFormat.Text, PlanFor.Update))
                .isEqualTo(ApiResult.Failure(ApiError.Unexpected))
            assertThat(kept.detail(TheBook)).isEqualTo(epub)
            assertThat(served.asked).isEmpty()
        }

    // --- every plan

    @Test
    fun `every plan keeps the book's page`() = runBlocking<Unit> {
        val book = Books.book(Books.readAlong(31), Books.audio(3, 606_399_910))
        serve(book)
        audio.manifestResult = ApiResult.Success(Recorded.manifest("m4b").copy(bookId = TheBook))

        DownloadFormat.entries.forEach { format ->
            kept.remove(TheBook, null)

            planned(format)

            assertThat(fixture.kept().detail(TheBook)).isEqualTo(book)
        }
    }

    @Test
    fun `a cover that cannot be fetched does not fail the plan`() = runBlocking<Unit> {
        fixture.server.enqueue(MockResponse.Builder().code(500).build())
        val book = Books.book(Books.epub(11)).copy(coverUrl = fixture.coverUrl(TheBook))
        serve(book)

        assertThat(planned(DownloadFormat.Text).files).hasSize(1)

        assertThat(kept.detail(TheBook)).isEqualTo(book)
        assertThat(kept.coverFile(TheBook)).isNull()
    }

    @Test
    fun `a book that cannot be read from the server has no plan`() = runBlocking<Unit> {
        library.bookResult = ApiResult.Failure(ApiError.ServerUnreachable)

        assertThat(refused(DownloadFormat.Text)).isEqualTo(ApiError.ServerUnreachable)
    }

    @Test
    fun `a book merged into another is not planned under its old id`() = runBlocking<Unit> {
        serve(Books.book(Books.epub(11), id = "9"))

        assertThat(refused(DownloadFormat.Text)).isEqualTo(ApiError.Moved(BookId("9")))
        assertThat(kept.detail(BookId("9"))).isNull()
    }

    @Test
    fun `signed out, the files planned have no address, and wait for a server to fetch them from`() =
        runBlocking<Unit> {
            serve(Books.book(Books.epub(11)))
            val plan = planned(DownloadFormat.Text)

            reader.server = null

            assertThat(addresses(plan.files)).containsExactly(null)
        }
}
