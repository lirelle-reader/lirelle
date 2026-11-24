package app.tfdn.lirelle.feature.library

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.testing.KomgaLikeCapabilities
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun showTabs(tabs: () -> List<LibraryTab>) {
        compose.setContent {
            LirelleTheme { LibraryScreen(tabs = tabs(), page = { tab -> Text("page of ${tab.name}") }) }
        }
    }

    private fun show() {
        compose.setContent {
            LirelleTheme { LibraryScreen(page = { tab -> Text("page of ${tab.name}") }) }
        }
    }

    @Test
    fun `starts on Books`() {
        show()

        compose.onNodeWithText(context.getString(R.string.library_title)).assertIsDisplayed()
        compose.onNodeWithTag("tab-Books").assertIsSelected()
        compose.onNodeWithText("page of Books").assertIsDisplayed()
    }

    @Test
    fun `tapping a tab shows its page`() {
        show()

        compose.onNodeWithTag("tab-Authors").performClick()

        compose.onNodeWithTag("tab-Authors").assertIsSelected()
        compose.onNodeWithText("page of Authors").assertIsDisplayed()
    }

    @Test
    fun `a source that can do everything has the four tabs`() {
        assertThat(SourceCapabilities.Everything.libraryTabs()).containsExactlyElementsIn(LibraryTab.entries).inOrder()
        assertThat(LibraryViewModel(SourceCapabilities.Everything).tabs)
            .containsExactlyElementsIn(LibraryTab.entries).inOrder()
    }

    @Test
    fun `a Komga-like source has Books, Series and Shelves, without Authors`() {
        val tabs = LibraryViewModel(KomgaLikeCapabilities).tabs
        assertThat(tabs).containsExactly(LibraryTab.Books, LibraryTab.Series, LibraryTab.Shelves).inOrder()

        showTabs { tabs }

        compose.onNodeWithTag("tab-Authors").assertDoesNotExist()
        compose.onNodeWithTag("tab-Books").assertIsSelected()
        compose.onNodeWithTag("tab-Shelves").performClick()
        compose.onNodeWithText("page of Shelves").assertIsDisplayed()
    }

    @Test
    fun `a source without any kind of shelf has no Shelves tab`() {
        val noShelves = KomgaLikeCapabilities.copy(shelfKinds = emptySet())

        assertThat(noShelves.libraryTabs()).containsExactly(LibraryTab.Books, LibraryTab.Series).inOrder()
    }

    @Test
    fun `a tab shown that goes away leaves the Library on Books`() {
        var tabs by mutableStateOf<List<LibraryTab>>(LibraryTab.entries)
        showTabs { tabs }
        compose.onNodeWithTag("tab-Authors").performClick()
        compose.onNodeWithText("page of Authors").assertIsDisplayed()

        tabs = KomgaLikeCapabilities.libraryTabs()

        compose.onNodeWithTag("tab-Books").assertIsSelected()
        compose.onNodeWithText("page of Books").assertIsDisplayed()
    }

    @Test
    fun `the Library opens on the tab its source starts on`() {
        val komga = LibraryViewModel(KomgaLikeCapabilities)
        val bookOrbit = LibraryViewModel(SourceCapabilities.Everything)
        assertThat(komga.startTab).isEqualTo(LibraryTab.Series)
        assertThat(bookOrbit.startTab).isEqualTo(LibraryTab.Books)

        compose.setContent {
            LirelleTheme {
                LibraryScreen(
                    tabs = komga.tabs,
                    startTab = komga.startTab,
                    page = { tab -> Text("page of ${tab.name}") },
                )
            }
        }

        compose.onNodeWithTag("tab-Series").assertIsSelected()
        compose.onNodeWithText("page of Series").assertIsDisplayed()
    }

    @Test
    fun `the tab the user left comes back rather than the start tab`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            LirelleTheme {
                LibraryScreen(
                    tabs = KomgaLikeCapabilities.libraryTabs(),
                    startTab = LibraryTab.Series,
                    page = { tab -> Text("page of ${tab.name}") },
                )
            }
        }
        compose.onNodeWithTag("tab-Shelves").performClick()
        compose.onNodeWithText("page of Shelves").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithTag("tab-Shelves").assertIsSelected()
        compose.onNodeWithText("page of Shelves").assertIsDisplayed()
    }

    @Test
    fun `swiping moves to the next tab`() {
        show()

        compose.onNodeWithText("page of Books").performTouchInput { swipeLeft() }

        compose.onNodeWithTag("tab-Series").assertIsSelected()
        compose.onNodeWithText("page of Series").assertIsDisplayed()
    }
}
