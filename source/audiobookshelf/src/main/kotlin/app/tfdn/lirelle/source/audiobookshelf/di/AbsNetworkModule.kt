package app.tfdn.lirelle.source.audiobookshelf.di

import app.tfdn.lirelle.core.network.ServerProbe
import app.tfdn.lirelle.core.network.SourceSignIn
import app.tfdn.lirelle.core.network.di.PublicClient
import app.tfdn.lirelle.core.session.PendingOidcStore
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.SessionStore
import app.tfdn.lirelle.core.session.profile.SourceOf
import app.tfdn.lirelle.source.audiobookshelf.network.AbsErrors
import app.tfdn.lirelle.source.audiobookshelf.network.AbsLogout
import app.tfdn.lirelle.source.audiobookshelf.network.AbsSignIn
import app.tfdn.lirelle.source.audiobookshelf.network.absOidcApi
import app.tfdn.lirelle.source.audiobookshelf.network.absProbe
import app.tfdn.lirelle.source.audiobookshelf.network.absPublicApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.OkHttpClient

/**
 * What reaches an Audiobookshelf server before anyone is signed in: telling its servers from others, and signing in
 * to them — there is no profile yet. The app sends there what is Audiobookshelf's. Every client is made from the
 * public one, which names the app on each request.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object AbsNetworkModule {

    /** Its status, asked of an address: given up after five seconds. */
    @Provides
    @Singleton
    @SourceOf(ServerKind.Audiobookshelf)
    fun probe(@PublicClient client: OkHttpClient): ServerProbe = absProbe(client)

    /**
     * Signing in, by a name and a password or through the server's provider in the browser, and closing the sessions
     * the app lets go of, soon given up when the server does not answer.
     */
    @Provides
    @Singleton
    @SourceOf(ServerKind.Audiobookshelf)
    fun signIn(
        store: SessionStore,
        pendingOidc: PendingOidcStore,
        @PublicClient client: OkHttpClient,
    ): SourceSignIn = AbsSignIn(
        store = store,
        api = absPublicApi(client),
        oidcApi = absOidcApi(client),
        pendingOidc = pendingOidc,
        errors = AbsErrors(),
        logout = AbsLogout(client),
    )
}
