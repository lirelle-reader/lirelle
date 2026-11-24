package app.tfdn.lirelle.feature.player.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.ids.BookId
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackButtonReceiverTest {

    private val mark = newLastBookMark()
    private val store = lastBookStore(mark = mark)

    @Test
    fun `a play button starts the service only when there is a last book to play`() = runTest {
        assertThat(PlaybackButtonReceiver.shouldStart(mark)).isFalse()

        store.put(LastBook(BookId("42"), "The Long Road", null))
        assertThat(PlaybackButtonReceiver.shouldStart(mark)).isTrue()

        store.clear()
        assertThat(PlaybackButtonReceiver.shouldStart(mark)).isFalse()
    }

    @Test
    fun `with no profile open, a play button starts nothing`() {
        assertThat(PlaybackButtonReceiver.shouldStart(null)).isFalse()
    }

    /** The process may have been started for the button: the answer waits for nothing to be read. */
    @Test
    fun `a profile that never kept a book starts nothing`() {
        assertThat(PlaybackButtonReceiver.shouldStart(newLastBookMark())).isFalse()
    }

    @Test
    fun `only the receiver's own starts are play button starts, not Media3's notification buttons`() {
        val mediaButton = android.content.Intent(android.content.Intent.ACTION_MEDIA_BUTTON)

        assertThat(PlaybackButtonReceiver.isPlayButtonStart(mediaButton)).isFalse()
        assertThat(PlaybackButtonReceiver.isPlayButtonStart(PlaybackButtonReceiver.markPlayButtonStart(mediaButton)))
            .isTrue()
    }
}
