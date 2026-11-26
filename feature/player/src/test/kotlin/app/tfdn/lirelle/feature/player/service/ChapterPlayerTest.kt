package app.tfdn.lirelle.feature.player.service

import androidx.media3.common.C
import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.audio.narration.toManifest
import app.tfdn.lirelle.core.audio.narration.trackOf
import app.tfdn.lirelle.core.ids.BookId
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class ChapterPlayerTest {

    private val engine = FakeEngine()
    private val navigator = BookNavigator(engine)
    private val asked = mutableListOf<String>()
    private val actions = object : ChapterPlayer.Actions {
        override fun play() { asked += "play" }
        override fun forward() { asked += "forward" }
        override fun back() { asked += "back" }
        override fun setSpeed(speed: Float) { asked += "speed $speed" }
        override fun resume(bookId: BookId, play: () -> Boolean, done: () -> Unit) {
            asked += "resume $bookId"
            resumed = done
            playsOnceLoaded = play
        }
    }
    private var playsOnceLoaded: (() -> Boolean)? = null
    private var resumed: (() -> Unit)? = null
    private val player = ChapterPlayer(
        engine,
        navigator,
        actions,
        starts = { bookId, play, done ->
            asked += "start $bookId"
            resumed = done
            playsOnceLoaded = play
        },
    )
    private val playlist =
        BookPlaylist.of(Manifests.twoFiles(), "https://b/cover", { "Part $it" }) { "https://b/$it" }!!

    private fun load(startMs: Long = 0) {
        navigator.open(playlist, startMs)
        engine.engineState = Player.STATE_READY
        engine.durationsMs = listOf(100_000, 60_000)
        engine.update()
        player.refresh()
        idle()
    }

    private fun at(index: Int, positionMs: Long) {
        engine.index = index
        engine.positionMs = positionMs
        engine.update()
        player.refresh()
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `the session sees one item per chapter, placed in the chapter`() {
        load(startMs = 75_000)

        assertThat(player.mediaItemCount).isEqualTo(3)
        assertThat(player.currentMediaItemIndex).isEqualTo(1)
        assertThat(player.currentPosition).isEqualTo(15_000)
        assertThat(player.duration).isEqualTo(40_000)
        val metadata = player.mediaMetadata
        assertThat(metadata.title.toString()).isEqualTo("The Long Road")
        assertThat(metadata.subtitle.toString()).isEqualTo("Middle")
        assertThat(metadata.artist.toString()).isEqualTo("Middle")
        assertThat(metadata.artworkUri).isEqualTo(Uri.parse("https://b/cover"))
        assertThat(PlayerSessionContract.chapterStartMs(metadata)).isEqualTo(60_000)
        assertThat(metadata.extras!!.getLong(PlayerSessionContract.EXTRA_CHAPTER_END_MS)).isEqualTo(100_000)
        assertThat(metadata.extras!!.getString(PlayerSessionContract.EXTRA_CHAPTER_ID)).isEqualTo("c2")
    }

    @Test
    fun `the cover's address is said as the service tells, for every chapter`() {
        val said = Uri.parse("content://app.tfdn.lirelle.covers/profile-1/b1")
        val told = mutableListOf<BookPlaylist>()
        val player = ChapterPlayer(engine, navigator, actions, artwork = { said.also { _ -> told += it } })
        navigator.open(playlist, 0)
        engine.engineState = Player.STATE_READY
        engine.durationsMs = listOf(100_000, 60_000)
        engine.update()
        player.refresh()
        idle()

        val covers = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaMetadata.artworkUri }
        assertThat(covers.distinct()).containsExactly(said)
        assertThat(player.mediaMetadata.artworkUri).isEqualTo(said)
        assertThat(told.distinct()).containsExactly(playlist)
    }

    @Test
    fun `a narration's chapters tell the session their section's place in the book, an audiobook's do not`() {
        load()
        val audiobook = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaMetadata.extras!! }
        val map = ReadAlongMaps.inventedMap()
        val narration = BookPlaylist.ofSegments(
            manifest = map.toManifest("Narrated", emptyList(), emptyList(), hasCover = false),
            coverUrl = null,
            clip = { asset -> map.trackOf(asset.assetId)?.let { ClipRange(it.clipStartMs, it.clipEndMs) } },
        ) { "https://b/$it" }!!
        navigator.open(narration, 0)
        engine.durationsMs = listOf(15_000, 10_000)
        engine.update()
        player.refresh()
        idle()

        val sections = (0 until player.mediaItemCount).map {
            player.getMediaItemAt(it).mediaMetadata.extras!!.getInt(PlayerSessionContract.EXTRA_CHAPTER_SECTION_INDEX)
        }
        assertThat(sections).containsExactly(1, 3).inOrder()
        assertThat(audiobook.map { it.containsKey(PlayerSessionContract.EXTRA_CHAPTER_SECTION_INDEX) }.toSet())
            .containsExactly(false)
    }

    @Test
    fun `the chapter follows playback across files`() {
        load()
        at(1, 20_000)

        assertThat(player.currentMediaItemIndex).isEqualTo(2)
        assertThat(player.currentPosition).isEqualTo(20_000)
        assertThat(player.duration).isEqualTo(60_000)
    }

    @Test
    fun `seeking in the chapter moves the engine in the book`() {
        load(startMs = 75_000)

        player.seekTo(30_000)
        idle()

        assertThat(navigator.bookTimeMs()).isEqualTo(90_000)
    }

    @Test
    fun `next and previous media items are the chapters`() {
        load(startMs = 75_000)

        player.seekToNextMediaItem()
        idle()
        assertThat(navigator.bookTimeMs()).isEqualTo(100_000)

        player.seekToPreviousMediaItem()
        idle()
        assertThat(navigator.bookTimeMs()).isEqualTo(60_000)
    }

    @Test
    fun `next and previous from headsets, cars and watches skip forward and back, even on the last chapter`() {
        load(startMs = 75_000)
        player.seekToNext()
        player.seekToPrevious()
        player.seekForward()
        player.seekBack()
        at(1, 50_000)
        player.seekToNext()
        idle()

        assertThat(asked).containsExactly("forward", "back", "forward", "back", "forward").inOrder()
    }

    @Test
    fun `play and speed go through the service's actions, pause straight to the engine`() {
        load()
        engine.wantsToPlay = true
        engine.update()
        idle()

        player.pause()
        player.play()
        player.setPlaybackSpeed(1.5f)
        idle()

        assertThat(engine.wantsToPlay).isFalse()
        assertThat(asked).containsExactly("play", "speed 1.5").inOrder()
    }

    @Test
    fun `the current chapter can show another title and text`() {
        load(startMs = 75_000)

        player.override = ChapterPlayer.Override(title = "The Long Road — finished", artist = "Middle · 23 min")
        idle()

        assertThat(player.mediaMetadata.title.toString()).isEqualTo("The Long Road — finished")
        assertThat(player.mediaMetadata.artist.toString()).isEqualTo("Middle · 23 min")
        val other = player.currentTimeline.getWindow(0, Timeline.Window()).mediaItem.mediaMetadata
        assertThat(other.artist.toString()).isEqualTo("Opening")
    }

    @Test
    fun `without a book there is nothing to seek, and play, which asks for the last book, does nothing itself`() {
        assertThat(player.currentTimeline.isEmpty).isTrue()
        assertThat(player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)).isTrue()
        assertThat(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)).isFalse()
    }

    @Test
    fun `with a book, skips are always offered, item changes never, only the last book can be set`() {
        load()

        assertThat(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)).isTrue()
        assertThat(player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS)).isTrue()
        assertThat(player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)).isTrue()
        assertThat(player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)).isFalse()
        assertThat(player.isCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM)).isTrue()
    }

    @Test
    fun `another book is another item for the session, even at the same chapter`() {
        load()
        val transitions = mutableListOf<Int>()
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                transitions += reason
            }
        })
        val otherManifest = Manifests.twoFiles().copy(bookId = BookId("43"))
        val other = BookPlaylist.of(otherManifest, null, { "" }) { "https://b/$it" }!!

        navigator.open(other, 0)
        engine.update()
        player.refresh()
        idle()

        assertThat(transitions).containsExactly(Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
    }

    @Test
    fun `the skip increments are the settings'`() {
        load()

        player.skips = ChapterPlayer.Skips(backMs = 10_000, forwardMs = 45_000)
        idle()

        assertThat(player.seekBackIncrement).isEqualTo(10_000)
        assertThat(player.seekForwardIncrement).isEqualTo(45_000)
    }

    @Test
    fun `at the end of the book, play does not start the last chapter again, and there is no previous or next`() {
        load()
        at(1, 60_000)
        engine.engineState = Player.STATE_ENDED
        engine.update()
        idle()

        player.seekToDefaultPosition()
        player.play()
        idle()

        assertThat(navigator.bookTimeMs()).isEqualTo(160_000)
        assertThat(asked).isEmpty()
        assertThat(player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)).isFalse()
        assertThat(player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS)).isFalse()
        assertThat(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)).isFalse()
        assertThat(player.isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM)).isTrue()
    }

    @Test
    fun `at the end of a narration, play is offered and asks the service, which starts it over`() {
        val map = ReadAlongMaps.inventedMap()
        val narration = BookPlaylist.ofSegments(
            manifest = map.toManifest("Narrated", emptyList(), emptyList(), hasCover = false),
            coverUrl = null,
            clip = { asset -> map.trackOf(asset.assetId)?.let { ClipRange(it.clipStartMs, it.clipEndMs) } },
        ) { "https://b/$it" }!!
        navigator.open(narration, 0)
        engine.durationsMs = listOf(15_000, 10_000)
        engine.index = 1
        engine.positionMs = 10_000
        engine.engineState = Player.STATE_ENDED
        engine.update()
        player.refresh()
        idle()

        player.seekToDefaultPosition()
        player.play()
        idle()

        assertThat(player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)).isTrue()
        assertThat(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)).isFalse()
        assertThat(asked).containsExactly("play")
    }

    @Test
    fun `a whole-book chapter of unknown length has no duration`() {
        val unknown = Manifests.twoFiles(chapters = false).let { m ->
            m.copy(assets = m.assets.map { it.copy(durationMs = null) })
        }
        navigator.open(BookPlaylist.of(unknown, null, { "" }) { "https://b/$it" }!!, 0)
        engine.engineState = Player.STATE_READY
        engine.update()
        player.refresh()
        idle()

        assertThat(player.duration).isEqualTo(C.TIME_UNSET)
    }

    @Test
    fun `a play button after the service died sets the last book, which the service loads`() {
        val item = PlayerSessionContract.resumeItem(LastBook(BookId("42"), "The Long Road", null))

        player.setMediaItem(item)
        idle()
        assertThat(asked).containsExactly("resume 42")
        // Meanwhile the session shows the book it is loading, as playing: the notification is posted at once.
        assertThat(player.currentMediaItem?.mediaMetadata?.title.toString()).isEqualTo("The Long Road")
        assertThat(player.playbackState).isEqualTo(Player.STATE_BUFFERING)
        assertThat(player.playWhenReady).isTrue()

        load()
        resumed!!.invoke()
        idle()
        assertThat(player.mediaItemCount).isEqualTo(3)
    }

    @Test
    fun `a last book that cannot be loaded leaves an empty player`() {
        player.setMediaItem(PlayerSessionContract.resumeItem(LastBook(BookId("42"), "The Long Road", null)))
        idle()

        resumed!!.invoke()
        idle()

        assertThat(player.currentTimeline.isEmpty).isTrue()
        assertThat(player.playbackState).isAnyOf(Player.STATE_IDLE, Player.STATE_ENDED)
        assertThat(player.playWhenReady).isFalse()
    }

    @Test
    fun `pause while the last book loads is kept once it is loaded`() {
        player.setMediaItem(PlayerSessionContract.resumeItem(LastBook(BookId("42"), "The Long Road", null)))
        idle()
        assertThat(player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)).isTrue()

        player.pause()
        idle()
        assertThat(player.playWhenReady).isFalse()
        // The load is told not to start the engine at all.
        assertThat(playsOnceLoaded!!.invoke()).isFalse()

        player.play()
        idle()
        assertThat(playsOnceLoaded!!.invoke()).isTrue()
    }

    @Test
    fun `a book the car starts shows as starting, and is loaded by the car's start, not as a last book`() {
        val item = PlayerSessionContract.startItem(BookId("7"), "The Long Road")

        player.setMediaItem(item)
        idle()
        assertThat(asked).containsExactly("start 7")
        assertThat(player.currentMediaItem?.mediaMetadata?.title.toString()).isEqualTo("The Long Road")
        assertThat(player.playbackState).isEqualTo(Player.STATE_BUFFERING)
        assertThat(player.playWhenReady).isTrue()
        assertThat(playsOnceLoaded!!.invoke()).isTrue()

        load()
        resumed!!.invoke()
        idle()
        assertThat(player.mediaItemCount).isEqualTo(3)
    }

    @Test
    fun `a book the car starts that cannot be loaded leaves the player as it was`() {
        player.setMediaItem(PlayerSessionContract.startItem(BookId("7"), null))
        idle()

        resumed!!.invoke()
        idle()

        assertThat(player.currentTimeline.isEmpty).isTrue()
        assertThat(player.playWhenReady).isFalse()
    }

    @Test
    fun `a second book asked while the first loads keeps its placeholder and its pause when the first ends`() {
        player.setMediaItem(PlayerSessionContract.startItem(BookId("7"), "The Long Road"))
        idle()
        val firstDone = resumed!!
        player.setMediaItem(PlayerSessionContract.startItem(BookId("8"), "The Wide River"))
        idle()
        player.pause()
        idle()

        firstDone()
        idle()

        assertThat(asked).containsExactly("start 7", "start 8").inOrder()
        assertThat(player.currentMediaItem?.mediaMetadata?.title.toString()).isEqualTo("The Wide River")
        assertThat(player.playbackState).isEqualTo(Player.STATE_BUFFERING)
        assertThat(playsOnceLoaded!!.invoke()).isFalse()
    }

    @Test
    fun `any other item is still set to nothing`() {
        player.setMediaItem(MediaItem.Builder().setMediaId("book/7").build())
        player.setMediaItem(MediaItem.Builder().setMediaId("elsewhere").build())
        idle()

        assertThat(asked).isEmpty()
        assertThat(player.currentTimeline.isEmpty).isTrue()
    }
}
