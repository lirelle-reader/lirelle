package app.tfdn.lirelle.di

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.tfdn.lirelle.MainActivity
import app.tfdn.lirelle.core.downloads.job.DownloadsIntents
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

/** Not the player's requests (0 and 1): the same activity, another pending intent. */
private const val DownloadsRequest = 2

@Module
@InstallIn(SingletonComponent::class)
object DownloadsIntentsModule {
    /**
     * A tap on a notification of the downloads opens the app as the launcher does (the one activity, brought
     * back as it is), asking for the Downloads destination of the profile the notification is of.
     *
     * The profile is also the intent's identifier: the system keeps one pending intent for intents it holds
     * equal, and extras do not tell two apart. Without it, the notification of a profile left a moment ago
     * would be handed the extras of the profile opened since, and name that one.
     */
    @Provides
    fun downloadsIntents(@ApplicationContext context: Context): DownloadsIntents = DownloadsIntents { profileId ->
        val open = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setIdentifier(profileId)
            .putExtra(DownloadsIntents.EXTRA_OPEN_DOWNLOADS, true)
            .putExtra(DownloadsIntents.EXTRA_OPEN_DOWNLOADS_PROFILE_ID, profileId)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        PendingIntent.getActivity(context, DownloadsRequest, open, flags)
    }
}
