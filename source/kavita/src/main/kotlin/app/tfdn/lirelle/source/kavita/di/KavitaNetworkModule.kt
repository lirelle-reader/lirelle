package app.tfdn.lirelle.source.kavita.di

import app.tfdn.lirelle.core.network.DeviceLabelProvider
import app.tfdn.lirelle.core.network.ServerProbe
import app.tfdn.lirelle.core.network.SourceSignIn
import app.tfdn.lirelle.core.network.di.PublicClient
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.session.SessionStore
import app.tfdn.lirelle.core.session.profile.SourceOf
import app.tfdn.lirelle.source.kavita.network.KavitaApis
import app.tfdn.lirelle.source.kavita.network.KavitaErrors
import app.tfdn.lirelle.source.kavita.network.KavitaJson
import app.tfdn.lirelle.source.kavita.network.KavitaProbe
import app.tfdn.lirelle.source.kavita.network.KavitaPublicApi
import app.tfdn.lirelle.source.kavita.network.KavitaRevocation
import app.tfdn.lirelle.source.kavita.network.KavitaSignIn
import app.tfdn.lirelle.source.kavita.network.kavitaSignInClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * How long each of the probe's requests may take, from its connection to its last byte: the probe comes after the
 * other kinds', and a server that does not answer must not make the user wait as long again.
 */
private const val ProbeCallTimeoutSeconds = 5L

/**
 * What reaches a Kavita server before anyone is signed in: telling its servers from others, and signing in to them
 * — there is no profile yet. The app sends there what is Kavita's.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object KavitaNetworkModule {

    /** How Kavita's answers are read, for every API of the source. */
    @Provides
    @Singleton
    @SourceOf(ServerKind.Kavita)
    fun json(): Json = KavitaJson

    /**
     * Signing in and deleting keys: a password, a session or a key goes to the server it is for, and never along
     * a redirect to another.
     */
    @Provides
    @Singleton
    fun publicApi(
        @PublicClient client: OkHttpClient,
        @SourceOf(ServerKind.Kavita) json: Json,
    ): KavitaApis<KavitaPublicApi> = KavitaApis(kavitaSignInClient(client), json, KavitaPublicApi::class.java)

    @Provides
    @Singleton
    @SourceOf(ServerKind.Kavita)
    fun probe(@PublicClient client: OkHttpClient, @SourceOf(ServerKind.Kavita) json: Json): ServerProbe {
        val brief = client.newBuilder().callTimeout(ProbeCallTimeoutSeconds, TimeUnit.SECONDS).build()
        return KavitaProbe(KavitaApis(brief, json, KavitaPublicApi::class.java), KavitaErrors())
    }

    /** Signing in, and deleting the keys the app lets go of, soon given up when the server does not answer. */
    @Provides
    @Singleton
    @SourceOf(ServerKind.Kavita)
    fun signIn(
        store: SessionStore,
        publicApi: KavitaApis<KavitaPublicApi>,
        deviceLabel: DeviceLabelProvider,
        @PublicClient client: OkHttpClient,
        @SourceOf(ServerKind.Kavita) json: Json,
    ): SourceSignIn = KavitaSignIn(store, publicApi, KavitaErrors(), deviceLabel, KavitaRevocation(client, json))
}
