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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** How long a cover may take to come from the server: past it the car shows its placeholder. */
private const val FetchTimeoutMillis = 15_000L

/**
 * The covers of the open profile, with the profile they are of.
 *
 * @property open whether that profile is still the open one, as it changes: the same account opened again is
 *   another, and once false it is never true again.
 * @property isOpen what [open] says now.
 */
class ProfileCovers(
    val profileId: String,
    val files: CarCoverFiles,
    val open: Flow<Boolean>,
    val isOpen: () -> Boolean,
) {
    /** What [fetch] finds; null once the profile closed, and the fetch is given up then. */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal suspend fun <T : Any> whileOpen(fetch: suspend CarCoverFiles.() -> T?): T? =
        open.mapLatest { stillOpen -> if (stillOpen) files.fetch() else null }.first()
}

/**
 * Serves the car the covers of the open profile, at the addresses of [CarCovers]: the car loads pictures by
 * itself and cannot sign in to the server. Exported without a permission, which the car's host could not be
 * granted: it answers the app and the car's host only, which is Android Auto as Google signed it
 * ([isAndroidAuto]): the system vouches for the name of the package that asks, and for who signed it. It serves
 * the covers of the profile open at that moment and of no other, and only to be read: a profile that closes while
 * its cover is fetched gives none.
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

    /** On a binder thread, which may wait for the server: [CarCoverFetches] bound how many do, and how often. */
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
        if (caller != context.packageName && !context.packageManager.isAndroidAuto(caller)) {
            notFound("Not for $caller")
        }
        val segments = uri.pathSegments
        if (segments.size != 2) notFound("Not a cover: $uri")
        val (profileId, bookId) = segments
        val covers = open(context)?.takeIf { it.profileId == profileId } ?: notFound("Not the open profile")
        val file = runBlocking { withTimeoutOrNull(timeoutMillis) { covers.whileOpen { file(BookId(bookId)) } } }
            ?: notFound("No cover for $uri")
        // Fetching it took the time of the server, in which the profile may have closed: asked again last.
        if (!covers.isOpen()) notFound("Not the open profile any more")
        return file
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
        ?.let { ProfileCovers(it.profileId, it.entry.carCovers(), it.open, it::isOpen) }
