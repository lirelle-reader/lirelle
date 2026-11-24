package app.tfdn.lirelle.core.network

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Carries the OIDC redirect URI from the activity that receives it to the sign-in screen.
 * The redirect can arrive before the screen exists (the app may have been killed while the
 * browser was in front), so the latest one is kept until it is collected.
 *
 * Each redirect is taken once, by one collector: the sign-in screen while one is shown — alone while nobody
 * is signed in, or over a profile — and otherwise, while a profile is open, what the signed-in app places
 * beside its screens for the redirects that find no sign-in screen. That one is told whether a sign-in screen
 * is shown from the first composition on, so that a redirect kept here for a sign-in screen made again is
 * left to it. For the moment both collect — a frame as the sign-in screens come — the redirect still reaches
 * only one of them, and either does the same with it: completes the sign-in it answers, or drops it.
 */
@Singleton
class OidcRedirectRelay @Inject constructor() {
    private val channel = Channel<String>(Channel.CONFLATED)

    val redirects: Flow<String> = channel.receiveAsFlow()

    fun deliver(redirectUri: String) {
        channel.trySend(redirectUri)
    }
}
