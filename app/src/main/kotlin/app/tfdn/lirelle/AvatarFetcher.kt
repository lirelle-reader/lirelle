package app.tfdn.lirelle

import app.tfdn.lirelle.core.library.net.ProfileNetwork
import app.tfdn.lirelle.core.network.AccountPictures
import app.tfdn.lirelle.core.network.di.AuthenticatedClient
import app.tfdn.lirelle.core.session.Session
import app.tfdn.lirelle.core.session.profile.ProfileAvatars
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import app.tfdn.lirelle.core.session.profile.ProfileSession
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

/** The largest picture kept: an avatar is drawn a few dozen pixels wide, and the device keeps one per profile. */
private const val MaxPictureBytes = 2L * 1024 * 1024

private const val ImageType = "image"

/**
 * Keeps on the device the picture of the open profile's account, so that its avatar shows it — also later,
 * while the profile sleeps and none of its objects exist ([ProfileAvatars]). Only the open profile can fetch
 * it: the server gives the picture to its account alone.
 *
 * It follows the profile's session: an account that has no picture loses the one kept; a picture kept for
 * another `avatarUrl` than the account's is fetched again. A fetch that fails says nothing and leaves what
 * there was; it is tried again when the session next changes — the account read again, its tokens renewed —,
 * when the profile's network comes back, which a sign-in after an expired session is too, and at the next
 * start. Nothing is asked of the server meanwhile.
 *
 * A picture the server did give and that is not kept — too large, or not an image — is another matter: asking
 * again would bring the same answer, at every renewal of the tokens. It is not asked for again for as long as
 * the profile's objects live and the account's `avatarUrl` stays the same.
 */
@ProfileScoped
class AvatarFetcher @Inject constructor(
    private val session: ProfileSession,
    private val network: ProfileNetwork,
    @AuthenticatedClient client: OkHttpClient,
    private val avatars: ProfileAvatars,
    private val pictures: AccountPictures,
) {
    /**
     * The profile's client, which carries its token to its server only, and here follows no redirection: the
     * picture is on the profile's server or it is not fetched.
     */
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    /** The `avatarUrl` whose picture was refused, while the account still has it; forgotten with these objects. */
    @Volatile
    private var refused: String? = null

    /**
     * Follows the session and the profile's network for as long as [scope] lives. A newer state ends the
     * fetch under way: what it would have kept may already be the picture of before.
     */
    fun start(scope: CoroutineScope) {
        scope.launch {
            session.session.combine(network.online, ::Pair).collectLatest { (now, online) -> follow(now, online) }
        }
    }

    /**
     * Brings the kept picture in line with the account of [session]. Deleting needs no server; fetching
     * needs the profile's network ([online]), a profile that is still there to act for, and a picture that
     * was not refused already.
     */
    internal suspend fun follow(session: Session, online: Boolean) {
        val id = this.session.profileId
        val wanted = session.user.avatarUrl
        if (wanted != refused) refused = null
        try {
            when {
                wanted == null -> avatars.delete(id)
                wanted == refused || wanted == avatars.keptFor(id) || !online -> Unit
                else -> when (val answer = fetch(session.serverUrl, wanted)) {
                    is PictureAnswer.Picture -> avatars.keep(id, wanted, answer.bytes)
                    PictureAnswer.Refused -> refused = wanted
                    PictureAnswer.Failed -> Unit
                }
            }
        } catch (_: IOException) {
            // Not kept this time: the initial shows, or the picture there was.
        }
    }

    /**
     * Asks the server for the picture, unless the profile may not reach it at this very moment. What the
     * caller knew of the network is a state that follows the session's mark a few dispatches late: the mark
     * and the network are asked again here where they are known, right before the request, so that none
     * leaves for a server that has just refused the profile's tokens.
     */
    private suspend fun fetch(server: String, avatarUrl: String): PictureAnswer {
        val url = pictures.pictureUrl(server, avatarUrl)?.takeIf { it.isOn(server) } ?: return PictureAnswer.Refused
        val reachable = network.isOnline && !session.signInNeeded.value && session.open
        if (!reachable) return PictureAnswer.Failed
        return client.newCall(Request.Builder().url(url).build()).awaitPicture()
    }
}

/** What asking for a picture came to. */
internal sealed interface PictureAnswer {
    /** The picture, to be kept. */
    class Picture(val bytes: ByteArray) : PictureAnswer

    /**
     * The server answered, with something that is not kept — not an image, an empty or too large one — or
     * the source gives no address on the profile's server for the account's `avatarUrl` ([AccountPictures]): the
     * same question would get the same answer.
     */
    data object Refused : PictureAnswer

    /** No answer, or the server's refusal to give one: worth asking again later. */
    data object Failed : PictureAnswer
}

/**
 * Whether the address is on [server], the profile's address: same scheme, host and port. Neither the server's
 * answers nor the source's reading of them are trusted with where the profile's token goes.
 */
private fun HttpUrl.isOn(server: String): Boolean {
    val home = server.toHttpUrlOrNull() ?: return false
    return scheme == home.scheme && host == home.host && port == home.port
}

/**
 * What the server answers ([pictureIn]); [PictureAnswer.Failed] when it cannot be reached or its answer cannot
 * be read to its end. Read where the answer arrives, on the client's own thread; cancelled with the caller.
 */
private suspend fun Call.awaitPicture(): PictureAnswer = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) = continuation.resume(PictureAnswer.Failed)

            override fun onResponse(call: Call, response: Response) =
                continuation.resume(runCatching { response.use(::pictureIn) }.getOrDefault(PictureAnswer.Failed))
        },
    )
}

/**
 * The picture in [response] when it is a success, says it is an image and holds at most [MaxPictureBytes];
 * [PictureAnswer.Refused] when it is a success that holds anything else, [PictureAnswer.Failed] when the
 * server did not answer the question — an error, a redirection. Never reads more than one byte past the
 * limit, whatever length the answer announces.
 */
internal fun pictureIn(response: Response): PictureAnswer {
    if (!response.isSuccessful) return PictureAnswer.Failed
    val body = response.body
    if (body.contentType()?.type != ImageType || body.contentLength() > MaxPictureBytes) return PictureAnswer.Refused
    val read = Buffer()
    val source = body.source()
    var more = true
    while (more) {
        more = read.size <= MaxPictureBytes && source.read(read, MaxPictureBytes + 1 - read.size) != -1L
    }
    return if (read.size in 1..MaxPictureBytes) PictureAnswer.Picture(read.readByteArray()) else PictureAnswer.Refused
}
