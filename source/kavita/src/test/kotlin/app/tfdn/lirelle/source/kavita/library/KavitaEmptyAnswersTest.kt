package app.tfdn.lirelle.source.kavita.library

import app.tfdn.lirelle.core.downloads.model.FileSource
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.source.kavita.network.KavitaAccountPictures
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

private val Book = BookId("12")
private val File = FileId("34")
private val NotFound = ApiResult.Failure(ApiError.NotFound)
private val Nothing = ApiResult.Success(emptyList<Any>())

/**
 * What Kavita does not have: no audio, no mark, no narration (every write is refused, nothing is asked of the
 * server), and how a refused download is told.
 */
class KavitaEmptyAnswersTest {

    @Test
    fun `no audio, no marks and no narration are served`() = runBlocking {
        assertThat(EmptyKavitaAudio().assetUrl(Book, "a")).isNull()
        assertThat(EmptyKavitaAudio().manifest(Book)).isEqualTo(NotFound)
        assertThat(EmptyKavitaAudioBookmarks().list(Book)).isEqualTo(Nothing)
        assertThat(EmptyKavitaMarks().bookmarks(Book, File)).isEqualTo(Nothing)
        assertThat(EmptyKavitaMarks().highlights(Book, File)).isEqualTo(Nothing)
        assertThat(EmptyKavitaMarks().addBookmark(Book, File, "cfi", "title")).isEqualTo(NotFound)
        assertThat(EmptyKavitaReadAlong().trackUrl(Book, File, "a.mp3")).isNull()
        assertThat(KavitaAccountPictures.pictureUrl("https://kavita.example.com", "/avatar")).isNull()
    }
}
