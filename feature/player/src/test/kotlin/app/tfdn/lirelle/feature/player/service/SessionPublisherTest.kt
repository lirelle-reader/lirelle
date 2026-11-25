package app.tfdn.lirelle.feature.player.service

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.audio.settings.PlayerSettings
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.audio.timer.SleepChoice
import app.tfdn.lirelle.core.audio.timer.SleepState
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class SessionPublisherTest {

    private val engine = FakeEngine()
    private val navigator = BookNavigator(engine)
    private val chapters = ChapterPlayer(engine, navigator, object : ChapterPlayer.Actions {
        override fun play() = Unit
        override fun forward() = Unit
        override fun back() = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun resume(bookId: BookId, play: () -> Boolean, done: () -> Unit) = Unit
    })
    private val extras = mutableListOf<Bundle>()
    private val buttons = mutableListOf<List<CommandButton>>()
    private val publisher = SessionPublisher(
        PlayerTexts(ApplicationProvider.getApplicationContext<android.content.Context>().resources),
        chapters, navigator, { extras += it }, { buttons += it },
    )
    private val book = SessionBook(BookId("42"), "The Long Road", emptyList(), emptyList(), null, 160_000)

    private fun sleeping(remainingMs: Long) =
        PlayerSessionExtras(book = book, sleep = SleepState(SleepChoice.Minutes(30), remainingMs, false))

    @Test
    fun `the sleep timer's time left goes out once a second, not at every tick`() {
        publisher.publish(sleeping(61_400))
        publisher.publish(sleeping(60_900))
        publisher.publish(sleeping(60_400))

        assertThat(extras.map { PlayerSessionExtras.from(it).sleep!!.remainingMs }).containsExactly(62_000L, 61_000L)
    }

    @Test
    fun `the buttons change with the intervals and at the end of the book only`() {
        publisher.publish(PlayerSessionExtras(book = book))
        publisher.publish(sleeping(10_000))
        publisher.settings = PlayerSettings(backSeconds = 10)
        publisher.publish(PlayerSessionExtras(book = book, finished = FinishedBook(null)))

        assertThat(buttons.map { list -> list.map { it.displayName.toString() } }).containsExactly(
            listOf("Previous chapter", "Back 15 seconds", "Forward 30 seconds", "Next chapter", "Speed: 1×"),
            listOf("Previous chapter", "Back 10 seconds", "Forward 30 seconds", "Next chapter", "Speed: 1×"),
            emptyList<String>(),
        ).inOrder()
    }

    @Test
    fun `the notification shows the time left after the current chapter's title`() {
        navigator.open(BookPlaylist.of(Manifests.twoFiles(), null, { "Part $it" }) { "https://b/$it" }!!, 70_000)
        engine.engineState = Player.STATE_READY
        engine.update()
        shadowOf(Looper.getMainLooper()).idle()

        publisher.publish(sleeping(5 * 60_000))
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(chapters.mediaMetadata.artist.toString()).isEqualTo("Middle · Stops in 5 min")
    }

    @Test
    fun `a narration's section without a title is named by its number in the book`() {
        val book = narrationOf(ReadAlongMaps.inventedMap())
        navigator.open(book.playlist, 0)
        engine.engineState = Player.STATE_READY
        engine.update()
        shadowOf(Looper.getMainLooper()).idle()
        val narrating = PlayerSessionExtras(book = this.book, narrationFileId = FileId("1"))

        publisher.publish(narrating)
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(chapters.mediaMetadata.artist.toString()).isEqualTo("Opening")

        navigator.seekBookTime(16_000)
        publisher.refresh()
        shadowOf(Looper.getMainLooper()).idle()
        // Section 3 has no label: the fourth file of the book's reading order.
        assertThat(chapters.mediaMetadata.artist.toString()).isEqualTo("Section 4")
        assertThat(chapters.mediaMetadata.title.toString()).isEqualTo("The Long Road")
    }

    @Test
    fun `at a narration's end the notification says so and offers no buttons`() {
        val book = narrationOf(ReadAlongMaps.inventedMap())
        navigator.open(book.playlist, 0)
        engine.engineState = Player.STATE_READY
        engine.update()
        shadowOf(Looper.getMainLooper()).idle()

        publisher.publish(
            PlayerSessionExtras(book = this.book, narrationFileId = FileId("1"), narrationFinished = true),
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(chapters.mediaMetadata.artist.toString()).isEqualTo("Narration finished")
        assertThat(buttons.last()).isEmpty()
        assertThat(PlayerSessionExtras.from(extras.last()).narrationFinished).isTrue()
    }

    private fun List<List<CommandButton>>.names() = map { list -> list.map { it.displayName.toString() } }

    @Test
    fun `the buttons follow the speed, and nothing is sent while it stays`() {
        val chapterButtons = listOf("Previous chapter", "Back 15 seconds", "Forward 30 seconds", "Next chapter")
        publisher.publish(PlayerSessionExtras(book = book))
        publisher.speed = 1.5f
        publisher.speed = 1.5f
        publisher.publish(sleeping(10_000))

        assertThat(buttons.names()).containsExactly(
            chapterButtons + "Speed: 1×",
            chapterButtons + "Speed: 1.5×",
        ).inOrder()
        // What a controller that connects now is given.
        assertThat(listOf(publisher.buttons()).names()).containsExactly(chapterButtons + "Speed: 1.5×")
    }
}
