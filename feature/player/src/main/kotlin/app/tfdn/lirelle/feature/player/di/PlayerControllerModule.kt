package app.tfdn.lirelle.feature.player.di

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileLife
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import app.tfdn.lirelle.feature.player.controller.MediaControllerConnection
import app.tfdn.lirelle.feature.player.controller.MediaSessionConnector
import app.tfdn.lirelle.feature.player.controller.PlayerConnection
import app.tfdn.lirelle.feature.player.controller.PlayerControllerScope
import app.tfdn.lirelle.feature.player.controller.SectionNames
import app.tfdn.lirelle.feature.player.service.NarrationTexts
import app.tfdn.lirelle.feature.player.service.PlaybackServiceRunning
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

private const val Tag = "PlayerController"

/**
 * The app's side of the player, for one profile: the controller remembers the books of its account, so
 * another profile gets another, and the connection to the service is let go when the profile closes.
 */
@Module
@InstallIn(ProfileComponent::class)
internal object PlayerControllerModule {

    /** As long as the profile, on the main thread, the MediaController's; a failure in it is logged, not fatal. */
    @Provides
    @ProfileScoped
    @PlayerControllerScope
    fun controllerScope(@ProfileLife life: CoroutineScope): CoroutineScope = CoroutineScope(
        life.coroutineContext + SupervisorJob(life.coroutineContext[Job]) + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, failure -> Log.w(Tag, "The player controller failed", failure) },
    )

    /** One connection to the playback service for the profile's screens. */
    @Provides
    @ProfileScoped
    fun playerConnection(
        @ApplicationContext context: Context,
        @PlayerControllerScope scope: CoroutineScope,
    ): PlayerConnection =
        MediaControllerConnection(
            MediaSessionConnector(context),
            scope,
            ContextCompat.getMainExecutor(context),
            PlaybackServiceRunning.running,
        )
}

/** What the player takes from the device, whoever is signed in. */
@Module
@InstallIn(SingletonComponent::class)
internal object PlayerDeviceModule {

    /** A narrated section without a title is named as the notification names it, in the device's language. */
    @Provides
    fun sectionNames(@ApplicationContext context: Context): SectionNames =
        SectionNames { NarrationTexts(context.resources).section(it) }
}
