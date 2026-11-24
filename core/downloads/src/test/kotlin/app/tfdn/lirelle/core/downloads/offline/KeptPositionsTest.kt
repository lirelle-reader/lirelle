package app.tfdn.lirelle.core.downloads.offline

import app.tfdn.lirelle.core.audio.position.DataStorePlaybackStore
import app.tfdn.lirelle.core.audio.position.LocalPlayback
import app.tfdn.lirelle.core.audio.timeline.BookTimeline
import app.tfdn.lirelle.core.downloads.engine.Books
import app.tfdn.lirelle.core.downloads.engine.MemoryRegistry
import app.tfdn.lirelle.core.downloads.engine.Recorded
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.model.DownloadedBook
import app.tfdn.lirelle.core.downloads.store.DataStores
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.NarrationPlace
import app.tfdn.lirelle.core.library.model.PlaybackState
import app.tfdn.lirelle.core.library.model.ReadingPosition
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.reader.sync.LocalNarration
import app.tfdn.lirelle.core.reader.sync.LocalPosition
import app.tfdn.lirelle.core.reader.sync.PositionStore
import app.tfdn.lirelle.core.reader.sync.PositionSync
import app.tfdn.lirelle.core.reader.sync.RestoredPosition
import app.tfdn.lirelle.core.testing.FakeAudioRepository
import app.tfdn.lirelle.core.testing.FakeReaderRepository
import app.tfdn.lirelle.core.testing.InMemoryOutbox
import app.tfdn.lirelle.core.testing.InMemoryPositionStore
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private val TheBook = BookId("3")
private val AlongFile = FileId("32")
private const val Cfi = "epubcfi(/6/8!/4/2)"

private fun at(minute: Int): Instant = Instant.parse("2026-11-16T10:00:00Z").plusSeconds(minute * 60L)

/** The positions of the server kept on the device with a download, each by its rule. */
@RunWith(RobolectricTestRunner::class)
class KeptPositionsTest {

    @get:Rule
    internal val fixture = KeptFixture()

    @get:Rule
    val dataStores = DataStores()

    private val registry = MemoryRegistry()
    private val reader = FakeReaderRepository()
    private val audio = FakeAudioRepository()
    private val positions = InMemoryPositionStore()
    private val outbox = InMemoryOutbox()
    private val narrations = InMemoryNarrations()
    private val playbacks = mutableMapOf<String, DataStorePlaybackStore>()

    /** The file the listening is kept in: another name is a device that kept none yet. */
    private var playbackFile = "playback"
    private val playback
        get() = playbacks.getOrPut(playbackFile) { DataStorePlaybackStore(dataStores.create(playbackFile)) }
    private val playbackOutbox = InMemoryPlaybackQueue()
    private val detail = Books.book(Books.readAlong(32), Books.audio(33, null), id = TheBook.value)
    private val manifest = Recorded.manifest("mp3").copy(bookId = TheBook)
    private val second = manifest.assets.sortedBy { it.sequence }[1]
    private val place = NarrationPlace(seconds = 12.0, fragment = "chapter.xhtml#s3", sectionIndex = 1)
    private val book = DownloadedBook(TheBook, emptyList(), at(0), lastOpenedAt = null)

    private fun places() = DevicePlaces(positions, outbox, narrations, playback, playbackOutbox)

    private fun keptPositions() = KeptPositions(registry, reader, audio, fixture.kept(), places())

    private fun serverText(percent: Double, minute: Int?, narrationMinute: Int? = null) {
        reader.positionResult = ApiResult.Success(
            ReadingPosition(
                fileId = AlongFile,
                cfi = Cfi,
                percent = percent,
                updatedAt = minute?.let(::at),
                narration = place.takeIf { narrationMinute != null },
                narrationUpdatedAt = narrationMinute?.let(::at),
            ),
        )
    }

