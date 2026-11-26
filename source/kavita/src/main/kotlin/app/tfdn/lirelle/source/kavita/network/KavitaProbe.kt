package app.tfdn.lirelle.source.kavita.network

import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.ProbeAnswer
import app.tfdn.lirelle.core.network.ServerProbe
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Response

/** The health route as the probe's answer arrives at it: what is left of its address is the server's. */
private const val HealthPath = "/$HealthRoute"

/** What a Kavita server in good health answers, as plain text. */
private const val Healthy = "Ok"

/** No honest answer about health is longer; an answer that is, is some web page. */
private const val MaxHealthBytes = 64L

/** No honest answer about the identity provider is longer; an answer that is, is some web page. */
private const val MaxSettingsBytes = 64L * 1024

private const val Ok = 200
private val ServerErrors = 500..599

/**
 * The address to remember after the probe of [typed] was answered at [answeredAt], or null when that answer gives
 * none to trust: it is not the health route, or it left `https` for cleartext. The probe follows redirects (a
 * reverse proxy sending `http` to `https`, or to the base path Kavita sits under): the address that answered is
 * kept, base path included, so that the sign-in does not go through the redirect again.
 */
internal fun kavitaAnsweringAddress(typed: String, answeredAt: HttpUrl): String? {
    if (answeredAt == "$typed$HealthPath".toHttpUrlOrNull()) return typed
    // The cleartext warning is only shown for an address typed with `http`.
    if (typed.toHttpUrlOrNull()?.isHttps == true && !answeredAt.isHttps) return null
    val path = answeredAt.encodedPath
    if (!path.endsWith(HealthPath)) return null
    return answeredAt.newBuilder()
        .encodedPath(path.removeSuffix(HealthPath).ifEmpty { "/" })
        .query(null)
        .fragment(null)
        .build()
        .toString()
        .trimEnd('/')
}

/**
 * A Kavita server is one whose two public answers are Kavita's: its health route answers the plain text `Ok`, and
 * its identity provider's settings, answered even when none is set up, are an object that says whether there is
 * one, whether passwords are still taken, and the provider's name. Both, strictly: a web page, a health told in
 * other words, or settings of another shape are another server's, which another kind may know. A failing server
 * is a failure: nothing is known of it.
 *
 * The client it is given should time out quickly: the probe comes after the other kinds', and a server that does
 * not answer must not make the user wait as long again.
 */
internal class KavitaProbe(
    private val api: KavitaApis<KavitaPublicApi>,
    private val errors: KavitaErrors,
) : ServerProbe {

    override suspend fun probe(address: String): ApiResult<ProbeAnswer> = errors.call(KavitaCall.Probe) {
        val health = api.forServer(address).health().failingOnServerError()
        val healthy = health.code() == Ok && health.body().text(MaxHealthBytes)?.trim() == Healthy
        health.errorBody()?.close()
        val answering = kavitaAnsweringAddress(address, health.raw().request.url)
        if (answering == null || !healthy) return@call ProbeAnswer.NotMine
        val settings = api.forServer(answering).oidcSettings().failingOnServerError()
        val told = settings.code() == Ok && isOidcSettings(settings.body().text(MaxSettingsBytes))
        settings.errorBody()?.close()
        if (told) ProbeAnswer.Mine(answering) else ProbeAnswer.NotMine
    }

    /** The answer, unless the server failed: a failure then, as the errors say it. */
    private fun <T> Response<T>.failingOnServerError(): Response<T> {
        if (code() in ServerErrors) throw HttpException(this)
        return this
    }

    /** The text of this body, or null when there is none or it is longer than [most] bytes. */
    private fun ResponseBody?.text(most: Long): String? = this?.use {
        val source = it.source()
        if (source.request(most + 1)) null else source.buffer.readUtf8()
    }

    /** Whether [body] is a JSON object that says what Kavita says of its identity provider. */
    private fun isOidcSettings(body: String?): Boolean {
        val parsed = try {
            KavitaJson.parseToJsonElement(body ?: return false)
        } catch (_: SerializationException) {
            return false
        }
        if (parsed !is JsonObject) return false
        return parsed.isBoolean("enabled") && parsed.isBoolean("disablePasswordAuthentication") &&
            (parsed["providerName"] as? JsonPrimitive)?.isString == true
    }

    private fun JsonObject.isBoolean(name: String): Boolean {
        val value = this[name] as? JsonPrimitive ?: return false
        return !value.isString && value.booleanOrNull != null
    }
}
