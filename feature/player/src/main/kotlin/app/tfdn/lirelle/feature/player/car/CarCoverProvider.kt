package app.tfdn.lirelle.feature.player.car

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.session.profile.openEntryOrNull
import app.tfdn.lirelle.feature.player.di.PlayerProfileEntry
import app.tfdn.lirelle.feature.player.di.PlayerReceiverEntry
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The apps that are served covers besides the app itself: Android Auto on the phone, which draws what the car
 * shows. The system vouches for the name of the package that asks, and a cover is nothing secret; anyone else
 * is refused all the same, so that the provider is of no use to another app.
 */
private val CarHosts = setOf("com.google.android.projection.gearhead")

/** How long a cover may take to come from the server: past it the car shows its placeholder. */
private const val FetchTimeoutMillis = 15_000L

/** The covers of the open profile, with the profile they are of. */
class ProfileCovers(val profileId: String, val files: CarCoverFiles)

/**
 * Serves the car the covers of the open profile, at the addresses of [CarCovers]: the car loads pictures by
 * itself and cannot sign in to the server. Exported without a permission, which the car's host could not be
 * granted: it answers the car's host and the app only ([CarHosts]), with the covers of the profile open at that
 * moment and of no other, and only to be read.
 *
 * The system makes it, at any time, even with nobody signed in: the open profile is asked at each request
 * ([open]) and never kept.
 *
 * @param timeoutMillis how long a request waits for its cover.
 */
class CarCoverProvider internal constructor(
    private val timeoutMillis: Long,
    private val open: (Context) -> ProfileCovers?,
) : ContentProvider() {

    constructor() : this(FetchTimeoutMillis, ::openProfileCovers)

    override fun onCreate(): Boolean = true

    /** On a binder thread, which may wait for the server. */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") notFound("A cover is only read")
        return ParcelFileDescriptor.open(coverFile(uri, callingPackage), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /**
     * The file of the cover at [uri], for the package [caller].
     *
     * @throws FileNotFoundException for anyone but the car's host and the app, for a profile that is not the
     *   open one, and for a cover that cannot be had in time.
     */
    internal fun coverFile(uri: Uri, caller: String?): File {
        val context = requireNotNull(context)
        if (caller != context.packageName && caller !in CarHosts) notFound("Not for $caller")
        val segments = uri.pathSegments
        if (segments.size != 2) notFound("Not a cover: $uri")
        val (profileId, bookId) = segments
        val covers = open(context)?.takeIf { it.profileId == profileId } ?: notFound("Not the open profile")
        return runBlocking { withTimeoutOrNull(timeoutMillis) { covers.files.file(BookId(bookId)) } }
            ?: notFound("No cover for $uri")
    }

    /** What a provider answers for a file it does not give: the car then shows its placeholder. */
    private fun notFound(why: String): Nothing = throw FileNotFoundException(why)

    /** The picture's own type is in the file, which the car's host reads. */
    override fun getType(uri: Uri): String = "image/*"

    /** Nothing is listed: a cover is asked by its address. */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException(ReadOnly)

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException(ReadOnly)

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException(ReadOnly)

    private companion object {
        const val ReadOnly = "Covers are only read"
    }
}

/** The covers of the profile open now; null when none is. Hilt does not inject a provider: asked as a receiver does. */
private fun openProfileCovers(context: Context): ProfileCovers? =
    EntryPointAccessors.fromApplication(context.applicationContext, PlayerReceiverEntry::class.java)
        .currentProfile()
        .openEntryOrNull<PlayerProfileEntry>()
        ?.let { ProfileCovers(it.profileId, it.entry.carCovers()) }
