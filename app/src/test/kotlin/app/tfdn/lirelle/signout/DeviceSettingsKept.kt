package app.tfdn.lirelle.signout

import android.content.Context
import app.tfdn.lirelle.core.audio.settings.PlayerSettings
import app.tfdn.lirelle.core.comics.settings.Background
import app.tfdn.lirelle.core.comics.settings.ComicSettings
import app.tfdn.lirelle.core.comics.settings.Fit
import app.tfdn.lirelle.core.comics.settings.TwoPages
import app.tfdn.lirelle.core.downloads.model.DownloadFormat
import app.tfdn.lirelle.core.downloads.store.DownloadSettings
import app.tfdn.lirelle.core.library.Presentation
import app.tfdn.lirelle.core.library.ViewPreferences
import app.tfdn.lirelle.core.reader.settings.MarginSize
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.core.reader.settings.ReaderTheme
import app.tfdn.lirelle.di.AccountDataOnDevice
import app.tfdn.lirelle.di.AppearanceOnDevice
import app.tfdn.lirelle.feature.settings.appearance.AppTheme
import com.google.common.truth.Truth.assertWithMessage
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first

private const val BackSeconds = 45
private const val ForwardSeconds = 10

/** The list view whose layout is changed: any one of them, they are kept the same way. */
private const val View = "books"

/**
 * Every setting that is the device's, set to something else than what a new installation has, and read back
 * once an account has left: none of them is in a profile's folder, so none may go with one.
 */
internal class DeviceSettingsKept(context: Context, private val data: AccountDataOnDevice) {

    private val appearance = EntryPointAccessors.fromApplication(context, AppearanceOnDevice::class.java).appearance()

    private val downloads = DownloadSettings(
        allowMetered = true,
        notificationsAsked = true,
        seriesFormats = setOf(DownloadFormat.Text, DownloadFormat.Audiobook),
    )
    private val comics = ComicSettings(fit = Fit.Width, twoPages = TwoPages.Always, background = Background.White)
    private val reader = ReaderSettings(
        theme = ReaderTheme.Sepia,
        margins = MarginSize.Wide,
        hyphenate = false,
        volumeKeys = true,
    )
    private val player = PlayerSettings(
        backSeconds = BackSeconds,
        forwardSeconds = ForwardSeconds,
        rewindOnResume = false,
        shakeToExtend = false,
    )
    private val view = ViewPreferences(Presentation.List, sort = "title", descending = true)

    /** Changes each of them, as its settings screen does. */
    suspend fun change() {
        appearance.setTheme(AppTheme.Dark)
        data.downloadSettings().update { downloads }
        data.comicDeviceSettings().apply {
            setFit(comics.fit)
            setTwoPages(comics.twoPages)
            setBackground(comics.background)
        }
        data.readerSettings().update { reader }
        data.playerDeviceSettings().update { player }
        data.libraryPreferences().update(View) { view }
    }

    /**
     * Each of them is as [change] left it.
     *
     * @param lastSpeed the speed of a new book, which the device keeps too: the one last chosen for a book,
     *   whoever chose it.
     */
    suspend fun assertKept(lastSpeed: Float) {
        assertWithMessage("app theme").that(appearance.theme.first()).isEqualTo(AppTheme.Dark)
        assertWithMessage("downloads' settings").that(data.downloadSettings().settings.first()).isEqualTo(downloads)
        assertWithMessage("comics' display").that(data.comicDeviceSettings().shared.first()).isEqualTo(comics)
        assertWithMessage("reader's settings").that(data.readerSettings().settings.first()).isEqualTo(reader)
        assertWithMessage("player's settings").that(data.playerDeviceSettings().settings.first())
            .isEqualTo(player.copy(lastSpeed = lastSpeed))
        assertWithMessage("library's view").that(data.libraryPreferences().view(View).first()).isEqualTo(view)
    }
}
