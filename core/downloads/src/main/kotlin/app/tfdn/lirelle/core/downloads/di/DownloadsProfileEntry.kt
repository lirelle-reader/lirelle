package app.tfdn.lirelle.core.downloads.di

import app.tfdn.lirelle.core.downloads.ClosingDeletions
import app.tfdn.lirelle.core.downloads.DownloadRemovals
import app.tfdn.lirelle.core.downloads.DownloadSurroundings
import app.tfdn.lirelle.core.downloads.Downloads
import app.tfdn.lirelle.core.downloads.job.DownloadJobWork
import app.tfdn.lirelle.core.downloads.job.DownloadNotices
import app.tfdn.lirelle.core.downloads.job.DownloadNotifications
import app.tfdn.lirelle.core.downloads.offline.KeptBookData
import app.tfdn.lirelle.core.downloads.store.DownloadFiles
import app.tfdn.lirelle.core.downloads.update.DownloadUpdates
import app.tfdn.lirelle.core.library.offline.DownloadMarks
import app.tfdn.lirelle.core.session.profile.ProfileComponent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn

/** The downloads of the open profile, for what Hilt cannot inject them into. */
@EntryPoint
@InstallIn(ProfileComponent::class)
interface DownloadsProfileEntry {
    fun downloadsQueue(): Downloads

    fun downloadRemovals(): DownloadRemovals

    fun downloadClosingDeletions(): ClosingDeletions

    fun downloadSurroundings(): DownloadSurroundings

    fun downloadUpdates(): DownloadUpdates

    fun downloadFiles(): DownloadFiles

    fun keptBookData(): KeptBookData

    fun downloadMarks(): DownloadMarks

    /** For the system's job, which runs for the profile open when it starts and for no other. */
    fun downloadJobWork(): DownloadJobWork

    /** What that job shows while it runs. */
    fun downloadNotices(): DownloadNotices

    /** What that job shows before it knows what it fetches. */
    fun downloadNotifications(): DownloadNotifications
}
