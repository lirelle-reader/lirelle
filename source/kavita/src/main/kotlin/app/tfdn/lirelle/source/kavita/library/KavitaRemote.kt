package app.tfdn.lirelle.source.kavita.library

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiErrorException
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.session.profile.ProfileSession
import app.tfdn.lirelle.source.kavita.network.KavitaApis
import app.tfdn.lirelle.source.kavita.network.KavitaCall
import app.tfdn.lirelle.source.kavita.network.KavitaErrors
import app.tfdn.lirelle.source.kavita.network.answer
import retrofit2.HttpException
import retrofit2.Response

/** The routes of the profile's server, and how its answers become the app's models. */
internal class KavitaRemote(val api: KavitaLibraryApi, val mapper: KavitaMapper)

/**
 * How the library speaks to the open profile's Kavita server: with its key, at the address its session holds when
 * the call is made, through [session]. Whatever a call throws becomes the error the screens show, as a call made
 * with the profile's key: a refused key is a session that ended, a 404 something not found, and what the server
 * says of a failure is never read.
 */
internal class KavitaProfileServer(
    private val session: ProfileSession,
    private val apis: KavitaApis<KavitaLibraryApi>,
    private val titles: KavitaTitles,
    private val errors: KavitaErrors,
) {

    /** The profile's server as it is now. */
    fun remote(): KavitaRemote {
        val serverUrl = session.session.value.serverUrl
        return KavitaRemote(apis.forServer(serverUrl), KavitaMapper(serverUrl, titles))
    }

    /** What [block] answers, or the error it met. */
    suspend fun <T> call(block: suspend (KavitaRemote) -> T): ApiResult<T> =
        errors.call(KavitaCall.Authenticated) { block(remote()) }

    /**
     * What [block] answers for a list the paging loads, which knows no HTTP: what the server refused comes out as
     * its error.
     */
    suspend fun <T> loaded(block: suspend (KavitaRemote) -> T): T = try {
        block(remote())
    } catch (exception: HttpException) {
        throw ApiErrorException(errors.map(exception, KavitaCall.Authenticated))
    }

    /** What [block] answers; null when it fails, for what a page can show without. */
    suspend fun <T> orNull(block: suspend () -> T): T? =
        (errors.call(KavitaCall.Authenticated) { block() } as? ApiResult.Success)?.value

    /** The error to show for a list that failed to load. */
    fun errorOf(throwable: Throwable): ApiError = errors.map(throwable, KavitaCall.Authenticated)
}

/** Nothing of that id: what is asked of the server is not there. */
internal fun notFound(): Nothing = throw ApiErrorException(ApiError.NotFound)

/**
 * Every page of a list Kavita gives by pages, the first being 1, in batches of [BatchSize]. A batch shorter than
 * what was asked for is the last one, whatever total the `Pagination` header says; so is the one that reaches that
 * total.
 */
internal suspend fun <T> wholeList(fetch: suspend (page: Int, size: Int) -> Response<List<T>>): List<T> {
    val all = mutableListOf<T>()
    var page = FirstPage
    do {
        val response = fetch(page, BatchSize)
        val batch = response.answer()
        all += batch
        val more = batch.size >= BatchSize && all.size < (response.totalItems() ?: Int.MAX_VALUE)
        page++
    } while (more && page < FirstPage + MaxBatches)
    return all
}

/** How many items each batch of a whole list holds. */
internal const val BatchSize = 100

/** Kavita numbers its pages from 1. */
internal const val FirstPage = 1

/** Safety stop for whole-list loads: 5 000 items. */
private const val MaxBatches = 50
