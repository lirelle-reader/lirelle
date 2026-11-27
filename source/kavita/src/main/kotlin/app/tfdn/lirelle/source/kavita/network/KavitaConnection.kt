package app.tfdn.lirelle.source.kavita.network

import app.tfdn.lirelle.core.network.AccountPictures
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

private val DefaultPatience = 5.minutes

/** Kavita's routes of whole files, by the end of their path: a server may be served under a path of its own. */
private val FilePaths = listOf("/api/Download/chapter", "/api/Reader/pdf")

/** Kavita gives an account no picture the app shows: the profile's avatar is its initial. */
internal object KavitaAccountPictures : AccountPictures {
    override fun pictureUrl(server: String, avatarUrl: String): HttpUrl? = null
}

/**
 * Waits longer for the bytes of a whole file than for an ordinary answer: Kavita's long transfers
 * ([app.tfdn.lirelle.core.network.di.LongTransfer]), told by their paths. A chapter's file (`Download/chapter`, an
 * EPUB) may be a zip of several files that Kavita builds before it sends its first byte, and a PDF (`Reader/pdf`) is
 * a whole file too; both are waited for as BookOrbit's book files are, so that a slow server or a transfer that
 * stalls for a moment goes on instead of being cut. A page of a comic is an ordinary answer.
 */
internal class KavitaFilePatience(private val patience: Duration = DefaultPatience) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val path = chain.request().url.encodedPath
        if (FilePaths.none(path::endsWith)) return chain.proceed(chain.request())
        val millis = patience.inWholeMilliseconds.toInt()
        return chain.withReadTimeout(millis, TimeUnit.MILLISECONDS).proceed(chain.request())
    }
}
