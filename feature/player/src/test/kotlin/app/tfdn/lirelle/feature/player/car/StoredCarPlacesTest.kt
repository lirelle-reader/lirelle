package app.tfdn.lirelle.feature.player.car

import app.tfdn.lirelle.core.audio.position.LocalPlayback
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.NarrationPlace
import app.tfdn.lirelle.core.library.model.PlaybackState
import app.tfdn.lirelle.core.library.model.ReadingPosition
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.reader.sync.LocalNarration
import app.tfdn.lirelle.core.reader.sync.LocalPosition
import app.tfdn.lirelle.core.testing.FakeAudioRepository
import app.tfdn.lirelle.core.testing.FakeReaderRepository
import app.tfdn.lirelle.core.testing.InMemoryOutbox
import app.tfdn.lirelle.core.testing.InMemoryPositionStore
import app.tfdn.lirelle.feature.player.service.InMemoryNarrationStore
import app.tfdn.lirelle.feature.player.service.InMemoryPlaybackStore
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Test

private val Book = BookId("42")
private val File = FileId("1")

/** Where a book stands, read from the device's stores and from the server, newest first. */
class StoredCarPlacesTest {

    private val audio = FakeAudioRepository()
    private val playback = InMemoryPlaybackStore()
    private val reader = FakeReaderRepository()
    private val narrations = InMemoryNarrationStore()
    private val positions = InMemoryPositionStore()
    private val outbox = InMemoryOutbox()
    private val places = StoredCarPlaces(audio, playback, reader, narrations, positions, outbox)

    private fun at(time: String) = Instant.parse("2026-11-25T$time:00Z")

    private suspend fun listenedOnDevice(time: String) =
        playback.put(LocalPlayback(Book, "a", 0, 0, at(time), contentVersion = "v1"))

    private fun listenedOnServer(time: String?, completed: Boolean = false) {
        audio.playbackStateResult =
            ApiResult.Success(PlaybackState("a", 0, 50.0, completed, time?.let(::at), "7", "v1"))
    }

    private fun server(cfi: String?, readAt: String?, heardAt: String? = null) {
        reader.positionResult = ApiResult.Success(
            ReadingPosition(
                File, cfi, 10.0, readAt?.let(::at),
                narration = heardAt?.let { NarrationPlace(0.0, "text/one.xhtml#s1", 1) },
                narrationUpdatedAt = heardAt?.let(::at),
            ),
        )
    }

    @Test
    fun `an audiobook never listened to, or only begun, is not finished`() = runTest {
        assertThat(places.audiobook(Book)).isEqualTo(AudiobookStanding(finished = false))

        listenedOnDevice("10:00")
        listenedOnServer("11:00")
        assertThat(places.audiobook(Book).finished).isFalse()
    }

    @Test
    fun `an audiobook is finished when the server says so, unless this device listened to it since`() = runTest {
        listenedOnServer("11:00", completed = true)
        assertThat(places.audiobook(Book).finished).isTrue()

        listenedOnDevice("11:00")
        assertThat(places.audiobook(Book).finished).isTrue()

        listenedOnDevice("12:00")
        assertThat(places.audiobook(Book).finished).isFalse()
    }

    @Test
    fun `a narration's date is the newest of the device's place and the server's`() = runTest {
        assertThat(places.narration(File)).isEqualTo(NarrationStanding(heardAt = null, marker = null))

        narrations.put(LocalNarration(File, NarrationPlace(0.0, "text/one.xhtml#s1", 1), 5.0, at("10:00")))
        assertThat(places.narration(File).heardAt).isEqualTo(at("10:00"))

        server(cfi = null, readAt = null, heardAt = "11:00")
        assertThat(places.narration(File).heardAt).isEqualTo(at("11:00"))
    }

    @Test
    fun `the page marker is the newest of the device's, sent or not, and the server's`() = runTest {
        positions.put(LocalPosition(File, "epubcfi(/6/4!/4/2)", 10.0, at("10:00")))
        assertThat(places.narration(File).marker).isEqualTo(PageMarker("epubcfi(/6/4!/4/2)", at("10:00")))

        outbox.enqueuePosition(LocalPosition(File, "epubcfi(/6/4!/4/4)", 11.0, at("11:00")))
        assertThat(places.narration(File).marker).isEqualTo(PageMarker("epubcfi(/6/4!/4/4)", at("11:00")))

        server(cfi = "epubcfi(/6/8!/4/2)", readAt = "12:00")
        assertThat(places.narration(File).marker).isEqualTo(PageMarker("epubcfi(/6/8!/4/2)", at("12:00")))

        // A server's marker without a date is never the newer one.
        server(cfi = "epubcfi(/6/8!/4/2)", readAt = null)
        assertThat(places.narration(File).marker).isEqualTo(PageMarker("epubcfi(/6/4!/4/4)", at("11:00")))
    }
}
