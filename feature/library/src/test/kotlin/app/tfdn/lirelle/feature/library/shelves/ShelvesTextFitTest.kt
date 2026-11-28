package app.tfdn.lirelle.feature.library.shelves

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.ids.ShelfId
import app.tfdn.lirelle.core.library.SourceCapabilities
import app.tfdn.lirelle.core.library.model.Shelf
import app.tfdn.lirelle.core.library.model.ShelfKind
import app.tfdn.lirelle.core.library.model.Shelves
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.core.testing.assertAllTextFits
import app.tfdn.lirelle.feature.library.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

/** No text of the Shelves tab overflows or is clipped, its three sections shown, at a narrow width and any size. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class ShelvesTextFitTest(language: String, fontScale: Float) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-w320dp-h640dp", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val shelves = Shelves(
        smartLists = listOf(Shelf(ShelfKind.SmartList, ShelfId("3"), "To read soon", 14)),
        collections = listOf(Shelf(ShelfKind.Collection, ShelfId("c:7"), "Favourites", 1)),
        playlists = listOf(Shelf(ShelfKind.Playlist, ShelfId("p:9"), LongName, 1234)),
    )

    @Test
    fun `smart lists, collections and playlists`() {
        val repository = FakeLibraryRepository().apply { shelvesResult = ApiResult.Success(shelves) }
        val viewModel = ShelvesViewModel(repository, SourceCapabilities.Everything)
        compose.setContent { LirelleTheme { ShelvesTab(onOpenShelf = {}, viewModel = viewModel) } }

        compose.onNodeWithText(compose.activity.getString(R.string.shelves_playlists)).assertIsDisplayed()
        compose.assertAllTextFits()
    }

    companion object {
        private const val LongName = "Les grands romans d'aventure à écouter pendant les longs trajets en voiture"

        @JvmStatic
        @Parameters(name = "{0} x{1}")
        fun cases(): List<Array<Any>> = listOf("en", "fr").flatMap { language ->
            listOf(1.0f, 2.0f).map { scale -> arrayOf<Any>(language, scale) }
        }
    }
}
