package app.tfdn.lirelle.feature.player.car

import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.ids.BookId
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Test
import org.junit.runner.RunWith

private const val Authority = "app.tfdn.lirelle.covers"

/** The address the session says for the playing book's cover, and where the app's own picture comes from. */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class PlayingCoverTest {

    private val cover = PlayingCover(Authority, profileId = "profile-1")
    private val loaded = mutableListOf<Uri>()
    private val loader = cover.loader(
        object : BitmapLoader {
            override fun supportsMimeType(mimeType: String): Boolean = mimeType == "image/png"

            override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
                Futures.immediateFailedFuture(UnsupportedOperationException())

            override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
                loaded += uri
                return Futures.immediateFailedFuture(UnsupportedOperationException())
            }
        },
    )

    @Test
    fun `the address said is the one the car is served, of the profile played for`() {
        assertThat(cover.said(BookId("42"), "https://books.example/c/42"))
            .isEqualTo(CarCovers.uri(Authority, "profile-1", BookId("42")))
        assertThat(cover.said(BookId("42"), "file:///kept/42/cover"))
            .isEqualTo(Uri.parse("content://$Authority/profile-1/42"))
    }

    @Test
    fun `a book without a cover is said none`() {
        assertThat(cover.said(BookId("42"), null)).isNull()
    }

    @Test
    fun `the app's own picture is loaded from the cover's own address, the server's or the device's`() {
        val said = checkNotNull(cover.said(BookId("42"), "https://books.example/thumb/42"))
        loader.loadBitmap(said)
        val kept = checkNotNull(cover.said(BookId("43"), "file:///kept/43/cover"))
        loader.loadBitmap(kept)

        assertThat(loaded)
            .containsExactly(Uri.parse("https://books.example/thumb/42"), Uri.parse("file:///kept/43/cover"))
            .inOrder()
    }

    @Test
    fun `the picture of the item's metadata is loaded from the cover's own address too`() {
        val said = checkNotNull(cover.said(BookId("42"), "https://books.example/thumb/42"))
        val metadata = MediaMetadata.Builder().setArtworkUri(said).build()

        loader.loadBitmapFromMetadata(metadata)

        assertThat(loaded).containsExactly(Uri.parse("https://books.example/thumb/42"))
    }

    @Test
    fun `any other address is loaded as it is, the one said of a book played before included`() {
        val before = checkNotNull(cover.said(BookId("42"), "https://books.example/thumb/42"))
        cover.said(BookId("43"), "https://books.example/thumb/43")

        loader.loadBitmap(before)
        loader.loadBitmap(Uri.parse("https://books.example/other"))

        assertThat(loaded).containsExactly(before, Uri.parse("https://books.example/other")).inOrder()
    }

    @Test
    fun `what the loader can decode is what its covers can`() {
        assertThat(loader.supportsMimeType("image/png")).isTrue()
        assertThat(loader.supportsMimeType("image/webp")).isFalse()
    }
}
