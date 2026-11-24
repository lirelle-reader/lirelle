package app.tfdn.lirelle.core.audio.di

import app.tfdn.lirelle.core.audio.ListeningFiles
import app.tfdn.lirelle.core.audio.narration.FileReadAlongMapStore
import app.tfdn.lirelle.core.audio.narration.ReadAlongMapStore
import app.tfdn.lirelle.core.library.ReadAlongRepository
import app.tfdn.lirelle.core.session.profile.ProfileCloser
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileFolders
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.multibindings.IntoSet
import java.io.File

/** The read-along maps of one profile. */
@Module
@InstallIn(ProfileComponent::class)
internal object NarrationModule {

    /**
     * In what the profile keeps, not its cache area: the system does not remove a map while its book is listened
     * to. A map says which books were listened to, and holds their text: it goes with the profile's folder.
     */
    @Provides
    @ProfileScoped
    fun fileReadAlongMapStore(folders: ProfileFolders, repository: ReadAlongRepository): FileReadAlongMapStore =
        FileReadAlongMapStore(File(folders.files, ListeningFiles.ReadAlong), repository)

    @Provides
    fun readAlongMapStore(store: FileReadAlongMapStore): ReadAlongMapStore = store

    /**
     * A map still being fetched when the profile closes is not kept: written later, it would make again the
     * folder of a profile that is being deleted.
     */
    @Provides
    @IntoSet
    fun keepNoMapAfterClose(store: FileReadAlongMapStore): ProfileCloser = ProfileCloser { store.close() }
}
