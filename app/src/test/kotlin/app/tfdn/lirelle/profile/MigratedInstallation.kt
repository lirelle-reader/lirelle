package app.tfdn.lirelle.profile

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import app.tfdn.lirelle.awaited
import app.tfdn.lirelle.core.audio.di.AudioProfileEntry
import app.tfdn.lirelle.core.audio.settings.PlayerSettings
import app.tfdn.lirelle.core.comics.settings.Background
import app.tfdn.lirelle.core.comics.settings.ComicSettings
import app.tfdn.lirelle.core.comics.settings.Fit
import app.tfdn.lirelle.core.comics.settings.TwoPages
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.store.DownloadSettings
import app.tfdn.lirelle.core.library.Presentation
import app.tfdn.lirelle.core.library.ViewPreferences
import app.tfdn.lirelle.core.library.di.LibraryProfileEntry
import app.tfdn.lirelle.core.reader.di.ReaderCoreProfileEntry
import app.tfdn.lirelle.core.reader.settings.BrightnessEdge
import app.tfdn.lirelle.core.reader.settings.MarginSize
import app.tfdn.lirelle.core.reader.settings.ReaderAlign
import app.tfdn.lirelle.core.reader.settings.ReaderFlow
import app.tfdn.lirelle.core.reader.settings.ReaderFont
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.core.reader.settings.ReaderTheme
import app.tfdn.lirelle.core.reader.settings.RunningLine
import app.tfdn.lirelle.core.reader.settings.RunningSlot
import app.tfdn.lirelle.core.session.CurrentDataFormat
import app.tfdn.lirelle.core.session.SessionState
import app.tfdn.lirelle.di.AppearanceOnDevice
import app.tfdn.lirelle.di.ProfileDataOnDevice
import app.tfdn.lirelle.feature.player.di.PlayerProfileEntry
import app.tfdn.lirelle.feature.settings.appearance.AppTheme
import app.tfdn.lirelle.signout.TestDevice
import app.tfdn.lirelle.until
import com.google.common.truth.Truth.assertWithMessage
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.flow.first

private const val BookSpeed = 1.5f
private const val BackSeconds = 45
private const val ForwardSeconds = 10
private const val FontStep = 5
private const val LineStep = 3
private const val Brightness = 0.4f

/**
 * What the application holds once an [OldInstallation] was migrated, read back through its real graph and its
 * real stores: the account the user left is the one profile, signed in, and the device's settings are as they
 * were; no store finds its file held by the migration.
 *
 * What the account kept on the device is not found again: the migration moves it into the profile's folders,
 * but it is of the format before the app named things by ids of its own, so the profile starts again from
 * empty folders when it opens. Where the migration put each file is the business of the migration's own tests
 * (`ProfileMigrationRestartTest`), which look at the files before any profile opens.
 */
internal class MigratedInstallation(private val context: Context) {

    val device = TestDevice(context)

    /** Waits for the profile made of the old session to be open, as the screens see it. @return its id. */
    suspend fun shownProfile(): String {
        val shown = awaited("the profile made of the old session is shown") {
            device.data.openProfiles().shown.value.takeIf { it.state is SessionState.SignedIn }
        }
        assertWithMessage("session").that(shown.state).isEqualTo(SessionState.SignedIn(OldSession))
        return checkNotNull(shown.profileId)
    }

    /**
     * The account of the old installation is the one profile [id], signed in, its data started again; what is
     * the device's is as it was, and nothing is left at the old places.
     *
     * @param untouched whether the old installation was written with the files that are the device's alone.
     */
    suspend fun assertAccountKept(id: String, untouched: Boolean = true) {
        assertOneReadyProfile(id)
        assertDataStartedAgain()
        assertDeviceSettingsKept()
        if (untouched) assertUntouched()
        assertOldPlacesGone()
    }

    /**
     * Nothing of what the old installation kept for the account is read by the profile's stores, and what was
     * moved into its folders is deleted once set aside.
     */
    private suspend fun assertDataStartedAgain() {
        assertWithMessage("format").that(device.data.profileStore().current.value?.dataFormat)
            .isEqualTo(CurrentDataFormat)
        val reading = device.profile<ReaderCoreProfileEntry>()
        assertWithMessage("position").that(reading.readingPositionStore().get(OldFileId)).isNull()
        assertWithMessage("positions to send").that(reading.readingOutbox().pending().positions).isEmpty()
        assertWithMessage("marks to send").that(reading.readerMarkOutbox().pending()).isEmpty()
        assertWithMessage("searches").that(device.profile<LibraryProfileEntry>().recentSearches().entries.first())
            .isEmpty()
        val audio = device.profile<AudioProfileEntry>()
        assertWithMessage("place").that(audio.audioPlaybackStore().get(OldBookId)).isNull()
        assertWithMessage("listening sessions to send").that(audio.audioListeningSessions().pending()).isEmpty()
        assertWithMessage("audio bookmarks to send").that(audio.audioBookmarkQueue().pending()).isEmpty()
        val kept = device.profile<ProfileDataOnDevice>()
        assertWithMessage("book speed").that(kept.playerSettingsStore().speedOf(OldBookId)).isNull()
        assertWithMessage("last book").that(kept.lastBookStore().get()).isNull()
        assertWithMessage("last book's mark").that(device.profile<PlayerProfileEntry>().lastBookMark().exists())
            .isFalse()
        assertWithMessage("downloads").that(kept.downloadRegistry().books.first()).isEmpty()
        until("what the profile's data was set aside as is deleted") {
            device.profileFolders().none { it.name.startsWith("old-data-") }
        }
        listOf("downloads", "readalong", "comics").forEach {
            assertWithMessage("files in $it").that(device.filesIn(it)).isEmpty()
        }
    }

