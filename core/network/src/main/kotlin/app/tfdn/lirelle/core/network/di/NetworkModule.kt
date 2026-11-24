package app.tfdn.lirelle.core.network.di

import android.content.Context
import app.tfdn.lirelle.core.network.AndroidDeviceLabelProvider
import app.tfdn.lirelle.core.network.DeviceLabelProvider
import app.tfdn.lirelle.core.session.ServerKind
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import okhttp3.OkHttpClient

/** OkHttp client without credentials: sign-in, renewal, and anything public. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PublicClient

/**
 * OkHttp client that adds the access token of one profile and renews it; book files, audio streams and covers
 * are fetched with clients made from it. A profile binding: nothing that outlives a profile can hold it.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuthenticatedClient

/**
 * The interceptor, an [okhttp3.Interceptor], that waits longer for the first bytes of a long transfer than for an
 * ordinary answer: a book's file the server may prepare before it sends anything, an audio file, a read-along
 * map, a download that stalls for a moment. Which requests are long is the profile's source's to say: it binds
 * its own ([LongTransferOf]) in the profile component, and the profile's kind chooses it. Every client that
 * fetches files installs it: the reader's, the read-along's, the audio's and the downloads'.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LongTransfer

/**
 * The [LongTransfer] interceptor of the source of the kind of server [kind], beside the others' (a binding takes
 * one qualifier: this one says both). The [LongTransfer] one is that of the profile's own kind, chosen on it.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LongTransferOf(val kind: ServerKind)

/**
 * What reaches a server before anyone is signed in: there is no profile yet. The sign-in itself
 * ([app.tfdn.lirelle.core.network.AuthRepository]) is the app's dispatcher, over what each source provides for
 * its kind: its [app.tfdn.lirelle.core.network.ServerProbe] and its [app.tfdn.lirelle.core.network.SourceSignIn].
 */
@Module
@InstallIn(SingletonComponent::class)
internal object NetworkModule {

    @Provides
    @Singleton
    @PublicClient
    fun publicClient(): OkHttpClient = OkHttpClient.Builder().build()

    @Provides
    @Singleton
    fun deviceLabelProvider(@ApplicationContext context: Context): DeviceLabelProvider =
        AndroidDeviceLabelProvider(context)
}
