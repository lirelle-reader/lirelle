package app.tfdn.lirelle.feature.player.car

import app.tfdn.lirelle.core.downloads.offline.KeptBookData
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.LibraryRepository
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.di.AuthenticatedClient
import app.tfdn.lirelle.core.session.profile.ProfileFolders
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/** How many covers fetched for the car a profile keeps: past it, those served longest ago go. */
private const val CoverBound = 200

/** The folder of those covers, in the profile's cache. */
private const val CoverFolder = "car-covers"

/** How old a picture being written is when no fetch can still be writing it: the app was stopped meanwhile. */
private const val StalePartMillis = 60 * 60 * 1_000L

/** How many characters of a digest name a file: enough that two books, or two addresses, never share one. */
private const val NameLength = 24

/**
 * The address of the cover of each book the car was listed, as its list gave it: the small one, with the cover's
 * version. One for the profile: the playback service's library writes them, the covers' provider reads them.
 */
@ProfileScoped
class CarCoverUrls @Inject constructor() {
    /** An empty text for a book that was listed without a cover. */
    private val urls = ConcurrentHashMap<BookId, String>()

    fun remember(bookId: BookId, url: String?) {
        urls[bookId] = url.orEmpty()
    }

    /** The cover's address; null for a book listed without a cover. */
    fun of(bookId: BookId): String? = urls[bookId]?.ifEmpty { null }

    /** Whether the book was listed since the profile opened: what [of] says is then what its server says. */
    fun knows(bookId: BookId): Boolean = urls.containsKey(bookId)
}

/**
 * The covers of one profile as files, for the car, which cannot sign in to the server: the cover a download
 * kept, else the server's, fetched with the profile's client and kept in a folder of the profile's cache — one
 * file per book and per address, which changes with the cover, at most [bound] of them.
 *
 * @param client the authenticated one.
 * @param folder where the fetched covers are kept; made when the first one is.
 * @param fetches what bounds the asking of the server.
 */
class CarCoverFiles(
    private val kept: KeptBookData,
    private val urls: CarCoverUrls,
    private val library: LibraryRepository,
    private val client: OkHttpClient,
    private val folder: File,
    private val fetches: CarCoverFetches,
    private val bound: Int = CoverBound,
) {
    /** For the covers' provider, from the open profile's objects. */
    @Inject
    constructor(
        kept: KeptBookData,
        urls: CarCoverUrls,
        library: LibraryRepository,
        @AuthenticatedClient client: OkHttpClient,
        folders: ProfileFolders,
        fetches: CarCoverFetches,
    ) : this(kept, urls, library, client, File(folders.cache, CoverFolder), fetches)

    /**
     * The cover of [bookId] as a file to read; null when it has none, or when it cannot be had. It may block on
     * the server, and stops when its caller gives up.
     */
    suspend fun file(bookId: BookId): File? =
        // The audiobook's own cover first: the car lists what is listened to.
        kept.audioCoverFile(bookId) ?: kept.coverFile(bookId) ?: fromServer(bookId)

    /** The server's cover: the one kept in the folder, else fetched into it, as far as [fetches] let it. */
    private suspend fun fromServer(bookId: BookId): File? {
        val book = digest(bookId.value)
        val listed = urls.knows(bookId)
        val before = if (listed) {
            urls.of(bookId)?.let { fileOf(book, it) }?.takeIf { it.isFile }
        } else {
            // Not listed since the app started (the car shows a list it kept): the cover fetched before will do.
            stored(book).maxByOrNull { it.lastModified() }
        }
        if (before != null) return before.served()
        return fetches.attempt(bookId) {
            (if (listed) urls.of(bookId) else pageCover(bookId))?.let { url -> fetched(bookId, url) }
        }
    }

    /** Where the cover of the book named [book] at [url] is kept. */
    private fun fileOf(book: String, url: String) = File(folder, "$book-${digest(url)}")

    /**
     * The cover of [bookId] at [url], fetched into the folder; null when it cannot be had. The covers of before go,
     * unless the book was listed at another address meanwhile: this fetch is then the one of before, and the
     * newer cover, which may be on its way to the car, stays.
     */
    private suspend fun fetched(bookId: BookId, url: String): File? {
        val book = digest(bookId.value)
        val file = fileOf(book, url)
        return file.takeIf { fetch(url, it) }?.also {
            if (!urls.knows(bookId) || urls.of(bookId) == url) {
                stored(book).filter { old -> old != file }.forEach { old -> old.delete() }
            }
            trim()
        }
    }

    /** The cover the book's page names, for a book no list gave. */
    private suspend fun pageCover(bookId: BookId): String? =
        (library.book(bookId) as? ApiResult.Success)?.value?.coverUrl

    /** The covers kept of the book named [book], whatever their address. */
    private fun stored(book: String): List<File> =
        folder.listFiles { file -> file.name.startsWith("$book-") && !file.name.endsWith(Part) }.orEmpty().toList()

    /** Served now: among the last to go. */
    private fun File.served(): File = also { it.setLastModified(System.currentTimeMillis()) }

    /**
     * Writes the picture at [url] into [target], whole or not at all; false when it cannot be had. Each fetch
     * writes a file of its own: two requests of one cover at once do not write over each other, and the one that
     * ends last finds the cover there.
     */
    private suspend fun fetch(url: String, target: File): Boolean {
        var part: File? = null
        return try {
            val call = client.newCall(Request.Builder().url(url).build())
            folder.mkdirs()
            part = File.createTempFile("${target.name}-", Part, folder)
            unlessGivenUp(call) { download(call, part) } && (part.renameTo(target) || target.isFile)
        } catch (_: IOException) {
            false
        } catch (_: IllegalArgumentException) {
            // An address that is not one.
            false
        } finally {
            part?.delete()
        }
    }

    private fun download(call: Call, into: File): Boolean =
        call.execute().use { response ->
            if (response.isSuccessful) into.outputStream().use { response.body.byteStream().copyTo(it) }
            response.isSuccessful
        }

    /** Past the bound, the covers served longest ago go; and what a fetch that never ended left behind. */
    private fun trim() {
        val (parts, covers) = folder.listFiles().orEmpty().partition { it.name.endsWith(Part) }
        covers.sortedByDescending { it.lastModified() }.drop(bound).forEach { it.delete() }
        val longAgo = System.currentTimeMillis() - StalePartMillis
        parts.filter { it.lastModified() < longAgo }.forEach { it.delete() }
    }

    private companion object {
        /** A picture being written. */
        const val Part = ".part"

        /** A name for a file from a text of any kind: an id may hold what a file's name may not. */
        fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(NameLength)
    }
}

/**
 * What [read] reads of [call], on a thread that may wait for the server. When its caller gives up the call is
 * cancelled: a thread that waits for a server's bytes is not woken by an interrupt, and one that sends them
 * a few at a time would hold it, and the caller with it, for as long as it likes.
 */
private suspend fun <T> unlessGivenUp(call: Call, read: () -> T): T = coroutineScope {
    val givenUp = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            call.cancel()
        }
    }
    try {
        runInterruptible(Dispatchers.IO) { read() }
    } finally {
        // Read to its end too: cancelling a call that ended does nothing.
        givenUp.cancel()
    }
}
