package app.tfdn.lirelle.source.audiobookshelf.network

import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.ProbeAnswer
import app.tfdn.lirelle.core.network.ServerProbe
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Response

/** The status route as the probe's answer arrives at it: what is left of its address is the server's. */
private const val StatusPath = "/$StatusRoute"

/** What every Audiobookshelf since 2.6.0 says it is, in its status. */
private const val AbsApp = "audiobookshelf"

/** No honest status is longer; an answer that is, is some web page. */
internal const val MaxStatusBytes = 64L * 1024

/**
 * How long each of the probe's requests may take, from its connection to its last byte: a server that does not
 * answer must not keep the user waiting before the other kinds are asked.
 */
private const val ProbeCallTimeoutSeconds = 5L

private const val Ok = 200
private val ServerErrors = 500..599

/**
 * The probe as the app makes it, from the public client ([publicClient]): the agent of the app on its request, and
 * given up after five seconds.
 */
internal fun absProbe(publicClient: OkHttpClient): AbsProbe {
    val brief = publicClient.newBuilder().callTimeout(ProbeCallTimeoutSeconds, TimeUnit.SECONDS).build()
    return AbsProbe(AbsApis(brief, AbsJson, AbsPublicApi::class.java), AbsErrors())
}

/**
 * The address to remember after the probe of [typed] was answered at [answeredAt], or null when that answer gives
 * none to trust: it is not the status route, or it left `https` for cleartext. The probe follows redirects (a
 * reverse proxy sending `http` to `https`, or to the base path Audiobookshelf sits under): the address that answered
 * is kept, base path included, so that the sign-in does not go through the redirect again.
 */
internal fun absAnsweringAddress(typed: String, answeredAt: HttpUrl): String? {
    if (answeredAt == "$typed$StatusPath".toHttpUrlOrNull()) return typed
    // The cleartext warning is only shown for an address typed with `http`.
    if (typed.toHttpUrlOrNull()?.isHttps == true && !answeredAt.isHttps) return null
    val path = answeredAt.encodedPath
    if (!path.endsWith(StatusPath)) return null
    return answeredAt.newBuilder()
        .encodedPath(path.removeSuffix(StatusPath).ifEmpty { "/" })
        .query(null)
        .fragment(null)
        .build()
        .toString()
        .trimEnd('/')
}

/**
 * An Audiobookshelf server is one whose status, answered to anybody, is a JSON object whose `app` is the string
 * `"audiobookshelf"`. BookOrbit and Komga answer that address with their web page, Kavita with a page or a refusal,
 * and another server's JSON says no such `app`: each is another kind's, which another probe may know — and so is an
 * answer longer than any status, of which no more than that length is read. A failing server is a failure: nothing
 * is known of it.
 *
 * The same answer tells the server's version: one too old, or that tells none in numbers, is recognised and refused
 * here ([requireRecentAbs]), so that the address step says why rather than "no server the app knows".
 */
internal class AbsProbe(
    private val api: AbsApis<AbsPublicApi>,
    private val errors: AbsErrors,
) : ServerProbe {

    override suspend fun probe(address: String): ApiResult<ProbeAnswer> = errors.call(AbsCall.Probe) {
        val status = api.forServer(address).status().failingOnServerError()
        // Read first, whatever the status: reading is what closes an answer that is still on its way.
        val said = status.body().textUpTo(MaxStatusBytes)
        status.errorBody()?.close()
        val answering = absAnsweringAddress(address, status.raw().request.url)
        val claims = if (status.code() == Ok) absStatusClaims(said) else null
        if (answering == null || claims == null) return@call ProbeAnswer.NotMine
        requireRecentAbs((claims["serverVersion"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
        ProbeAnswer.Mine(answering)
    }

    /** The answer, unless the server failed: a failure then, as the errors say it. */
    private fun <T> Response<T>.failingOnServerError(): Response<T> {
        if (code() in ServerErrors) throw HttpException(this)
        return this
    }
}

/** What [body] says, when it is a JSON object that says it is Audiobookshelf's status; null otherwise. */
internal fun absStatusClaims(body: String?): JsonObject? {
    val status = try {
        body?.let(AbsJson::parseToJsonElement) as? JsonObject
    } catch (_: SerializationException) {
        null
    }
    val app = status?.get("app") as? JsonPrimitive
    return status.takeIf { app != null && app.isString && app.content == AbsApp }
}