    private fun serverAudio(positionMs: Long, minute: Int, version: String = "v4") {
        audio.playbackStateResult = ApiResult.Success(
            PlaybackState(
                second.assetId, positionMs, 1.5, completed = false, at(minute), version, manifest.contentVersion,
            ),
        )
    }

    private fun listened(bookTimeMs: Long, minute: Int) = LocalPlayback(
        bookId = TheBook,
        assetId = manifest.assets.first().assetId,
        positionMs = bookTimeMs,
        bookTimeMs = bookTimeMs,
        at = at(minute),
        contentVersion = manifest.contentVersion,
    )

    @Before
    fun aDownloadedBook() = runBlocking {
        registry.update(TheBook) { book }
        fixture.kept().keep(detail)
        fixture.kept().keepManifest(manifest)
    }

    // --- the text

    @Test
    fun `a position only the server knows is kept on the device`() = runBlocking<Unit> {
        serverText(2.0, minute = 5)

        keptPositions().refresh(detail)

        assertThat(positions.get(AlongFile)).isEqualTo(LocalPosition(AlongFile, Cfi, 2.0, at(5)))
    }

    @Test
    fun `the page of a comic the server knows is kept with its position`() = runBlocking<Unit> {
        reader.positionResult =
            ApiResult.Success(ReadingPosition(AlongFile, cfi = null, percent = 25.0, updatedAt = at(5), page = 75))

        keptPositions().refresh(detail)

        assertThat(positions.get(AlongFile)).isEqualTo(LocalPosition(AlongFile, null, 25.0, at(5), page = 75))
    }

    @Test
    fun `the page of a kept PDF is kept as the page reader keeps it, and a newer page of the device stays`() =
        runBlocking<Unit> {
            val pdf = Books.book(Books.epub(41, format = "PDF"), id = TheBook.value)
            reader.positionResult =
                ApiResult.Success(
                    ReadingPosition(FileId("41"), cfi = null, percent = 10.0, updatedAt = at(5), page = 12),
                )

            keptPositions().refresh(pdf)
            assertThat(positions.get(FileId("41"))).isEqualTo(LocalPosition(FileId("41"), null, 10.0, at(5), page = 12))

            val own = LocalPosition(FileId("41"), null, 50.0, at(9), page = 60)
            positions.put(own)
            keptPositions().refresh(pdf)
            assertThat(positions.get(FileId("41"))).isEqualTo(own)
        }

    @Test
    fun `an older position of the device is replaced`() = runBlocking<Unit> {
        positions.put(LocalPosition(AlongFile, "epubcfi(/6/2)", 1.0, at(1)))
        serverText(2.0, minute = 5)

        keptPositions().refresh(detail)

        assertThat(positions.get(AlongFile)).isEqualTo(LocalPosition(AlongFile, Cfi, 2.0, at(5)))
    }

    @Test
    fun `a newer position of the device stays, kept or still to send`() = runBlocking<Unit> {
        val own = LocalPosition(AlongFile, "epubcfi(/6/20)", 40.0, at(9))
        serverText(2.0, minute = 5)

        positions.put(own)
        keptPositions().refresh(detail)
        assertThat(positions.get(AlongFile)).isEqualTo(own)

        positions.positions.clear()
        outbox.enqueuePosition(own)
        keptPositions().refresh(detail)
        assertThat(positions.get(AlongFile)).isNull()
    }

    @Test
    fun `a file never opened on the server keeps nothing`() = runBlocking<Unit> {
        serverText(0.0, minute = null)

        keptPositions().refresh(detail)

        assertThat(positions.positions).isEmpty()
        assertThat(narrations.get(AlongFile)).isNull()
    }

    @Test
    fun `a server that cannot be reached keeps nothing and fails nobody`() = runBlocking<Unit> {
        reader.positionResult = ApiResult.Failure(ApiError.ServerUnreachable)
        audio.playbackStateResult = ApiResult.Failure(ApiError.ServerUnreachable)

        keptPositions().refresh(detail)

        assertThat(positions.positions).isEmpty()
        assertThat(narrations.get(AlongFile)).isNull()
        assertThat(playback.get(TheBook)).isNull()
    }

