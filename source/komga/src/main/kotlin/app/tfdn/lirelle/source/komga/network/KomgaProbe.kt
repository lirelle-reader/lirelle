package app.tfdn.lirelle.source.komga.network

import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.ProbeAnswer
import app.tfdn.lirelle.core.network.ServerProbe
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Response

/** The providers' route as the probe's answer arrives at it: what is left of its address is the server's. */
private const val ProvidersPath = "/$ProvidersRoute"

/** No honest list of providers is longer; an answer that is, is some web page. */
private const val MaxProvidersBytes = 64L * 1024

private const val Unauthorized = 401
private val ServerErrors = 500..599

/**
 * The address to remember after the probe of [typed] was answered at [answeredAt], or null when that answer gives
 * none to trust: it is not the providers' route, or it left `https` for cleartext. The probe follows redirects (a
 * reverse proxy sending `http` to `https`, or to the base path Komga sits under): the address that answered is
 * kept, base path included, so that the sign-in does not go through the redirect again.
 */
internal fun komgaAnsweringAddress(typed: String, answeredAt: HttpUrl): String? {
    if (answeredAt == "$typed$ProvidersPath".toHttpUrlOrNull()) return typed
    // The cleartext warning is only shown for an address typed with `http`.
    if (typed.toHttpUrlOrNull()?.isHttps == true && !answeredAt.isHttps) return null
    val path = answeredAt.encodedPath
    if (!path.endsWith(ProvidersPath)) return null
    return answeredAt.newBuilder()
        .encodedPath(path.removeSuffix(ProvidersPath).ifEmpty { "/" })
        .query(null)
        .fragment(null)
        .build()
        .toString()
        .trimEnd('/')
}

/**
 * A Komga server is one whose two public answers are Komga's: its providers' route answers a JSON array of
 * providers (empty when none is set up), and its account's route, asked without credentials, refuses with a Basic
 * challenge. Both, strictly: a web page, a refusal of the providers, or a 401 that challenges for anything else is
 * another server's, which another kind may know. A failing server is a failure: nothing is known of it.
 *
 * The client it is given should time out quickly: the probe comes after BookOrbit's, and a server that does not
 * answer must not make the user wait twice as long.
 */
internal class KomgaProbe(
    private val api: KomgaApis<KomgaPublicApi>,
    private val errors: KomgaErrors,
) : ServerProbe {

    override suspend fun probe(address: String): ApiResult<ProbeAnswer> = errors.call(KomgaCall.Probe) {
        val providers = api.forServer(address).providers().failingOnServerError()
        val listed = providers.isSuccessful && isProviderList(providers.body())
        providers.errorBody()?.close()
        val answering = komgaAnsweringAddress(address, providers.raw().request.url)
        if (answering == null || !listed) return@call ProbeAnswer.NotMine
        val account = api.forServer(answering).anonymousAccount().failingOnServerError()
        account.errorBody()?.close()
        val challenge = account.headers().values("WWW-Authenticate")
        if (account.code() == Unauthorized && challenge.any { it.trim().startsWith("Basic", ignoreCase = true) }) {
            ProbeAnswer.Mine(answering)
        } else {
            ProbeAnswer.NotMine
        }
    }

    /** The answer, unless the server failed: a failure then, as the errors say it. */
    private fun <T> Response<T>.failingOnServerError(): Response<T> {
        if (code() in ServerErrors) throw HttpException(this)
        return this
    }

    /** Whether [body] is a JSON array whose every element names a provider by its id. */
    private fun isProviderList(body: ResponseBody?): Boolean = body?.use {
        val source = it.source()
        if (source.request(MaxProvidersBytes + 1)) return@use false
        val parsed = try {
            KomgaJson.parseToJsonElement(source.buffer.readUtf8())
        } catch (_: SerializationException) {
            return@use false
        }
        parsed is JsonArray && parsed.all { it is JsonObject && it["registrationId"] is JsonPrimitive }
    } ?: false
}
