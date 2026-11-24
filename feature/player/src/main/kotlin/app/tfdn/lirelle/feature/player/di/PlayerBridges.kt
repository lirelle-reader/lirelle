package app.tfdn.lirelle.feature.player.di

import app.tfdn.lirelle.core.session.profile.CurrentProfile
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.entry
import app.tfdn.lirelle.feature.player.controller.PlayerController
import app.tfdn.lirelle.feature.player.controller.PlayerControllerScope
import app.tfdn.lirelle.feature.player.service.LastBookMark
import app.tfdn.lirelle.feature.player.service.PlaybackParts
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope

/** The player's objects of the open profile, for what Hilt cannot inject them into. */
@EntryPoint
@InstallIn(ProfileComponent::class)
interface PlayerProfileEntry {
    fun playerController(): PlayerController

    @PlayerControllerScope
    fun playerControllerScope(): CoroutineScope

    /**
     * Everything the playback service is made of, in one piece: the service takes this entry once and builds
     * itself from it, so it is never made of the objects of two profiles, nor of a profile that closed.
     */
    fun playbackParts(): PlaybackParts

    /** For the play button's receiver, which is nobody's to inject. */
    fun lastBookMark(): LastBookMark
}

/**
 * The way to the open profile for the play button's receiver, which Media3 makes and Hilt does not inject.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PlayerReceiverEntry {
    fun currentProfile(): CurrentProfile
}

/**
 * Gives view models the player's objects of the open profile. Unscoped, and in no component that outlives a
 * profile: each view model gets those of the profile open when it is made.
 *
 * The playback service has no such bridge: a bridge gives a screen that is leaving the objects of the profile
 * closed last, and each one asks for the profile anew. The service plays, so it takes one open profile, once
 * ([PlayerProfileEntry.playbackParts]).
 */
@Module
@InstallIn(ViewModelComponent::class)
internal object PlayerBridges {

    @Provides
    fun controller(current: CurrentProfile): PlayerController = current.entry<PlayerProfileEntry>().playerController()

    @Provides
    @PlayerControllerScope
    fun controllerScope(current: CurrentProfile): CoroutineScope =
        current.entry<PlayerProfileEntry>().playerControllerScope()
}