    @Test
    fun `a store that cannot be written fails nobody`() = runBlocking<Unit> {
        serverText(2.0, minute = 5)
        val broken = object : PositionStore by positions {
            override suspend fun put(position: LocalPosition) = throw IOException("disk")
        }

        KeptPositions(registry, reader, audio, fixture.kept(), places().copy(positions = broken)).refresh(detail)

        assertThat(positions.positions).isEmpty()
    }

    @Test
    fun `a book that is not downloaded is not asked for`() = runBlocking<Unit> {
        registry.update(TheBook) { null }
        serverText(2.0, minute = 5)
        serverAudio(5_000, minute = 5)

        keptPositions().refresh(detail)

        assertThat(reader.calls).isEmpty()
        assertThat(audio.calls).isEmpty()
        assertThat(positions.positions).isEmpty()
    }

    @Test
    fun `nothing is queued for the server, which gave these positions`() = runBlocking<Unit> {
        serverText(2.0, minute = 5, narrationMinute = 6)
        serverAudio(5_000, minute = 5)

        keptPositions().refresh(detail)

        assertThat(outbox.log).isEmpty()
        assertThat(playbackOutbox.pending()).isEmpty()
        assertThat(reader.savedPositions).isEmpty()
        assertThat(audio.writes).isEmpty()
    }

    // --- the narration

    @Test
    fun `the narration place only the server knows is kept on the device`() = runBlocking<Unit> {
        serverText(2.0, minute = 5, narrationMinute = 6)

        keptPositions().refresh(detail)

        assertThat(narrations.get(AlongFile)).isEqualTo(LocalNarration(AlongFile, place, 2.0, at(6)))
    }

    @Test
    fun `with the kept map, the narration is as far as its clock says`() = runBlocking<Unit> {
        val map = fixture.maps.map(TheBook, AlongFile, "along-$AlongFile")!!
        fixture.kept().keepMap(TheBook, AlongFile, "along-$AlongFile")
        serverText(2.0, minute = 5, narrationMinute = 6)

        keptPositions().refresh(detail)

        val expected = map.percentAt(map.timeOfClock(place.seconds))
        assertThat(expected).isGreaterThan(0.0)
        assertThat(narrations.get(AlongFile)?.percent).isEqualTo(expected)
    }

    @Test
    fun `an older narration of the device is replaced, a newer one stays`() = runBlocking<Unit> {
        val other = NarrationPlace(seconds = 400.0, fragment = "other.xhtml#s9", sectionIndex = 4)
        serverText(2.0, minute = 5, narrationMinute = 6)

        narrations.put(LocalNarration(AlongFile, other, 30.0, at(1)))
        keptPositions().refresh(detail)
        assertThat(narrations.get(AlongFile)?.place).isEqualTo(place)

        val newer = LocalNarration(AlongFile, other, 30.0, at(9))
        narrations.put(newer)
        keptPositions().refresh(detail)
        assertThat(narrations.get(AlongFile)).isEqualTo(newer)

        narrations.clear()
        outbox.enqueueNarration(newer)
        keptPositions().refresh(detail)
        assertThat(narrations.get(AlongFile)).isNull()
    }

    @Test
    fun `a narration that never played on the server keeps nothing`() = runBlocking<Unit> {
        serverText(2.0, minute = 5)

        keptPositions().refresh(detail)

        assertThat(narrations.get(AlongFile)).isNull()
    }

    // --- the audiobook

    @Test
    fun `a listening only the server knows is kept as a load with the server would leave it`() = runBlocking<Unit> {
        serverAudio(5_000, minute = 5, version = "v4")

        keptPositions().refresh(detail)

        val kept = playback.get(TheBook)!!
        val bookTime = BookTimeline(manifest).bookTimeOf(second.assetId, 5_000)!!
        assertThat(bookTime).isGreaterThan(5_000L)
        assertThat(kept.copy(operationId = "")).isEqualTo(
            LocalPlayback(TheBook, second.assetId, 5_000, bookTime, at(5), "", "v4", manifest.contentVersion),
        )
        assertThat(playback.knownVersion(TheBook)).isEqualTo("v4")
    }

