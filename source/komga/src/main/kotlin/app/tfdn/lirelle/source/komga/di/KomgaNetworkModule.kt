package app.tfdn.lirelle.source.komga.di

import app.tfdn.lirelle.core.network.DeviceLabelProvider
import app.tfdn.lirelle.core.network.ServerProbe
import app.tfdn.lirelle.core.network.SourceSignIn
import app.tfdn.lirelle.core.network.di.PublicClient
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.SessionStore
import app.tfdn.lirelle.core.session.profile.SourceOf
import app.tfdn.lirelle.source.komga.network.KomgaApis
import app.tfdn.lirelle.source.komga.network.KomgaErrors
import app.tfdn.lirelle.source.komga.network.KomgaJson
import app.tfdn.lirelle.source.komga.network.KomgaProbe
import app.tfdn.lirelle.source.komga.network.KomgaPublicApi
import app.tfdn.lirelle.source.komga.network.KomgaRevocation
import app.tfdn.lirelle.source.komga.network.KomgaSignIn
import app.tfdn.lirelle.source.komga.network.komgaSignInClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * How long each of the probe's requests may take, from its connection to its last byte: the probe comes after
 * BookOrbit's, and a server that does not answer must not make the user wait twice as long.
 */
private const val ProbeCallTimeoutSeconds = 5L

/**
 * What reaches a Komga server before anyone is signed in: telling its servers from others, and signing in to them
 * — there is no profile yet. The app sends there what is Komga's.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object KomgaNetworkModule {

    /** How Komga's answers are read, for every API of the source. */
    @Provides
    @Singleton
    @SourceOf(ServerKind.Komga)
    fun json(): Json = KomgaJson

    /**
     * Signing in and revoking keys: a password, a session or a key goes to the server it is for, and never along
     * a redirect to another.
     */
    @Provides
    @Singleton
    fun publicApi(
        @PublicClient client: OkHttpClient,
        @SourceOf(ServerKind.Komga) json: Json,
    ): KomgaApis<KomgaPublicApi> = KomgaApis(komgaSignInClient(client), json, KomgaPublicApi::class.java)

    @Provides
    @Singleton
    @SourceOf(ServerKind.Komga)
    fun probe(@PublicClient client: OkHttpClient, @SourceOf(ServerKind.Komga) json: Json): ServerProbe {
        val brief = client.newBuilder().callTimeout(ProbeCallTimeoutSeconds, TimeUnit.SECONDS).build()
        return KomgaProbe(KomgaApis(brief, json, KomgaPublicApi::class.java), KomgaErrors())
    }

    /** Signing in, and revoking the keys the app lets go of, soon given up when the server does not answer. */
    @Provides
    @Singleton
    @SourceOf(ServerKind.Komga)
    fun signIn(
        store: SessionStore,
        publicApi: KomgaApis<KomgaPublicApi>,
        deviceLabel: DeviceLabelProvider,
        @PublicClient client: OkHttpClient,
        @SourceOf(ServerKind.Komga) json: Json,
    ): SourceSignIn = KomgaSignIn(store, publicApi, KomgaErrors(), deviceLabel, KomgaRevocation(client, json))
}
