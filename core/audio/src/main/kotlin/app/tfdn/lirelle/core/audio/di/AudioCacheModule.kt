package app.tfdn.lirelle.core.audio.di

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.tfdn.lirelle.core.audio.ListeningFiles
import app.tfdn.lirelle.core.audio.cache.AudioCache
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileFolders
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File

@Module
@InstallIn(ProfileComponent::class)
@OptIn(UnstableApi::class)
internal object AudioCacheModule {

    /**
     * One cache for the profile, in its cache folder, gone with it: a
     * [androidx.media3.datasource.cache.SimpleCache] locks its directory. The player reads through it, and a
     * download takes from it the files it holds whole.
     *
     * No closer here lets go of it: the closers of a profile run one after another, in no guaranteed order, and
     * the cache must not be released under a player still reading. The player's closer does it, once playback
     * has ended ([AudioCache.close]).
     */
    @Provides
    @ProfileScoped
    fun audioCache(@ApplicationContext context: Context, folders: ProfileFolders): AudioCache =
        AudioCache(context, File(folders.cache, ListeningFiles.Cache))
}