    @Test
    fun `an older listening of the device is replaced`() = runBlocking<Unit> {
        playback.put(listened(1_000, minute = 1))
        serverAudio(5_000, minute = 5)

        keptPositions().refresh(detail)

        assertThat(playback.get(TheBook)?.assetId).isEqualTo(second.assetId)
        assertThat(playback.knownVersion(TheBook)).isEqualTo("v4")
    }

    @Test
    fun `a newer listening of the device stays, kept or still to send, and its version too`() = runBlocking<Unit> {
        val own = listened(90_000, minute = 9)
        serverAudio(5_000, minute = 5)

        playback.put(own)
        keptPositions().refresh(detail)
        assertThat(playback.get(TheBook)).isEqualTo(own)
        assertThat(playback.knownVersion(TheBook)).isNull()

        playbackFile = "playback_empty"
        playbackOutbox.enqueue(own)
        keptPositions().refresh(detail)
        assertThat(playback.get(TheBook)).isNull()
        assertThat(playback.knownVersion(TheBook)).isNull()
    }

    @Test
    fun `a book never listened to on the server keeps nothing`() = runBlocking<Unit> {
        audio.playbackStateResult = ApiResult.Success(null)

        keptPositions().refresh(detail)

        assertThat(playback.get(TheBook)).isNull()
    }

    @Test
    fun `a listening in a file the kept manifest does not have keeps nothing`() = runBlocking<Unit> {
        audio.playbackStateResult = ApiResult.Success(
            PlaybackState("aud_unknown", 5_000, 1.5, completed = false, at(5), version = "v4", contentVersion = "x"),
        )

        keptPositions().refresh(detail)

        assertThat(playback.get(TheBook)).isNull()
        assertThat(playback.knownVersion(TheBook)).isNull()
    }

    @Test
    fun `without a kept manifest the audiobook is not asked for`() = runBlocking<Unit> {
        fixture.kept().remove(TheBook, DownloadFormat.Audiobook)
        serverAudio(5_000, minute = 5)

        keptPositions().refresh(detail)

        assertThat(audio.calls).isEmpty()
        assertThat(playback.get(TheBook)).isNull()
    }

    // --- what opens and what the page says, without the server

    @Test
    fun `the reader then opens there without the server`() = runBlocking<Unit> {
        serverText(2.0, minute = 5)
        keptPositions().refresh(detail)
        reader.positionResult = ApiResult.Failure(ApiError.ServerUnreachable)
        val wall = Clock.fixed(at(30), ZoneOffset.UTC)

        val restored = PositionSync(AlongFile, this, reader, positions, outbox, kick = {}, wall = wall).restore()

        assertThat(restored).isEqualTo(RestoredPosition(Cfi, 2.0))
        assertThat(outbox.log).isEmpty()
    }

    @Test
    fun `the page then says the device's position`() = runBlocking<Unit> {
        serverText(2.4, minute = 5)
        keptPositions().refresh(detail)
        val progress = LocalProgress(positions, outbox, narrations, playback, playbackOutbox, fixture.kept())

        assertThat(progress.percentOf(book)).isEqualTo(2)
    }

    @Test
    fun `the page says the listening when it is the latest`() = runBlocking<Unit> {
        serverText(2.4, minute = 5)
        serverAudio(5_000, minute = 7)
        keptPositions().refresh(detail)
        val progress = LocalProgress(positions, outbox, narrations, playback, playbackOutbox, fixture.kept())
        val bookTime = BookTimeline(manifest).bookTimeOf(second.assetId, 5_000)!!

        assertThat(progress.percentOf(book))
            .isEqualTo(Math.round(bookTime * 100.0 / manifest.totalDurationMs).toInt())
    }
}
