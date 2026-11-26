package app.tfdn.lirelle.feature.player.car

import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.testing.AndroidAutoCertificates
import app.tfdn.lirelle.core.testing.AndroidAutoPackage
import app.tfdn.lirelle.core.testing.InstalledAndroidAuto
import app.tfdn.lirelle.core.testing.SignedPackages
import app.tfdn.lirelle.feature.player.service.FakeEngine
import app.tfdn.lirelle.feature.player.service.PlayerCommands
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

private const val Gearhead = AndroidAutoPackage

/** A library service whose session answers with the player's commands, over a scripted library. */
@androidx.annotation.OptIn(UnstableApi::class)
class BrowsedService : MediaLibraryService() {
    val shelves = FakeCarShelves()
    lateinit var commands: PlayerCommands
    lateinit var session: MediaLibrarySession

    override fun onCreate() {
        super.onCreate()
        val items = CarItems(resources, "app.tfdn.lirelle.covers", "profile-1")
        val browser =
            CarBrowser(shelves, items, NoticeAsLine(CarTexts(resources)), CoroutineScope(Dispatchers.Unconfined))
        val cars = CarControllers.androidAuto(packageManager)
        commands = PlayerCommands("app.tfdn.lirelle", handle = {}, buttons = { emptyList() }, cars, browser)
        session = MediaLibrarySession.Builder(this, FakeEngine(), commands).setId("browsed-test").build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onDestroy() {
        session.release()
        super.onDestroy()
    }
}

/** Who may browse: the session's library callbacks answer a car from its browser, and refuse anyone else. */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
@Config(shadows = [SignedPackages::class])
class CarLibraryCallbackTest {

    @get:Rule
    val androidAuto = InstalledAndroidAuto()

    private val controller = Robolectric.buildService(BrowsedService::class.java).create()
    private val service = controller.get()
    private val commands get() = service.commands
    private val session get() = service.session

    @After
    fun end() {
        controller.destroy()
    }

    private fun from(packageName: String) =
        MediaSession.ControllerInfo.createTestOnlyControllerInfo(packageName, 0, 0, 0, 0, false, Bundle.EMPTY, false)

    private val car = from(Gearhead)

    @Test
    fun `a car is given the root, its tabs, an item and a search`() {
        service.shelves.places[CarPlace.Root] = CarAnswer.Nodes(CarPlace.Tab.entries.map(CarNode::Tab))
        service.shelves.searched = CarAnswer.Nodes(listOf(CarNode.Book(BookId("1"), "The Long Road", null)))

        assertThat(commands.onGetLibraryRoot(session, car, null).get().value?.mediaId).isEqualTo("root")
        assertThat(commands.onGetChildren(session, car, "root", 0, 100, null).get().value?.map { it.mediaId })
            .containsExactly("tab/home", "tab/downloaded", "tab/audiobooks", "tab/series").inOrder()
        assertThat(commands.onGetItem(session, car, "tab/home").get().value?.mediaId).isEqualTo("tab/home")
        assertThat(commands.onSearch(session, car, "road", null).get().resultCode)
            .isEqualTo(LibraryResult.RESULT_SUCCESS)
        assertThat(commands.onGetSearchResult(session, car, "road", 0, 100, null).get().value?.map { it.mediaId })
            .containsExactly("book/1")
        assertThat(service.shelves.searches).containsExactly("road")
    }

    @Test
    fun `a car may subscribe to a tab, as Media3 checks it by its item`() {
        assertThat(commands.onSubscribe(session, car, "tab/home", null).get().resultCode)
            .isEqualTo(LibraryResult.RESULT_SUCCESS)
        assertThat(commands.onSubscribe(session, car, "elsewhere", null).get().resultCode)
            .isNotEqualTo(LibraryResult.RESULT_SUCCESS)
    }

    @Test
    fun `a car that leaves has its search forgotten`() {
        service.shelves.searched = CarAnswer.Nodes(emptyList())
        commands.onSearch(session, car, "road", null).get()

        commands.onDisconnected(session, car)
        commands.onGetSearchResult(session, car, "road", 0, 100, null).get()

        assertThat(service.shelves.searches).containsExactly("road", "road")
    }

    /** The answers to everything a browser asks, asked by [other]. */
    private fun codesFor(other: MediaSession.ControllerInfo): Set<Int> = setOf(
        commands.onGetLibraryRoot(session, other, null).get().resultCode,
        commands.onGetChildren(session, other, "root", 0, 100, null).get().resultCode,
        commands.onGetItem(session, other, "tab/home").get().resultCode,
        commands.onSearch(session, other, "road", null).get().resultCode,
        commands.onGetSearchResult(session, other, "road", 0, 100, null).get().resultCode,
        commands.onSubscribe(session, other, "tab/home", null).get().resultCode,
    )

    @Test
    fun `anyone but a car browses nothing, and the library is asked nothing`() {
        listOf(from("com.example.watch"), from("com.android.systemui"), from("app.tfdn.lirelle")).forEach { other ->
            assertThat(codesFor(other)).containsExactly(SessionError.ERROR_PERMISSION_DENIED)
        }
        assertThat(service.shelves.asked).isEmpty()
        assertThat(service.shelves.searches).isEmpty()
    }

    @Test
    fun `an app that took Android Auto's name browses nothing - Google did not sign it`() {
        SignedPackages.sign(Gearhead, AndroidAutoCertificates.Stranger)
        assertThat(codesFor(car)).containsExactly(SessionError.ERROR_PERMISSION_DENIED)
        // The name alone, where Android Auto is not installed.
        SignedPackages.forget()
        assertThat(codesFor(car)).containsExactly(SessionError.ERROR_PERMISSION_DENIED)

        assertThat(service.shelves.asked).isEmpty()
        assertThat(service.shelves.searches).isEmpty()
    }

    @Test
    fun `without a browser, as before a car could browse, nothing is browsed`() {
        val cars = CarControllers.androidAuto(service.packageManager)
        val plain = PlayerCommands("app.tfdn.lirelle", handle = {}, buttons = { emptyList() }, cars)

        assertThat(plain.onGetLibraryRoot(session, car, null).get().resultCode)
            .isEqualTo(SessionError.ERROR_NOT_SUPPORTED)
        assertThat(plain.onGetChildren(session, car, "root", 0, 100, null).get().resultCode)
            .isEqualTo(SessionError.ERROR_NOT_SUPPORTED)
    }
}
