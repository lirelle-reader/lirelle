package app.tfdn.lirelle.shell

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.R
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.library.offline.DownloadMark
import app.tfdn.lirelle.core.ui.LocalDeviceOnline
import app.tfdn.lirelle.core.ui.LocalDownloadMarks
import app.tfdn.lirelle.core.ui.LocalOpenDownloads
import app.tfdn.lirelle.navigation.BookDestination
import app.tfdn.lirelle.navigation.DownloadsDestination
import app.tfdn.lirelle.navigation.DownloadsRequests
import app.tfdn.lirelle.navigation.GenreDestination
import app.tfdn.lirelle.navigation.HomeDestination
import app.tfdn.lirelle.navigation.LibraryDestination
import app.tfdn.lirelle.navigation.SearchBooksDestination
import app.tfdn.lirelle.navigation.SearchDestination
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ShellTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val downloadsRequests = DownloadsRequests { OpenProfile }

    /**
     * @param online whether the profile has a network, as the shell is told; null when it is not.
     * @param deviceOffline whether the device has none, as the shell is told; as [online] says when not given.
     */
    private fun show(
        width: WindowWidthClass,
        online: StateFlow<Boolean>? = null,
        deviceOffline: Boolean? = null,
        home: NavGraphBuilder.(NavHostController) -> Unit = { stubHome(it) },
    ) {
        val startOffline = deviceOffline ?: (online?.value == false)
        compose.setContent { Shown(width, online, startOffline, home) }
    }

    @Composable
    private fun Shown(
        width: WindowWidthClass,
        online: StateFlow<Boolean>?,
        startOffline: Boolean,
        home: NavGraphBuilder.(NavHostController) -> Unit,
    ) {
        LirelleTheme {
            CompositionLocalProvider(LocalWindowWidthClass provides width) {
                Shell(
                    account = { onBack ->
                        Text(
                            text = "account",
                            modifier = Modifier
                                .testTag("account-slot")
                                .clickable(onClick = onBack),
                        )
                    },
                    home = home,
                    library = { stubLibrary(it) },
                    search = { stubSearch(it) },
                    downloads = { stubDownloads(it) },
                    downloadsRequests = downloadsRequests,
                    online = online,
                    startOffline = startOffline,
                )
            }
        }
    }

    /** Two clicks with no frame in between, as a fast double tap lands on the device. */
    private fun doubleTap(node: SemanticsNodeInteraction) {
        val click = checkNotNull(node.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.runOnUiThread {
            click()
            click()
        }
        compose.waitForIdle()
    }

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun openAccount() {
        compose.onNodeWithTag("nav-more").performClick()
        compose.onNodeWithText(context.getString(R.string.more_account)).performClick()
        compose.onNodeWithTag("account-slot").assertIsDisplayed()
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `compact width lays the destinations out in a bottom bar`() {
        show(WindowWidthClass.Compact)

        assertThat(bounds("nav-home").top).isEqualTo(bounds("nav-library").top)
        assertThat(bounds("nav-library").left).isGreaterThan(bounds("nav-home").left)
    }

    @Test
    @Config(qualifiers = "w700dp-h1000dp")
    fun `medium width lays the destinations out in a rail`() {
        show(WindowWidthClass.Medium)

        assertThat(bounds("nav-home").left).isEqualTo(bounds("nav-library").left)
        assertThat(bounds("nav-library").top).isGreaterThan(bounds("nav-home").top)
    }

    @Test
    @Config(qualifiers = "w1000dp-h1600dp")
    fun `expanded width lays the destinations out in a rail`() {
        show(WindowWidthClass.Expanded)

        assertThat(bounds("nav-home").left).isEqualTo(bounds("nav-library").left)
        assertThat(bounds("nav-library").top).isGreaterThan(bounds("nav-home").top)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `shows the five destinations and starts on Home`() {
        show(WindowWidthClass.Compact)

        TopLevelDestination.entries.forEach { compose.onNodeWithTag(it.testTag).assertIsDisplayed() }
        compose.onNodeWithText("home-root").assertIsDisplayed()
        compose.onNodeWithTag("nav-home").assertIsSelected()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `selecting a destination shows its screen`() {
        show(WindowWidthClass.Compact)

        compose.onNodeWithTag("nav-library").performClick()

        compose.onNodeWithText("library-root").assertIsDisplayed()
        compose.onNodeWithText("home-root").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `the Library destination shows the library graph`() {
        show(WindowWidthClass.Compact)

        compose.onNodeWithTag(TopLevelDestination.Library.testTag).performClick()

        compose.onNodeWithText("library-root").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `a book opens inside the Library, with the navigation still shown`() {
        show(WindowWidthClass.Compact)
        compose.onNodeWithTag(TopLevelDestination.Library.testTag).performClick()

        compose.onNodeWithText("library-root").performClick()

        compose.onNodeWithText("book-7").assertIsDisplayed()
        compose.onNodeWithTag(TopLevelDestination.Library.testTag).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `leaving the Library and coming back returns to the book, and tapping Library again to its root`() {
        show(WindowWidthClass.Compact)
        compose.onNodeWithTag(TopLevelDestination.Library.testTag).performClick()
        compose.onNodeWithText("library-root").performClick()

        compose.onNodeWithTag(TopLevelDestination.Home.testTag).performClick()
        compose.onNodeWithTag(TopLevelDestination.Library.testTag).performClick()
        compose.onNodeWithText("book-7").assertIsDisplayed()

        compose.onNodeWithTag(TopLevelDestination.Library.testTag).performClick()
        compose.onNodeWithText("library-root").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `More leads to the account screen`() {
        show(WindowWidthClass.Compact)

        compose.onNodeWithTag("nav-more").performClick()
        compose.onNodeWithText(context.getString(R.string.more_account)).performClick()

        compose.onNodeWithTag("account-slot").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `a destination keeps its place when the user comes back to it`() {
        show(WindowWidthClass.Compact)
        compose.onNodeWithTag("nav-more").performClick()
        compose.onNodeWithText(context.getString(R.string.more_account)).performClick()

        compose.onNodeWithTag("nav-home").performClick()
        compose.onNodeWithTag("nav-more").performClick()

        compose.onNodeWithTag("account-slot").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `back from the account screen returns to More`() {
        show(WindowWidthClass.Compact)
        openAccount()

        compose.onNodeWithTag("account-slot").performClick()

        compose.onNodeWithText(context.getString(R.string.more_account)).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `tapping the account row twice opens the account screen once`() {
        show(WindowWidthClass.Compact)
        compose.onNodeWithTag("nav-more").performClick()

        doubleTap(compose.onNodeWithText(context.getString(R.string.more_account)))
        compose.onNodeWithTag("account-slot").assertIsDisplayed()
        pressBack()

        compose.onNodeWithTag("account-slot").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.more_account)).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `tapping back twice on the account screen does not leave More`() {
        show(WindowWidthClass.Compact)
        openAccount()

        doubleTap(compose.onNodeWithTag("account-slot"))

        compose.onNodeWithText(context.getString(R.string.more_account)).assertIsDisplayed()
        compose.onNodeWithText("home-root").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `tapping the selected destination again returns it to its first screen`() {
        show(WindowWidthClass.Compact)
        openAccount()

        compose.onNodeWithTag("nav-more").performClick()

        compose.onNodeWithTag("account-slot").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.more_account)).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `tapping the selected destination again on its first screen changes nothing`() {
        show(WindowWidthClass.Compact)
        compose.onNodeWithTag("nav-more").performClick()

        compose.onNodeWithTag("nav-more").performClick()
        compose.onNodeWithText(context.getString(R.string.more_account)).assertIsDisplayed()
        pressBack()

        // More was not stacked on itself: one back press leaves it for Home.
        compose.onNodeWithText("home-root").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `a book opens inside Downloads, with the navigation still shown`() {
        show(WindowWidthClass.Compact)
        compose.onNodeWithTag("nav-downloads").performClick()

        compose.onNodeWithText("downloads-root").performClick()

        compose.onNodeWithText("downloads-book-7").assertIsDisplayed()
        compose.onNodeWithTag("nav-downloads").assertIsSelected()
        pressBack()
        compose.onNodeWithText("downloads-root").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `Downloads leads to the Library`() {
        show(WindowWidthClass.Compact)
        compose.onNodeWithTag("nav-downloads").performClick()

        compose.onNodeWithText("browse-library").performClick()

        compose.onNodeWithText("library-root").assertIsDisplayed()
        compose.onNodeWithTag("nav-library").assertIsSelected()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `without a network the shell starts on Downloads`() {
        show(WindowWidthClass.Compact, online = MutableStateFlow(false))

        compose.onNodeWithText("downloads-root").assertIsDisplayed()
        compose.onNodeWithTag("nav-downloads").assertIsSelected()
        compose.onNodeWithText("home-root").assertDoesNotExist()
        // The other destinations are one tap away, and Downloads is where Back ends.
        compose.onNodeWithTag("nav-home").performClick()
        compose.onNodeWithText("home-root").assertIsDisplayed()
        pressBack()
        compose.onNodeWithText("downloads-root").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `a profile without a network on a device that has one starts on Home`() {
        // Its session waits for a sign-in: Home says so, the downloaded books do not.
        show(WindowWidthClass.Compact, online = MutableStateFlow(false), deviceOffline = false)

        compose.onNodeWithText("home-root").assertIsDisplayed()
        compose.onNodeWithTag("nav-home").assertIsSelected()
        compose.onNodeWithText("downloads-root").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `a profile without a network on a device without one starts on Downloads`() {
        show(WindowWidthClass.Compact, online = MutableStateFlow(false), deviceOffline = true)

        compose.onNodeWithText("downloads-root").assertIsDisplayed()
        compose.onNodeWithTag("nav-downloads").assertIsSelected()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `with a network the shell starts on Home`() {
        show(WindowWidthClass.Compact, online = MutableStateFlow(true))

        compose.onNodeWithText("home-root").assertIsDisplayed()
        compose.onNodeWithTag("nav-home").assertIsSelected()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `a network lost later leaves the screen in front where it is`() {
        val online = MutableStateFlow(true)
        show(WindowWidthClass.Compact, online)
        compose.onNodeWithTag("nav-library").performClick()

        online.value = false
        compose.waitForIdle()

        compose.onNodeWithText("library-root").assertIsDisplayed()
        compose.onNodeWithTag("nav-library").assertIsSelected()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `a shell made again from its saved state does not start again on Downloads`() {
        val online = MutableStateFlow(true)
        var deviceOffline by mutableStateOf(false)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Shown(WindowWidthClass.Compact, online, deviceOffline) { stubHome(it) } }
        compose.onNodeWithTag("nav-library").performClick()
        online.value = false
        deviceOffline = true

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("library-root").assertIsDisplayed()
        pressBack()
        compose.onNodeWithText("home-root").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `a shell started on Downloads and made again keeps Downloads as its start`() {
        val online = MutableStateFlow(false)
        var deviceOffline by mutableStateOf(true)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Shown(WindowWidthClass.Compact, online, deviceOffline) { stubHome(it) } }
        compose.onNodeWithTag("nav-home").performClick()
        online.value = true
        deviceOffline = false

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("home-root").assertIsDisplayed()
        pressBack()
        compose.onNodeWithText("downloads-root").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `the Downloads destination asked for from outside is selected, once`() {
        show(WindowWidthClass.Compact)

        compose.runOnUiThread { downloadsRequests.open() }
        compose.waitForIdle()

        compose.onNodeWithText("downloads-root").assertIsDisplayed()
        compose.onNodeWithTag("nav-downloads").assertIsSelected()
        assertThat(downloadsRequests.pending).isFalse()
        // The request is over: leaving Downloads does not bring it back.
        compose.onNodeWithTag("nav-home").performClick()
        compose.onNodeWithText("home-root").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `the Downloads destination asked for by another profile's notification is not selected`() {
        show(WindowWidthClass.Compact)

        compose.runOnUiThread { downloadsRequests.open("another-profile") }
        compose.waitForIdle()

        compose.onNodeWithText("home-root").assertIsDisplayed()
        compose.onNodeWithTag("nav-home").assertIsSelected()
        assertThat(downloadsRequests.pending).isFalse()

        // The open profile's own notification is followed.
        compose.runOnUiThread { downloadsRequests.open(OpenProfile) }
        compose.waitForIdle()
        compose.onNodeWithText("downloads-root").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `Downloads asked for before the shell exists is where it opens`() {
        downloadsRequests.open()

        show(WindowWidthClass.Compact)

        compose.onNodeWithText("downloads-root").assertIsDisplayed()
        assertThat(downloadsRequests.pending).isFalse()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `Downloads asked for while a book of Downloads is in front goes back to its first screen`() {
        show(WindowWidthClass.Compact)
        compose.onNodeWithTag("nav-downloads").performClick()
        compose.onNodeWithText("downloads-root").performClick()

        compose.runOnUiThread { downloadsRequests.open() }
        compose.waitForIdle()

        compose.onNodeWithText("downloads-root").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `the screens of the shell are given the way to the downloads and the state of the network`() {
        val online = MutableStateFlow(true)
        var seen: StateFlow<Boolean>? = null
        show(WindowWidthClass.Compact, online) {
            composable<HomeDestination> {
                seen = LocalDeviceOnline.current
                val openDownloads = checkNotNull(LocalOpenDownloads.current)
                Text("see-downloads", Modifier.clickable(onClick = openDownloads))
            }
        }

        compose.onNodeWithText("see-downloads").performClick()

        compose.onNodeWithText("downloads-root").assertIsDisplayed()
        compose.onNodeWithTag("nav-downloads").assertIsSelected()
        assertThat(seen).isSameInstanceAs(online)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `the screens of the shell are given the marks of the books kept on the device`() {
        val marks = MutableStateFlow(mapOf(BookId("1") to DownloadMark.Kept))
        var seen: StateFlow<Map<BookId, DownloadMark>>? = null
        compose.setContent {
            LirelleTheme {
                Shell(
                    account = {},
                    home = {
                        composable<HomeDestination> {
                            seen = LocalDownloadMarks.current
                            Text("home-root")
                        }
                    },
                    library = { stubLibrary(it) },
                    search = { stubSearch(it) },
                    marks = marks,
                )
            }
        }

        compose.onNodeWithText("home-root").assertIsDisplayed()
        assertThat(seen).isSameInstanceAs(marks)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `a shell that is given no marks gives none to its screens`() {
        var seen: StateFlow<Map<BookId, DownloadMark>>? = MutableStateFlow(emptyMap())
        show(WindowWidthClass.Compact) {
            composable<HomeDestination> {
                seen = LocalDownloadMarks.current
                Text("home-root")
            }
        }

        compose.onNodeWithText("home-root").assertIsDisplayed()
        assertThat(seen).isNull()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `a detail opened from Home stays in the Home tab`() {
        show(WindowWidthClass.Compact)

        compose.onNodeWithText("home-root").performClick()

        compose.onNodeWithText("home-book-7").assertIsDisplayed()
        compose.onNodeWithTag("nav-home").assertIsSelected()
        pressBack()
        compose.onNodeWithText("home-root").assertIsDisplayed()
        compose.onNodeWithTag("nav-home").assertIsSelected()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `a detail opened from Search stays in the Search tab`() {
        show(WindowWidthClass.Compact)
        compose.onNodeWithTag("nav-search").performClick()

        compose.onNodeWithText("search-root").performClick()

        compose.onNodeWithText("search-book-7").assertIsDisplayed()
        compose.onNodeWithTag("nav-search").assertIsSelected()
        pressBack()
        compose.onNodeWithText("search-root").assertIsDisplayed()
        compose.onNodeWithTag("nav-search").assertIsSelected()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `a detail opened from another detail stays in its tab`() {
        show(WindowWidthClass.Compact)
        compose.onNodeWithTag("nav-search").performClick()
        compose.onNodeWithText("search-root").performClick()

        compose.onNodeWithText("search-book-7").performClick()

        compose.onNodeWithText("search-book-8").assertIsDisplayed()
        compose.onNodeWithTag("nav-search").assertIsSelected()
        pressBack()
        compose.onNodeWithText("search-book-7").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `a route carries a text with awkward characters unchanged`() {
        val awkward = "SF / Fantasy 100% #1? \"quoted\" é😀"
        lateinit var navController: NavHostController
        show(WindowWidthClass.Compact) { controller ->
            navController = controller
            stubHome(controller)
            composable<GenreDestination> { Text("genre=" + it.toRoute<GenreDestination>().name) }
            composable<SearchBooksDestination> { Text("books=" + it.toRoute<SearchBooksDestination>().text) }
        }

        compose.runOnUiThread { navController.navigate(GenreDestination(awkward)) }
        compose.onNodeWithText("genre=$awkward").assertIsDisplayed()

        compose.runOnUiThread { navController.navigate(SearchBooksDestination(awkward)) }
        compose.onNodeWithText("books=$awkward").assertIsDisplayed()
        pressBack()
        compose.onNodeWithText("genre=$awkward").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `each tab keeps its own stack`() {
        show(WindowWidthClass.Compact)
        compose.onNodeWithText("home-root").performClick()
        compose.onNodeWithTag("nav-library").performClick()
        compose.onNodeWithText("library-root").performClick()
        compose.onNodeWithTag("nav-search").performClick()
        compose.onNodeWithText("search-root").performClick()
        compose.onNodeWithText("search-book-7").performClick()

        compose.onNodeWithTag("nav-home").performClick()
        compose.onNodeWithText("home-book-7").assertIsDisplayed()
        compose.onNodeWithTag("nav-home").assertIsSelected()

        compose.onNodeWithTag("nav-library").performClick()
        compose.onNodeWithText("book-7").assertIsDisplayed()
        compose.onNodeWithTag("nav-library").assertIsSelected()

        compose.onNodeWithTag("nav-search").performClick()
        compose.onNodeWithText("search-book-8").assertIsDisplayed()
        compose.onNodeWithTag("nav-search").assertIsSelected()
        pressBack()
        compose.onNodeWithText("search-book-7").assertIsDisplayed()

        compose.onNodeWithTag("nav-home").performClick()
        pressBack()
        compose.onNodeWithText("home-root").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `tapping Home again returns it to its first screen`() {
        show(WindowWidthClass.Compact)
        compose.onNodeWithText("home-root").performClick()

        compose.onNodeWithTag("nav-home").performClick()

        compose.onNodeWithText("home-root").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `tapping Search again returns it to its first screen`() {
        show(WindowWidthClass.Compact)
        compose.onNodeWithTag("nav-search").performClick()
        compose.onNodeWithText("search-root").performClick()
        compose.onNodeWithText("search-book-7").performClick()

        compose.onNodeWithTag("nav-search").performClick()

        compose.onNodeWithText("search-root").assertIsDisplayed()
        pressBack()
        compose.onNodeWithText("home-root").assertIsDisplayed()
    }
}

/** A Home graph without data. */
internal fun NavGraphBuilder.stubHome(navController: NavHostController) {
    stubTab<HomeDestination>("home-root", "home-book", navController)
}

/** A Library graph without data: a root that opens a book, and that book. */
internal fun NavGraphBuilder.stubLibrary(navController: NavHostController) {
    stubTab<LibraryDestination>("library-root", "book", navController)
}

/** A Downloads graph without data: a root that opens a book or leads to the Library, and that book. */
internal fun NavGraphBuilder.stubDownloads(navController: NavHostController) {
    composable<DownloadsDestination> {
        Column {
            Text("downloads-root", Modifier.clickable { navController.navigate(BookDestination(FirstBook)) })
            Text("browse-library", Modifier.clickable { navController.select(TopLevelDestination.Library) })
        }
    }
    composable<BookDestination> { Text("downloads-book-${it.toRoute<BookDestination>().id}") }
}

/** A Search graph without data. */
internal fun NavGraphBuilder.stubSearch(navController: NavHostController) {
    stubTab<SearchDestination>("search-root", "search-book", navController)
}

/**
 * A root that opens book 7, and a book page that opens the next book. Every tab registers the
 * same book route, as the real graphs do: the text says whose copy is shown.
 */
private inline fun <reified Root : Any> NavGraphBuilder.stubTab(
    root: String,
    book: String,
    navController: NavHostController,
) {
    composable<Root> {
        Text(
            text = root,
            modifier = Modifier.clickable { navController.navigate(BookDestination(FirstBook)) },
        )
    }
    composable<BookDestination> { entry ->
        val id = entry.toRoute<BookDestination>().id
        Text(
            text = "$book-$id",
            modifier = Modifier.clickable { navController.navigate(BookDestination(NextBook.getValue(id))) },
        )
    }
}

/** The first book's id, as its text: what a route carries. */
private const val FirstBook = "7"

/** The book each stub page opens next: ids are not numbers to count on. */
private val NextBook = mapOf("7" to "8", "8" to "9", "9" to "10")

/** The profile the shell of these tests is shown for: what a request of the downloads must name, or nobody. */
private const val OpenProfile = "p1"
