package app.tfdn.lirelle.feature.library.authors

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.library.model.Author
import app.tfdn.lirelle.core.library.model.AuthorDetail
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.core.testing.InMemoryLibraryPreferences
import app.tfdn.lirelle.core.testing.KomgaLikeCapabilities
import app.tfdn.lirelle.core.testing.MainDispatcherRule
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** An author page reached by an old link, the source having no authors, goes back (spec 2c, "Errors"). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class AuthorCapabilitiesTest {

    @get:Rule(order = 0)
    val mainRule = MainDispatcherRule()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val repository = FakeLibraryRepository().apply {
        authorResult = ApiResult.Success(AuthorDetail(Author(AuthorId("9"), "Ursula Writer", 2, null), null))
    }
    private var backs = 0

    @Test
    fun `an author page opened without authors goes back, asking nothing`() {
        val viewModel = AuthorViewModel("9", repository, InMemoryLibraryPreferences(), KomgaLikeCapabilities)
        compose.setContent {
            LirelleTheme {
                AuthorScreen(AuthorId("9"), onBack = { backs++ }, onOpenBook = {}, viewModel = viewModel)
            }
        }
        compose.waitForIdle()

        assertThat(backs).isEqualTo(1)
        assertThat(repository.calls).isEmpty()
        compose.onAllNodesWithText("Ursula Writer").assertCountEquals(0)
    }
}
