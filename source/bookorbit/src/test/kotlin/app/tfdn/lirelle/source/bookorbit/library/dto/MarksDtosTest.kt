package app.tfdn.lirelle.source.bookorbit.library.dto

import app.tfdn.lirelle.source.bookorbit.library.testing.Fixtures
import app.tfdn.lirelle.source.bookorbit.network.ApiJson
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MarksDtosTest {

    private inline fun <reified T> recorded(name: String): T = ApiJson.decodeFromString(Fixtures.recorded(name))

    private inline fun <reified T> handmade(name: String): T =
        ApiJson.decodeFromString(Fixtures.read("handmade/$name.json"))

    @Test
    fun `an empty list is empty`() {
        assertThat(recorded<List<BookmarkDto>>("marks-bookmarks-empty")).isEmpty()
        assertThat(recorded<List<HighlightDto>>("marks-highlights-empty")).isEmpty()
    }

    @Test
    fun `the recorded lists decode, whatever keys the recording lacks`() {
        assertThat(recorded<List<BookmarkDto>>("marks-bookmarks")).isNotEmpty()
        assertThat(recorded<List<HighlightDto>>("marks-highlights")).isNotEmpty()
        assertThat(recorded<HighlightDto>("marks-highlight-changed").note).isEqualTo("Note 2")
        assertThat(recorded<HighlightDto>("marks-highlight-note-cleared").note).isNull()
        assertThat(recorded<BookmarkDto>("marks-bookmark-added").cfi).isEqualTo("epubcfi(/6/8!/4/2/1:0)")
    }

    @Test
    fun `a restored hub item reads as a highlight`() {
        assertThat(recorded<HighlightDto>("marks-restored").id).isEqualTo(3)
    }

    @Test
    fun `device highlights decode with their odd values`() {
        val items = handmade<List<HighlightDto>>("marks-device-highlights")

        assertThat(items[0].cfi).isNull()
        assertThat(items[0].positionStatus).isEqualTo("failed")
        assertThat(items[2].color).isEqualTo("yellow")
        assertThat(items[2].origin).isEqualTo("something-new")
        assertThat(items[2].style).isEqualTo("invert")
    }

    @Test
    fun `device bookmarks decode, with a note and without a place`() {
        val items = handmade<List<BookmarkDto>>("marks-device-bookmarks")

        assertThat(items[0].note).startsWith("Kobo note")
        assertThat(items[1].cfi).isNull()
    }
}
