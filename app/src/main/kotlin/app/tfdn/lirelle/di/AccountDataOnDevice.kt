package app.tfdn.lirelle.di

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.tfdn.lirelle.core.audio.cache.AudioCache
import app.tfdn.lirelle.core.audio.settings.PlayerDeviceSettings
import app.tfdn.lirelle.core.audio.settings.PlayerSettingsStore
import app.tfdn.lirelle.core.comics.settings.ComicDeviceSettings
import app.tfdn.lirelle.core.downloads.engine.DownloadRunner
import app.tfdn.lirelle.core.downloads.store.DownloadRegistry
import app.tfdn.lirelle.core.downloads.store.DownloadSettingsStore
import app.tfdn.lirelle.core.downloads.update.CheckClock
import app.tfdn.lirelle.core.library.LibraryPreferences
import app.tfdn.lirelle.core.network.AuthRepository
import app.tfdn.lirelle.core.network.OidcRedirectRelay
import app.tfdn.lirelle.core.network.di.AuthenticatedClient
import app.tfdn.lirelle.core.reader.settings.ReaderSettingsStore
import app.tfdn.lirelle.core.session.ProfileStore
import app.tfdn.lirelle.core.session.SessionStore
import app.tfdn.lirelle.core.session.profile.CurrentProfile
import app.tfdn.lirelle.core.session.profile.DeviceSignals
import app.tfdn.lirelle.core.session.profile.OpenProfiles
import app.tfdn.lirelle.core.session.profile.ProfileAvatars
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileFolders
import app.tfdn.lirelle.core.session.profile.ProfileGraphFactory
import app.tfdn.lirelle.feature.player.service.LastBookStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient

/**
 * What the device keeps whoever is signed in, and the way to the open profile, as the application's own graph
 * holds them. Only the tests of what a sign-out leaves behind, and of what two profiles share, read it: they
 * are worth nothing on a graph built for them.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AccountDataOnDevice {
    fun sessions(): SessionStore

    /** The registry itself: what it still lists once an account has left. */
    fun profileStore(): ProfileStore

    fun openProfiles(): OpenProfiles

    /** The sign-in itself, as the sign-in screens are given it: signing in while a profile is open. */
    fun auth(): AuthRepository

    fun oidcRedirects(): OidcRedirectRelay

    fun currentProfile(): CurrentProfile

    /** The pictures of the profiles' accounts, which outlive the objects of a profile that sleeps. */
    fun profileAvatars(): ProfileAvatars

    /** What makes a profile's objects: a test makes those of a second profile beside the open one's. */
    fun profileGraphFactory(): ProfileGraphFactory

    /** What the device tells the open profiles, and how many of their objects still listen. */
    fun deviceSignals(): DeviceSignals

    fun downloadSettings(): DownloadSettingsStore

    fun comicDeviceSettings(): ComicDeviceSettings

    fun readerSettings(): ReaderSettingsStore

    fun playerDeviceSettings(): PlayerDeviceSettings

    fun libraryPreferences(): LibraryPreferences
}

/**
 * What the open profile keeps that no screen asks for by itself, and where it lives, as the application's own
 * graph holds them. For the same tests, which reach the rest through the entry points the screens' bridges use.
 */
@EntryPoint
@InstallIn(ProfileComponent::class)
interface ProfileDataOnDevice {
    fun profileFolders(): ProfileFolders

    @OptIn(UnstableApi::class)
    fun audioCache(): AudioCache

    fun downloadRegistry(): DownloadRegistry

    fun downloadRunner(): DownloadRunner

    fun downloadCheckClock(): CheckClock

    /** The whole player settings, the speed of each of the profile's books among them. */
    fun playerSettingsStore(): PlayerSettingsStore

    fun lastBookStore(): LastBookStore

    /** The client that speaks to the profile's server as its account. */
    @AuthenticatedClient
    fun authenticatedClient(): OkHttpClient
}
