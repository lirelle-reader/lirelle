package app.tfdn.lirelle.source.audiobookshelf.library

import app.tfdn.lirelle.core.library.model.ServerLibrary
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiErrorException
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.session.profile.ProfileSession
import app.tfdn.lirelle.source.audiobookshelf.library.dto.AbsLibraryDto
import app.tfdn.lirelle.source.audiobookshelf.network.AbsApis
import app.tfdn.lirelle.source.audiobookshelf.network.AbsCall
import app.tfdn.lirelle.source.audiobookshelf.network.AbsErrors
import retrofit2.HttpException

/** The routes of the profile's server, and how its items become the app's books. */
internal class AbsRemote(val api: AbsLibraryApi, val books: AbsBooks) {

    /**
     * The account's book libraries, in the server's order (`displayOrder`): a podcast library is left out of every
     * list, row, search and of the selector.
     */
    suspend fun bookLibraries(): List<AbsLibraryDto> =
        api.libraries().libraries.filter { it.mediaType == BookMedia }.sortedBy { it.displayOrder }

}

/**
 * How the library speaks to the open profile's Audiobookshelf server: with its access token, which the profile's
 * client adds and renews, at the address its session holds when the call is made, through [session]. Whatever a call
 * throws becomes the error the screens show, as a call made with the profile's token: a token refused for good is a
 * session that ended, what the account may not see (403) or what is not there (404) is not found, and what the
 * server says of a failure is never read.
 */
internal class AbsProfileServer(
    private val session: ProfileSession,
    private val apis: AbsApis<AbsLibraryApi>,
    private val errors: AbsErrors,
) {

    /** The profile's server as it is now. */
    fun remote(): AbsRemote {
        val serverUrl = session.session.value.serverUrl
        return AbsRemote(apis.forServer(serverUrl), AbsBooks(serverUrl))
    }

    /** What [block] answers, or the error it met. */
    suspend fun <T> call(block: suspend (AbsRemote) -> T): ApiResult<T> =
        errors.call(AbsCall.Authenticated) { block(remote()) }

    /**
     * What [block] answers for a list the paging loads, which knows no HTTP: what the server refused comes out as
     * its error.
     */
    suspend fun <T> loaded(block: suspend (AbsRemote) -> T): T = try {
        block(remote())
    } catch (exception: HttpException) {
        throw ApiErrorException(errors.map(exception, AbsCall.Authenticated))
    }

    /** What [block] answers; null when it fails, for what a page can show without. */
    suspend fun <T> orNull(block: suspend (AbsRemote) -> T): T? = (call(block) as? ApiResult.Success)?.value

    /** The error to show for a list that failed to load. */
    fun errorOf(throwable: Throwable): ApiError = errors.map(throwable, AbsCall.Authenticated)

    /**
     * The account's book libraries, in the server's order, read now: what the profile keeps of them ([whole]) is
     * replaced with them.
     */
    suspend fun libraries(whole: AbsWholeLibrary): ApiResult<List<ServerLibrary>> = call { remote ->
        whole.readLibraries(remote).map { it.serverLibrary() }
    }
}

/**
 * What [block] answers of one of the account's book libraries; null when the server says the account may not open
 * it (403) or that it is not there (404): a library hidden or removed since the libraries were read is left out.
 */
internal suspend fun <T> unlessLibraryGone(block: suspend () -> T): T? = try {
    block()
} catch (exception: HttpException) {
    if (exception.code() != Forbidden && exception.code() != NotThere) throw exception
    null
}

/** A library as the app shows it. */
internal fun AbsLibraryDto.serverLibrary(): ServerLibrary = ServerLibrary(id.asLibraryId(), name)

/** The media type of a book library; the other is `podcast`. */
private const val BookMedia = "book"

/** What the server answers for a library the account may not open. */
private const val Forbidden = 403

/** What the server answers for a library that is not there. */
private const val NotThere = 404