    /** The settings that are the device's alone are as the old installation had them: none at its default. */
    private suspend fun assertUntouched() {
        assertWithMessage("reading settings").that(device.data.readerSettings().settings.first()).isEqualTo(
            ReaderSettings(
                theme = ReaderTheme.Sepia,
                font = ReaderFont.Serif,
                fontSizeStep = FontStep,
                lineHeightStep = LineStep,
                margins = MarginSize.Wide,
                align = ReaderAlign.Justify,
                hyphenate = false,
                flow = ReaderFlow.Scrolled,
                volumeKeys = true,
                brightnessEdge = BrightnessEdge.Right,
                brightness = Brightness,
                header = RunningLine(RunningSlot.BookTitle, RunningSlot.Nothing),
                footer = RunningLine(RunningSlot.Nothing, RunningSlot.BookPercent),
                footnotePanel = false,
                cornerBookmark = false,
                lastHighlightColour = "#22C55E",
            ),
        )
        assertWithMessage("download settings").that(device.data.downloadSettings().settings.first()).isEqualTo(
            DownloadSettings(allowMetered = true, notificationsAsked = true, setOf(DownloadFormat.Audiobook)),
        )
        val appearance = EntryPointAccessors.fromApplication(context, AppearanceOnDevice::class.java).appearance()
        assertWithMessage("theme").that(appearance.theme.first()).isEqualTo(AppTheme.Dark)
    }

    private suspend fun assertOneReadyProfile(id: String) {
        val store = device.data.profileStore()
        assertWithMessage("profiles listed").that(device.listed()).containsExactly(id)
        assertWithMessage("current").that(store.current.value?.id).isEqualTo(id)
        assertWithMessage("session").that(store.current.value?.session).isEqualTo(OldSession)
        assertWithMessage("being moved").that(store.beingMoved()).isEmpty()
        assertWithMessage("being removed").that(store.beingRemoved()).isEmpty()
        assertWithMessage("profile folders").that(profileIds()).containsExactly(id)
    }

    /**
     * The profiles that have a folder: what the migration set aside beside them is no profile's. From one look
     * at the folders: the sweep deletes what was set aside meanwhile, and between two looks a folder would be
     * seen by the first and not known as set aside by the second.
     */
    private fun profileIds(): List<String> =
        device.profileFolders().filterNot { it.wasSetAside() }.map { it.name }.distinct()

    /** The folders the migration set aside for the start's sweep, which are there until it has passed. */
    fun setAside(): List<File> = device.profileFolders().filter { it.wasSetAside() }

    private fun File.wasSetAside() = name.startsWith("old-")

    /** What is the device's is where it was, and as it was: the account's part alone left the files. */
    private suspend fun assertDeviceSettingsKept() {
        assertWithMessage("library's view").that(device.data.libraryPreferences().view("books").first())
            .isEqualTo(ViewPreferences(Presentation.List, sort = "title", descending = true))
        assertWithMessage("comics' display").that(device.data.comicDeviceSettings().shared.first())
            .isEqualTo(ComicSettings(fit = Fit.Width, twoPages = TwoPages.Always, background = Background.White))
        assertWithMessage("player's settings").that(device.data.playerDeviceSettings().settings.first()).isEqualTo(
            PlayerSettings(
                backSeconds = BackSeconds,
                forwardSeconds = ForwardSeconds,
                rewindOnResume = false,
                shakeToExtend = false,
                lastSpeed = BookSpeed,
            ),
        )
    }

    private fun assertOldPlacesGone() {
        val preferences = ScratchOwners.map { context.preferencesDataStoreFile(it) }
        val old = preferences + preferences.map { File(it.path + ".tmp") } +
            listOf("downloads", "readalong").map { File(context.filesDir, it) } +
            listOf("covers", "reader", "comics", "audio").map { File(context.cacheDir, it) }
        assertWithMessage("old places").that(old.filter { it.exists() }).isEmpty()
        assertWithMessage("old audio index").that(context.databaseList().filter { "exoplayer" in it }).isEmpty()
        assertWithMessage("old last book")
            .that(context.getSharedPreferences("player_last_book", Context.MODE_PRIVATE).all).isEmpty()
    }
}
