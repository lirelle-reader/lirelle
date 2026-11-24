package app.tfdn.lirelle.core.downloads.offline

import app.tfdn.lirelle.core.downloads.engine.Books
import app.tfdn.lirelle.core.downloads.engine.Recorded
import app.tfdn.lirelle.core.downloads.engine.textFile
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.BookDetail
import app.tfdn.lirelle.core.library.model.BookFile
import app.tfdn.lirelle.core.library.model.BookFileKind
import app.tfdn.lirelle.core.library.model.MediumCover
import app.tfdn.lirelle.core.library.model.PageDirection
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.sampleBookDetail
import app.tfdn.lirelle.core.testing.sampleComicDetail
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import okio.Buffer
import org.junit.Rule
import org.junit.Test

private val TheBook = BookId("3")
private val OtherBook = BookId("4")
private val MapFile = FileId("31")

class KeptBookDataTest {

    @get:Rule
    internal val fixture = KeptFixture()

    /** A book without an audiobook's own cover; the tests of that cover give it one. */
    private val detail = sampleBookDetail(TheBook.value)

    private fun image(content: String) = MockResponse.Builder().body(Buffer().writeUtf8(content)).build()

    // --- the book's page

    @Test
    fun `a kept detail reads back whole from a new instance`() = runBlocking<Unit> {
        val whole = detail.copy(
            audioCover = MediumCover("https://books.example/audio-cover", "https://books.example/audio-thumb"),
        )
        fixture.server.close()

        fixture.kept().keep(whole)

        assertThat(fixture.kept().detail(TheBook)).isEqualTo(whole)
    }

    @Test
    fun `a detail with nothing in it reads back the same`() = runBlocking<Unit> {
        val bare = detail.copy(
            subtitle = null, series = null, description = null, rating = null, addedAt = null, readState = null,
            startedAt = null, audioCover = null, files = listOf(Books.audio(21, null)), genres = emptyList(),
        )
        fixture.kept().keep(bare)

        assertThat(fixture.kept().detail(TheBook)).isEqualTo(bare)
    }

    private val plain = BookFile(FileId("3"), BookFileKind.Text, "EPUB", 900_000, false, null, null, "p1")

    /** A plain EPUB beside the read-along one, which the source gives an account that may not download. */
    private fun withPlainEpub(): BookDetail =
        detail.copy(files = detail.files + plain, readerFileWithoutDownload = plain)

    @Test
    fun `the file the source gives an account that may not download reads back, as one of the files`() =
        runBlocking<Unit> {
            val book = withPlainEpub()
            fixture.kept().keep(book)

            val read = checkNotNull(fixture.kept().detail(TheBook))

            assertThat(read).isEqualTo(book)
            assertThat(read.readerFile(canDownload = false)?.id).isEqualTo(FileId("3"))
            assertThat(read.readerFile(canDownload = true)?.id).isEqualTo(FileId("1"))
        }

