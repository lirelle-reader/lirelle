package app.tfdn.lirelle.feature.player.car

import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import app.tfdn.lirelle.core.ids.BookId
import com.google.common.util.concurrent.ListenableFuture

/**
 * The cover of the playing book as the session says it. The session gives every controller the cover's address
 * beside its picture, and a car may load the address by itself: the server's, where it cannot sign in, or a
 * file of the app's, which it cannot read, shows nothing there. The address said is therefore the one of
 * [CarCovers], which [CarCoverProvider] answers for the open profile; the app's own picture — the
 * notification's, the lock screen's — is still loaded from the cover's own address ([loader]).
 *
 * @param authority the authority of the app's covers ([CarCovers.authority]).
 * @param profileId the profile the service plays for.
 */
class PlayingCover(private val authority: String, private val profileId: String) {
    /** The address said for the playing book, with the cover's own. */
    @Volatile
    private var playing: Pair<Uri, Uri>? = null

    /**
     * The address the session says for the cover of [bookId], whose own address is [address] (the server's, or
     * a file kept with a download); null for a book without a cover.
     */
    fun said(bookId: BookId, address: String?): Uri? {
        if (address == null) return null
        return CarCovers.uri(authority, profileId, bookId).also { playing = it to Uri.parse(address) }
    }

    /** The cover's own address for the one [said] of the playing book; any other address as it is. */
    fun own(address: Uri): Uri = playing?.takeIf { it.first == address }?.second ?: address

    /** [covers], which loads the picture of the address [said] from the cover's own, as it always did. */
    @OptIn(UnstableApi::class)
    fun loader(covers: BitmapLoader): BitmapLoader = object : BitmapLoader {
        override fun supportsMimeType(mimeType: String): Boolean = covers.supportsMimeType(mimeType)

        override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = covers.decodeBitmap(data)

        override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = covers.loadBitmap(own(uri))
    }
}
