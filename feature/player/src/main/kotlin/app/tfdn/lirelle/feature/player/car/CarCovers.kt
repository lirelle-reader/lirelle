package app.tfdn.lirelle.feature.player.car

import android.content.ContentResolver
import android.net.Uri
import app.tfdn.lirelle.core.ids.BookId

/**
 * The addresses of the covers the app serves to a car, which loads pictures by itself and cannot sign in to the
 * server: `content://<authority>/<profileId>/<bookId>`.
 */
object CarCovers {
    /** The authority of the app whose package is [packageName]: the one its manifest declares. */
    fun authority(packageName: String): String = "$packageName.covers"

    /** The cover of [bookId] in the profile [profileId]: ids are text of any kind, each one segment. */
    fun uri(authority: String, profileId: String, bookId: BookId): Uri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_CONTENT)
        .authority(authority)
        .appendPath(profileId)
        .appendPath(bookId.value)
        .build()
}