    @Test
    fun `a page kept before the source's choice was kept reads none, and the account reads the usual file`() =
        runBlocking<Unit> {
            val book = withPlainEpub()
            fixture.kept().keep(book)
            val page = fixture.files.kept(TheBook, "detail.json")
            val before = page.readText().replace(""","readerFileWithoutDownload":"3"""", "")
            check(before != page.readText())
            page.writeText(before)

            val read = checkNotNull(fixture.kept().detail(TheBook))

            assertThat(read).isEqualTo(book.copy(readerFileWithoutDownload = null))
            assertThat(read.readerFile(canDownload = false)?.id).isEqualTo(FileId("1"))
        }

    @Test
    fun `a page without the source's choice writes none`() = runBlocking<Unit> {
        fixture.kept().keep(detail)

        assertThat(fixture.files.kept(TheBook, "detail.json").readText()).doesNotContain("readerFileWithoutDownload")
    }

    // --- the direction the server gives its pages

    @Test
    fun `a manga's direction reads back from a new instance`() = runBlocking<Unit> {
        val manga = detail.copy(pageDirection = PageDirection.RightToLeft)
        fixture.kept().keep(manga)

        assertThat(fixture.kept().detail(TheBook)).isEqualTo(manga)
    }

    @Test
    fun `a page kept before the direction was kept, or with one this version does not know, reads none`() =
        runBlocking<Unit> {
            fixture.kept().keep(detail.copy(pageDirection = PageDirection.RightToLeft))
            val page = fixture.files.kept(TheBook, "detail.json")
            val kept = page.readText()
            val before = kept.replace(""","pageDirection":"RightToLeft"""", "")
            check(before != kept)

            page.writeText(before)
            assertThat(fixture.kept().detail(TheBook)).isEqualTo(detail)

            page.writeText(kept.replace(""""RightToLeft"""", """"TopToBottom""""))
            assertThat(fixture.kept().detail(TheBook)).isEqualTo(detail)
        }

    @Test
    fun `a page without a direction writes none`() = runBlocking<Unit> {
        fixture.kept().keep(detail)

        assertThat(fixture.files.kept(TheBook, "detail.json").readText()).doesNotContain("pageDirection")
    }

    @Test
    fun `a book that was not kept has no detail, cover, manifest, map or reading`() = runBlocking<Unit> {
        val kept = fixture.kept()

        assertThat(kept.detail(TheBook)).isNull()
        assertThat(kept.coverFile(TheBook)).isNull()
        assertThat(kept.audioCoverFile(TheBook)).isNull()
        assertThat(kept.manifest(TheBook)).isNull()
        assertThat(kept.map(TheBook, MapFile)).isNull()
        assertThat(kept.reading(TheBook)).isNull()
    }

    @Test
    fun `a kept detail that no longer reads is none`() = runBlocking<Unit> {
        fixture.kept().keep(detail)
        fixture.files.kept(TheBook, "detail.json").writeText("{")

        assertThat(fixture.kept().detail(TheBook)).isNull()
    }

    @Test
    fun `keeping a detail again replaces it`() = runBlocking<Unit> {
        fixture.kept().keep(detail)
        fixture.kept().keep(detail.copy(title = "Another Title"))

        assertThat(fixture.kept().detail(TheBook)!!.title).isEqualTo("Another Title")
    }

    // --- a page that would leave the kept copy nothing to open with

    /** The device keeps [file] whole as the book's text. */
    private suspend fun keepsText(file: FileId) {
        val record = FormatRecord(
            format = DownloadFormat.Text,
            state = FormatState.Complete(1_000),
            files = emptyList(),
            fileId = file,
            version = "v1",
            askedAt = Instant.EPOCH,
        )
        fixture.registry.update(TheBook) { DownloadedBook(TheBook, listOf(record), Instant.EPOCH, null) }
    }

    @Test
    fun `a page the server gives without the kept file keeps the kept files, and takes the rest and the reading`() =
        runBlocking<Unit> {
            keepsText(FileId("1"))
            fixture.kept().keep(withPlainEpub(), readingPercent = 10)
            val readAt = Instant.parse("2026-11-10T08:30:00Z")
            fixture.server.enqueue(image("new cover bytes"))
            val newer = detail.copy(title = "Another Title", coverUrl = fixture.coverUrl(TheBook), files = emptyList())

            // The server answers for the book without its file, for a moment.
            fixture.kept().keep(newer, 42, readAt)

            val read = checkNotNull(fixture.kept().detail(TheBook))
            assertThat(read).isEqualTo(newer.copy(files = withPlainEpub().files, readerFileWithoutDownload = plain))
            assertThat(fixture.kept().reading(TheBook)).isEqualTo(KeptReading(42, readAt))
            assertThat(fixture.kept().coverFile(TheBook)!!.readText()).isEqualTo("new cover bytes")
        }

    @Test
    fun `a page of a comic whose files the server cannot serve for now keeps the kept file, and takes the rest`() =
        runBlocking<Unit> {
            val comic = sampleComicDetail(TheBook.value)
            keepsText(checkNotNull(comic.pagedFile).id)
            fixture.kept().keep(comic)
            val analysed = comic.copy(
                title = "Another Title",
                description = "Another description",
                files = emptyList(),
                filesUnavailable = true,
            )

            fixture.kept().keep(analysed)

            val read = checkNotNull(fixture.kept().detail(TheBook))
            assertThat(read).isEqualTo(analysed.copy(files = comic.files, filesUnavailable = false))
            assertThat(read.pagedFile).isEqualTo(comic.pagedFile)
        }

    @Test
    fun `a page that names another file than the one kept keeps the kept file, and takes the rest`() =
        runBlocking<Unit> {
            // The file 1 is kept; the server now names the file 2, an update not applied yet.
            keepsText(FileId("1"))
            fixture.kept().keep(detail)
            val newer = detail.copy(title = "Another Title", files = listOf(Books.epub(2)))

            fixture.kept().keep(newer)

            val read = checkNotNull(fixture.kept().detail(TheBook))
            assertThat(read).isEqualTo(newer.copy(files = detail.files))
            assertThat(read.textFile?.id).isEqualTo(FileId("1"))
        }

    @Test
    fun `a page that names the kept file replaces the kept page`() = runBlocking<Unit> {
        keepsText(FileId("1"))
        fixture.kept().keep(detail)

        fixture.kept().keep(detail.copy(title = "Another Title"))

        assertThat(fixture.kept().detail(TheBook)!!.title).isEqualTo("Another Title")
    }

    @Test
    fun `a kept page that names no kept file is replaced by any page`() = runBlocking<Unit> {
        fixture.kept().keep(detail.copy(files = emptyList()))
        keepsText(FileId("1"))

        fixture.kept().keep(detail.copy(title = "Another Title", files = listOf(Books.audio(21, null))))

        assertThat(fixture.kept().detail(TheBook)!!.title).isEqualTo("Another Title")
    }

    @Test
    fun `a page is kept with files, never as one the server cannot serve`() = runBlocking<Unit> {
        fixture.kept().keep(detail.copy(filesUnavailable = true))

        assertThat(fixture.kept().detail(TheBook)).isEqualTo(detail)
        assertThat(fixture.files.kept(TheBook, "detail.json").readText()).doesNotContain("filesUnavailable")
    }

    // --- the cover

    @Test
    fun `the cover is fetched into the book's directory, and the detail keeps its address`() = runBlocking<Unit> {
        fixture.server.enqueue(image("cover bytes"))
        val withCover = detail.copy(coverUrl = fixture.coverUrl(TheBook))

        fixture.kept().keep(withCover)

        val cover = fixture.kept().coverFile(TheBook)!!
        assertThat(cover.readText()).isEqualTo("cover bytes")
        assertThat(cover.parentFile).isEqualTo(fixture.files.bookDir(TheBook))
        assertThat(fixture.kept().detail(TheBook)!!.coverUrl).isEqualTo(withCover.coverUrl)
        assertThat(fixture.files.bookDir(TheBook).list()).asList().containsExactly("detail.json", "cover")
    }

    @Test
    fun `a cover that cannot be fetched leaves the detail kept, and the cover there was`() = runBlocking<Unit> {
        val withCover = detail.copy(coverUrl = fixture.coverUrl(TheBook))
        fixture.server.enqueue(image("cover bytes"))
        fixture.kept().keep(withCover)
        fixture.server.enqueue(MockResponse.Builder().code(500).build())

        fixture.kept().keep(withCover.copy(title = "Another Title"))

        assertThat(fixture.kept().detail(TheBook)!!.title).isEqualTo("Another Title")
        assertThat(fixture.kept().coverFile(TheBook)!!.readText()).isEqualTo("cover bytes")
    }

    @Test
    fun `without a server the detail is still kept, without a cover`() = runBlocking<Unit> {
        val withCover = detail.copy(coverUrl = fixture.coverUrl(TheBook))
        fixture.server.close()

        fixture.kept().keep(withCover)

        assertThat(fixture.kept().detail(TheBook)).isEqualTo(withCover)
        assertThat(fixture.kept().coverFile(TheBook)).isNull()
    }

    @Test
    fun `a book that lost its cover loses the kept one`() = runBlocking<Unit> {
        fixture.server.enqueue(image("cover bytes"))
        fixture.kept().keep(detail.copy(coverUrl = fixture.coverUrl(TheBook)))

        fixture.kept().keep(detail.copy(coverUrl = null))

        assertThat(fixture.kept().coverFile(TheBook)).isNull()
    }

    // --- the audiobook's own cover

    private fun withBothCovers() =
        detail.copy(coverUrl = fixture.coverUrl(TheBook), audioCover = fixture.audioCover(TheBook))

    @Test
    fun `the audiobook's own cover is kept beside the book's, and reads back from a new instance`() =
        runBlocking<Unit> {
            fixture.server.enqueue(image("cover bytes"))
            fixture.server.enqueue(image("audio cover bytes"))

            fixture.kept().keep(withBothCovers())

            val audioCover = fixture.kept().audioCoverFile(TheBook)!!
            assertThat(audioCover.readText()).isEqualTo("audio cover bytes")
            assertThat(fixture.kept().coverFile(TheBook)!!.readText()).isEqualTo("cover bytes")
            assertThat(fixture.server.takeRequest().url.toString()).isEqualTo(fixture.coverUrl(TheBook))
            assertThat(fixture.server.takeRequest().url.toString()).isEqualTo(fixture.audioCover(TheBook).url)
            assertThat(fixture.files.bookDir(TheBook).list()).asList()
                .containsExactly("detail.json", "cover", "audio-cover")
        }

    @Test
    fun `a book without an audiobook's own cover keeps none, and asks for none`() = runBlocking<Unit> {
        fixture.server.enqueue(image("cover bytes"))

        fixture.kept().keep(detail.copy(coverUrl = fixture.coverUrl(TheBook)))

        assertThat(fixture.kept().audioCoverFile(TheBook)).isNull()
        assertThat(fixture.server.requestCount).isEqualTo(1)
    }

    @Test
    fun `an audiobook's own cover that cannot be fetched leaves the detail, the cover, and the one there was`() =
        runBlocking<Unit> {
            fixture.server.enqueue(image("cover bytes"))
            fixture.server.enqueue(image("audio cover bytes"))
            fixture.kept().keep(withBothCovers())
            fixture.server.enqueue(image("new cover bytes"))
            fixture.server.enqueue(MockResponse.Builder().code(500).build())

            fixture.kept().keep(withBothCovers().copy(title = "Another Title"))

            assertThat(fixture.kept().detail(TheBook)!!.title).isEqualTo("Another Title")
            assertThat(fixture.kept().coverFile(TheBook)!!.readText()).isEqualTo("new cover bytes")
            assertThat(fixture.kept().audioCoverFile(TheBook)!!.readText()).isEqualTo("audio cover bytes")
        }

    @Test
    fun `an audiobook's own cover that was never fetched is none, with the rest kept`() = runBlocking<Unit> {
        fixture.server.enqueue(image("cover bytes"))
        fixture.server.enqueue(MockResponse.Builder().code(500).build())

        fixture.kept().keep(withBothCovers())

        assertThat(fixture.kept().detail(TheBook)).isEqualTo(withBothCovers())
        assertThat(fixture.kept().coverFile(TheBook)!!.readText()).isEqualTo("cover bytes")
        assertThat(fixture.kept().audioCoverFile(TheBook)).isNull()
    }

    @Test
    fun `a book that lost its audiobook's own cover loses the kept one`() = runBlocking<Unit> {
        fixture.server.enqueue(image("cover bytes"))
        fixture.server.enqueue(image("audio cover bytes"))
        fixture.kept().keep(withBothCovers())
        fixture.server.enqueue(image("cover bytes"))

        fixture.kept().keep(withBothCovers().copy(audioCover = null))

        assertThat(fixture.kept().audioCoverFile(TheBook)).isNull()
        assertThat(fixture.kept().coverFile(TheBook)).isNotNull()
    }

    // --- the reading the server gave

    @Test
    fun `the reading given with the detail reads back`() = runBlocking<Unit> {
        val readAt = Instant.parse("2026-11-10T08:30:00Z")

        fixture.kept().keep(detail, readingPercent = 42, readAt = readAt)

        assertThat(fixture.kept().reading(TheBook)).isEqualTo(KeptReading(42, readAt))
    }

    @Test
    fun `a detail kept without a reading has none, and leaves the one there was`() = runBlocking<Unit> {
        fixture.kept().keep(detail)
        assertThat(fixture.kept().reading(TheBook)).isNull()

        fixture.kept().keep(detail, readingPercent = 42)
        fixture.kept().keep(detail.copy(title = "Another Title"))

        assertThat(fixture.kept().reading(TheBook)).isEqualTo(KeptReading(42, null))
        assertThat(fixture.kept().detail(TheBook)!!.title).isEqualTo("Another Title")
    }

    // --- the audiobook's manifest

    @Test
    fun `a kept manifest reads back whole from a new instance`() = runBlocking<Unit> {
        val manifest = Recorded.manifest("mp3")

        fixture.kept().keepManifest(manifest)

        assertThat(fixture.kept().manifest(manifest.bookId)).isEqualTo(manifest)
        assertThat(fixture.files.bookDir(manifest.bookId).list()).asList().containsExactly("manifest.json")
    }

    // --- the read-along map

    @Test
    fun `a kept map reads back from a new instance as the device's own`() = runBlocking<Unit> {
        val own = fixture.maps.map(TheBook, MapFile, "v1")!!

        assertThat(fixture.kept().keepMap(TheBook, MapFile, "v1")).isTrue()

        val read = fixture.kept().map(TheBook, MapFile)!!
        assertThat(listOf(read.bookId, read.fileId, read.contentVersion)).isEqualTo(listOf(TheBook, MapFile, "v1"))
        assertThat(read.tracks).isEqualTo(own.tracks)
        assertThat(read.sections).isEqualTo(own.sections)
        assertThat(fixture.kept().map(TheBook, FileId("32"))).isNull()
    }

    @Test
    fun `the kept map stays when the device's own maps drop it`() = runBlocking<Unit> {
        fixture.mapsKept = 1
        fixture.kept().keepMap(TheBook, MapFile, "v1")

        fixture.maps.map(OtherBook, FileId("41"), "v1")
        fixture.dropOwnMaps()

        assertThat(fixture.kept().map(TheBook, MapFile)).isNotNull()
    }

    @Test
    fun `a map that cannot be had is not kept`() = runBlocking<Unit> {
        fixture.readAlong.playlistResult = ApiResult.Failure(ApiError.ServerUnreachable)

        assertThat(fixture.kept().keepMap(TheBook, MapFile, "v1")).isFalse()

        assertThat(fixture.kept().map(TheBook, MapFile)).isNull()
    }

    // --- removing

    private suspend fun keepAll() {
        fixture.server.enqueue(image("cover bytes"))
        fixture.server.enqueue(image("audio cover bytes"))
        fixture.kept().keep(withBothCovers(), readingPercent = 42)
        fixture.kept().keepManifest(Recorded.manifest("m4b").copy(bookId = TheBook))
        fixture.kept().keepMap(TheBook, MapFile, "v1")
    }

    @Test
    fun `removing the audiobook keeps the page and what the narration needs`() = runBlocking<Unit> {
        keepAll()

        fixture.kept().remove(TheBook, DownloadFormat.Audiobook)

        val kept = fixture.kept()
        assertThat(kept.manifest(TheBook)).isNull()
        assertThat(kept.map(TheBook, MapFile)).isNotNull()
        assertThat(kept.detail(TheBook)).isNotNull()
        assertThat(kept.coverFile(TheBook)).isNotNull()
        assertThat(kept.audioCoverFile(TheBook)).isNotNull()
    }

    @Test
    fun `removing the narration keeps the page and what the audiobook needs`() = runBlocking<Unit> {
        keepAll()

        fixture.kept().remove(TheBook, DownloadFormat.Narration)

        val kept = fixture.kept()
        assertThat(kept.map(TheBook, MapFile)).isNull()
        assertThat(kept.manifest(TheBook)).isNotNull()
        assertThat(kept.detail(TheBook)).isNotNull()
        assertThat(kept.coverFile(TheBook)).isNotNull()
        assertThat(kept.audioCoverFile(TheBook)).isNotNull()
    }

    @Test
    fun `removing the text removes nothing kept`() = runBlocking<Unit> {
        keepAll()

        fixture.kept().remove(TheBook, DownloadFormat.Text)

        val kept = fixture.kept()
        assertThat(kept.map(TheBook, MapFile)).isNotNull()
        assertThat(kept.manifest(TheBook)).isNotNull()
        assertThat(kept.detail(TheBook)).isNotNull()
    }

    @Test
    fun `removing the book removes all that was kept of it, and of no other`() = runBlocking<Unit> {
        keepAll()
        fixture.kept().keep(detail.copy(id = OtherBook))

        fixture.kept().remove(TheBook, null)

        val kept = fixture.kept()
        assertThat(kept.detail(TheBook)).isNull()
        assertThat(kept.reading(TheBook)).isNull()
        assertThat(kept.coverFile(TheBook)).isNull()
        assertThat(kept.audioCoverFile(TheBook)).isNull()
        assertThat(kept.manifest(TheBook)).isNull()
        assertThat(kept.map(TheBook, MapFile)).isNull()
        assertThat(fixture.files.bookDir(TheBook).list().orEmpty()).isEmpty()
        assertThat(kept.detail(OtherBook)).isNotNull()
    }
}
