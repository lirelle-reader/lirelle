package app.tfdn.lirelle.core.downloads.di

import android.content.Context
import app.tfdn.lirelle.core.downloads.job.DownloadNotifications
import app.tfdn.lirelle.core.downloads.job.DownloadsIntents
import app.tfdn.lirelle.core.downloads.job.QueueNotices
import app.tfdn.lirelle.core.session.profile.ProfileCloser
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import app.tfdn.lirelle.core.session.profile.ProfileSession
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.multibindings.IntoSet

/** What a profile's downloads say in notifications: each one is of that profile, and goes with it. */
@Module
@InstallIn(ProfileComponent::class)
internal object DownloadsNoticesModule {

    /** Made with the profile's id: the buttons of its notifications say whose they are. */
    @Provides
    fun downloadNotifications(
        @ApplicationContext context: Context,
        session: ProfileSession,
        intents: DownloadsIntents,
    ): DownloadNotifications = DownloadNotifications(context, session.profileId, intents)

    /**
     * The profile closes: what its queue posted is taken away. The job's own notification goes with the job
     * (`cancelJobAtClose`); these two are posted outside it, and would stay for the next account to tap.
     */
    @Provides
    @IntoSet
    fun dismissQueueNoticesAtClose(notices: QueueNotices): ProfileCloser = ProfileCloser { notices.close() }
}
