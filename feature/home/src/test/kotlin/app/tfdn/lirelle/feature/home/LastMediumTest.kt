package app.tfdn.lirelle.feature.home

import app.tfdn.lirelle.core.library.model.MediaProfile
import app.tfdn.lirelle.core.testing.sampleBook
import app.tfdn.lirelle.core.ui.BookMedium
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.Instant
import org.junit.Test

class LastMediumTest {

    private val readAt = Instant.parse("2026-11-13T08:00:00Z")
    private val both = sampleBook("1", media = MediaProfile.TextAndAudio).copy(lastActivityAt = readAt)

    private fun heard(at: Instant?) = BookListening(timeLeftSeconds = 600, listenedAt = at)

    @Test
    fun `a book with one medium went on with it`() {
        assertThat(sampleBook("1", media = MediaProfile.TextOnly).lastMedium(null)).isEqualTo(BookMedium.Read)
        assertThat(sampleBook("1", media = MediaProfile.AudioOnly).lastMedium(null)).isEqualTo(BookMedium.Listen)
    }

    @Test
    fun `a book with both is not said while its listening is not known`() {
        assertThat(both.lastMedium(null)).isNull()
    }

    @Test
    fun `a book with both never listened to was read`() {
        assertThat(both.lastMedium(heard(null))).isEqualTo(BookMedium.Read)
    }

    @Test
    fun `the more recent of the reading and the listening wins`() {
        assertThat(both.lastMedium(heard(readAt.plus(Duration.ofHours(1))))).isEqualTo(BookMedium.Listen)
        assertThat(both.lastMedium(heard(readAt.minus(Duration.ofHours(1))))).isEqualTo(BookMedium.Read)
    }

    @Test
    fun `a reading dated a moment after the listening is that listening, written by the server`() {
        assertThat(both.lastMedium(heard(readAt.minus(Duration.ofSeconds(30))))).isEqualTo(BookMedium.Listen)
    }

    @Test
    fun `a read-along book with audio files never read was listened to`() {
        val readAlong = sampleBook("1", media = MediaProfile.ReadAlong).copy(hasAudioFiles = true)
        assertThat(readAlong.lastMedium(heard(readAt))).isEqualTo(BookMedium.Listen)
    }

    @Test
    fun `a read-along book without an audio file is read`() {
        assertThat(sampleBook("1", media = MediaProfile.ReadAlong).lastMedium(null)).isEqualTo(BookMedium.Read)
    }
}
