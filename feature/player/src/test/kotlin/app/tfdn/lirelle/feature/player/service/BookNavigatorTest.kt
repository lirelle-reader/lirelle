package app.tfdn.lirelle.feature.player.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookNavigatorTest {

    private val engine = FakeEngine()
    private val navigator = BookNavigator(engine)
    private val playlist = BookPlaylist.of(Manifests.twoFiles(), null, { "Part $it" }) { "https://b/$it" }!!

    private fun at(index: Int, positionMs: Long) {
        engine.index = index
        engine.positionMs = positionMs
        engine.update()
    }

    @Test
    fun `a book opens at a book time, in the file that holds it`() {
        navigator.open(playlist, 130_000)

        assertThat(engine.items).isEqualTo(playlist.items)
        assertThat(engine.index).isEqualTo(1)
        assertThat(engine.positionMs).isEqualTo(30_000)
        assertThat(navigator.bookTimeMs()).isEqualTo(130_000)
        assertThat(navigator.chapterIndex()).isEqualTo(2)
        assertThat(navigator.positionInChapter()).isEqualTo(30_000)
    }

    @Test
    fun `skips cross files and stay within the book`() {
        navigator.open(playlist, 0)
        at(0, 90_000)

        navigator.skip(30_000)
        assertThat(engine.index to engine.positionMs).isEqualTo(1 to 20_000L)

        navigator.skip(-45_000)
        assertThat(engine.index to engine.positionMs).isEqualTo(0 to 75_000L)

        navigator.skip(-500_000)
        assertThat(engine.index to engine.positionMs).isEqualTo(0 to 0L)
    }

    @Test
    fun `previous chapter goes back to the start of the chapter, or to the previous one in its first seconds`() {
        navigator.open(playlist, 0)
        at(0, 75_000)
        navigator.previousChapter()
        assertThat(navigator.bookTimeMs()).isEqualTo(60_000)

        at(0, 61_000)
        navigator.previousChapter()
        assertThat(navigator.bookTimeMs()).isEqualTo(0)
    }

    @Test
    fun `next chapter goes to the start of the next one, and nowhere from the last`() {
        navigator.open(playlist, 70_000)
        navigator.nextChapter()
        assertThat(engine.index to engine.positionMs).isEqualTo(1 to 0L)

        at(1, 10_000)
        navigator.nextChapter()
        assertThat(engine.index to engine.positionMs).isEqualTo(1 to 10_000L)
    }

    @Test
    fun `a place in a chapter is a place in the book`() {
        navigator.open(playlist, 0)

        navigator.seekToChapter(1, 15_000)
        assertThat(navigator.bookTimeMs()).isEqualTo(75_000)

        navigator.seekToChapter(2, 999_000)
        assertThat(navigator.bookTimeMs()).isEqualTo(160_000)
    }

    @Test
    fun `a cleared navigator knows no book time`() {
        navigator.open(playlist, 10_000)
        navigator.clear()

        assertThat(navigator.playlist).isNull()
        assertThat(navigator.bookTimeMs()).isNull()
        assertThat(engine.items).isEmpty()
    }
}
