package app.tfdn.lirelle.core.downloads.di

import app.tfdn.lirelle.core.downloads.DownloadSurroundings
import app.tfdn.lirelle.core.downloads.store.DownloadFiles
import app.tfdn.lirelle.core.downloads.store.DownloadSettingsStore
import app.tfdn.lirelle.core.library.net.ProfileNetwork
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileSession
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Module
@InstallIn(ProfileComponent::class)
internal object DownloadsSurroundingsModule {

    /**
     * The permission is the profile's own account's, followed as the server changes it; the network is the
     * profile's too: none while its session waits for a sign-in.
     */
    @Provides
    fun downloadSurroundings(
        network: ProfileNetwork,
        session: ProfileSession,
        settings: DownloadSettingsStore,
        files: DownloadFiles,
    ): DownloadSurroundings = DownloadSurroundings(
        network = network.state,
        canDownload = session.session.map { it.user.canDownload }.distinctUntilChanged(),
        settings = settings,
        freeBytes = files::freeBytes,
    )
}
