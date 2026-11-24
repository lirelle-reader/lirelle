package app.tfdn.lirelle.core.library.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** What a book offers, from its files as the source describes them; which files are content is the source's call. */
class MediaProfileTest {

    private fun file(format: String?, content: Boolean = true, overlay: Boolean = false) =
        MediaFile(format = format, isContent = content, hasMediaOverlay = overlay)

    @Test
    fun `an epub alone is text only`() {
        assertThat(mediaProfileOf(listOf(file("epub")))).isEqualTo(MediaProfile.TextOnly)
    }

    @Test
    fun `pdf, comics and unknown formats are text`() {
        listOf("pdf", "cbz", "cbr", "cb7", "mobi", "azw3", "fb2", "djvu", "kepub", "something-new", null).forEach {
            assertThat(mediaProfileOf(listOf(file(it)))).isEqualTo(MediaProfile.TextOnly)
        }
    }

    @Test
    fun `audio alone is audio only, in one or several files`() {
        assertThat(mediaProfileOf(listOf(file("m4b")))).isEqualTo(MediaProfile.AudioOnly)
        assertThat(mediaProfileOf(listOf(file("mp3"), file("mp3"), file("mp3")))).isEqualTo(MediaProfile.AudioOnly)
        listOf("m4a", "opus", "ogg", "flac").forEach {
            assertThat(mediaProfileOf(listOf(file(it)))).isEqualTo(MediaProfile.AudioOnly)
        }
    }

    @Test
    fun `text and audio that are not synchronised`() {
        assertThat(mediaProfileOf(listOf(file("epub"), file("m4b")))).isEqualTo(MediaProfile.TextAndAudio)
        assertThat(mediaProfileOf(listOf(file("pdf"), file("mp3")))).isEqualTo(MediaProfile.TextAndAudio)
    }

    @Test
    fun `an epub with a media overlay is read-along, whatever else there is`() {
        assertThat(mediaProfileOf(listOf(file("epub", overlay = true)))).isEqualTo(MediaProfile.ReadAlong)
        assertThat(mediaProfileOf(listOf(file("epub"), file("epub", overlay = true), file("m4b"))))
            .isEqualTo(MediaProfile.ReadAlong)
    }

    @Test
    fun `an overlay on something that is not an epub does not count`() {
        assertThat(mediaProfileOf(listOf(file("pdf", overlay = true)))).isEqualTo(MediaProfile.TextOnly)
    }

    @Test
    fun `format names are compared without case`() {
        assertThat(mediaProfileOf(listOf(file("M4B")))).isEqualTo(MediaProfile.AudioOnly)
        assertThat(mediaProfileOf(listOf(file("EPUB", overlay = true)))).isEqualTo(MediaProfile.ReadAlong)
    }

    @Test
    fun `only content files count`() {
        assertThat(mediaProfileOf(listOf(file("epub"), file("mp3", content = false)))).isEqualTo(MediaProfile.TextOnly)
        assertThat(mediaProfileOf(listOf(file("epub", content = false, overlay = true), file("m4b"))))
            .isEqualTo(MediaProfile.AudioOnly)
    }

    @Test
    fun `no file at all is text only`() {
        assertThat(mediaProfileOf(emptyList())).isEqualTo(MediaProfile.TextOnly)
    }
}
