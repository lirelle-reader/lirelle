package app.tfdn.lirelle.feature.player.di

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.tfdn.lirelle.core.audio.cache.AudioCache
import app.tfdn.lirelle.core.network.di.AuthenticatedClient
import app.tfdn.lirelle.core.network.di.LongTransfer
import app.tfdn.lirelle.core.session.profile.ProfileCloser
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileFolders
import app.tfdn.lirelle.core.session.profile.ProfileLife
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import app.tfdn.lirelle.feature.player.controller.PlayerControllerScope
import app.tfdn.lirelle.feature.player.service.DataStoreLastBookStore
import app.tfdn.lirelle.feature.player.service.LastBookFiles
import app.tfdn.lirelle.feature.player.service.LastBookMark
import app.tfdn.lirelle.feature.player.service.LastBookStore
import app.tfdn.lirelle.feature.player.service.PlaybackShutdown
import app.tfdn.lirelle.feature.player.service.PlayerNotification
import app.tfdn.lirelle.feature.player.service.endPlayback
import app.tfdn.lirelle.feature.player.service.releaseAfterPlayback
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.multibindings.IntoSet
import java.io.File
import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.cancel
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * The authenticated client, patient with the first bytes of a book's file ([LongTransfer]): for the audio, on its
 * own connections.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MediaClient

/** What the playback service is made of that is one profile's: it plays for the profile open when it starts. */
@Module
@InstallIn(ProfileComponent::class)
internal object PlayerServiceModule {

    /**
     * The profile closes: the book playing is stopped and the service ended first, so that nothing plays, or
     * reads the cache, for an account that left. The closing waits for it ([endPlayback]): the book's last
     * position is written into the profile's stores, which go next. The scope of the profile's controller is
     * ended here rather than with the profile: the session it holds keeps the service from ending.
     *
     * The profile's audio cache, which locks its folder, is let go here and not by a closer of its own: the
     * closers run one after another, in no guaranteed order, and a cache must not be released under a player
     * still reading through it. So it is released after the wait; and when the service had not ended by then,
     * only once it has ([releaseAfterPlayback]). Nothing is emptied: the cache is in the profile's folder,
     * which is deleted when the profile is removed.
     *
     * The notification that asks for a tap to continue listening goes too, once the service has ended and can
     * post it no more: it names a book of this account, and would open it for the next one.
     */
    @Provides
    @IntoSet
    @kotlin.OptIn(DelicateCoroutinesApi::class)
    @OptIn(UnstableApi::class)
    fun stopPlaybackAtClose(
        @ApplicationContext context: Context,
        shutdown: PlaybackShutdown,
        @PlayerControllerScope controller: CoroutineScope,
        cache: AudioCache,
    ): ProfileCloser = ProfileCloser {
        // Until the player itself is gone, which is a little after the service says it no longer runs. This
        // profile's own service: the next profile's may start before this one has ended.
        val reading = shutdown.reading
        val ended = endPlayback(shutdown, reading) { controller.cancel() }
        PlayerNotification.dismissContinue(context)
        // The late release outlives the profile, whose own scope ends with this closing.
        releaseAfterPlayback(ended, reading, GlobalScope) { cache.close() }
    }

    /**
     * Tells the play button's receiver whether the profile keeps a last book, by a file of the profile's
     * folder: there is nothing to read first, even in a process that has just started for the button.
     */
    @Provides
    fun lastBookMark(folders: ProfileFolders): LastBookMark = LastBookMark(File(folders.files, LastBookFiles.Mark))

    /** A file of its own: one store for the profile, as a DataStore's file is opened once. */
    @Provides
    @ProfileScoped
    fun lastBooks(
        folders: ProfileFolders,
        @ProfileLife life: CoroutineScope,
        mark: LastBookMark,
    ): LastBookStore = DataStoreLastBookStore(folders.newDataStore(LastBookFiles.Store, life), mark)

    @Provides
    @ProfileScoped
    @MediaClient
    fun mediaClient(@AuthenticatedClient client: OkHttpClient, @LongTransfer patience: Interceptor): OkHttpClient =
        client.newBuilder()
            // On the pages' connection, the audio's read-ahead would make every page and cover wait behind it.
            .connectionPool(ConnectionPool())
            .addInterceptor(patience)
            .build()
}
