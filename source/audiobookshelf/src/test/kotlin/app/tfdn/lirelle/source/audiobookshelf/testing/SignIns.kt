package app.tfdn.lirelle.source.audiobookshelf.testing

import app.tfdn.lirelle.core.session.SessionStore
import app.tfdn.lirelle.source.audiobookshelf.network.AbsErrors
import app.tfdn.lirelle.source.audiobookshelf.network.AbsLogout
import app.tfdn.lirelle.source.audiobookshelf.network.AbsSignIn
import app.tfdn.lirelle.source.audiobookshelf.network.absPublicApi
import java.time.Duration
import java.time.Instant
import okhttp3.OkHttpClient

/** The sign-in id the tests' sign-ins draw. */
const val TheSignInId = "0b9d6c3e-1f2a-4e5b-8c7d-6a5b4c3d2e1f"

/**
 * Audiobookshelf's sign-in as the app makes it, saving to [store]: its tokens made at [now], its sign-in named
 * [TheSignInId], a session it replaces closed and given up after a second.
 */
internal fun absSignIn(
    store: SessionStore,
    now: () -> Instant = { SignedInAt },
    newSignInId: () -> String = { TheSignInId },
) = AbsSignIn(
    store = store,
    api = absPublicApi(OkHttpClient()),
    errors = AbsErrors(),
    logout = AbsLogout(OkHttpClient(), timeout = Duration.ofSeconds(1)),
    now = now,
    newSignInId = newSignInId,
)
