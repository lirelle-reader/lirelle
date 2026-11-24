package app.tfdn.lirelle.source.bookorbit.network

import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.ProbeAnswer
import app.tfdn.lirelle.core.network.ServerProbe
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.HttpException

private const val BookOrbitProduct = "bookorbit"
private const val IdentityPath = "/api/v1/server-identity"

/**
 * The address to remember after a probe of [typed] was answered at [answeredAt], or null when
 * that answer gives no address to trust: it is not an identity route, or it left `https`
 * for cleartext.
 *
 * The probe is a GET and follows redirects, for example from `http` to `https` behind a
 * reverse proxy. Later POSTs to the typed address would not survive that redirect, or
 * would carry the password in cleartext first, so the address that answered is kept.
 */
internal fun answeringAddress(typed: String, answeredAt: HttpUrl): String? {
    if (answeredAt == "$typed$IdentityPath".toHttpUrlOrNull()) return typed
    // The cleartext warning is only shown for an address typed with `http`: a redirect must
    // not move the user from `https` to cleartext without it.
    if (typed.toHttpUrlOrNull()?.isHttps == true && !answeredAt.isHttps) return null
    val path = answeredAt.encodedPath
    // Redirected somewhere that is not an identity route: no server address to derive.
    if (!path.endsWith(IdentityPath)) return null
    return answeredAt.newBuilder()
        .encodedPath(path.removeSuffix(IdentityPath).ifEmpty { "/" })
        .query(null)
        .fragment(null)
        .build()
        .toString()
        .trimEnd('/')
}

/**
 * A BookOrbit server is one whose identity route names the product. Any other answer — another product, a web
 * page, a refusal of the route — is another server's, which another kind may know.
 */
internal class BookOrbitProbe(
    private val publicApi: ApiProvider<PublicApi>,
    private val errors: ErrorMapper,
) : ServerProbe {

    override suspend fun probe(address: String): ApiResult<ProbeAnswer> {
        val answer = errors.call(CallKind.Probe) {
            val response = publicApi.forServer(address).serverIdentity()
            if (!response.isSuccessful) throw HttpException(response)
            val answering = answeringAddress(address, response.raw().request.url)
            if (response.body()?.product != BookOrbitProduct || answering == null) {
                ProbeAnswer.NotMine
            } else {
                ProbeAnswer.Mine(answering)
            }
        }
        // What the errors make of an answer that is not BookOrbit's: a server, not a failure.
        val other = (answer as? ApiResult.Failure)?.error == ApiError.UnknownServer
        return if (other) ApiResult.Success(ProbeAnswer.NotMine) else answer
    }
}
