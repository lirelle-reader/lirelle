package app.tfdn.lirelle.feature.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.library.model.SearchResults
import app.tfdn.lirelle.core.library.model.SearchSection
import app.tfdn.lirelle.core.testing.sampleBook
import app.tfdn.lirelle.core.testing.sampleSeries
import app.tfdn.lirelle.core.ui.Loadable
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Where the results are scrolled to: kept when the screen is rebuilt, dropped with the results. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h400dp")
class SearchScrollTest {

    @get:Rule
    val compose = createComposeRule()

    private fun resultsFor(text: String) = SearchUiState(
        text = text,
        results = Loadable.Ready(
            SearchResults(
                text = text,
                books = SearchSection((1..5).map { sampleBook("$it") }, total = 5),
                series = SearchSection((40..44).map { sampleSeries("$it") }, total = 5),
            ),
        ),
    )

    private var state by mutableStateOf(resultsFor("mars"))
    private val restoration = StateRestorationTester(compose)

    private fun show() = restoration.setContent {
        LirelleTheme { SearchContent(state, SearchActions({}, {}, {}, {}, {}, {}, {}), {}, {}, {}, {}, {}) }
    }

    private fun scrolledBy(): Float = compose.onNodeWithTag("search-main").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    @Test
    fun `the scroll position survives the screen being rebuilt`() {
        show()
        compose.onNodeWithTag("result-series-44").performScrollTo()
        val before = scrolledBy()
        assertThat(before).isGreaterThan(0f)

        restoration.emulateSavedInstanceStateRestore()

        assertThat(scrolledBy()).isEqualTo(before)
    }

    @Test
    fun `the results of another text start at the top`() {
        show()
        compose.onNodeWithTag("result-series-44").performScrollTo()

        state = resultsFor("marsh")
        compose.waitForIdle()

        assertThat(scrolledBy()).isEqualTo(0f)
    }
}
