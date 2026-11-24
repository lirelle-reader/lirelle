package app.tfdn.lirelle.feature.home

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.library.model.HomeRow
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.testing.assertIsWhollyShown
import app.tfdn.lirelle.core.testing.sampleBook
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.core.ui.R as CoreR
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val ActionTag = "title-action"

/** Home's title takes an action at its end — the profile button — and nothing of Home moves for it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the title's place is compared.
@Config(qualifiers = "w411dp-h891dp")
class HomeTitleActionTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var withAction by mutableStateOf(false)

    private val loaded = HomeUiState(
        HomeRowKind.entries.associateWith { Loadable.Ready(HomeRow(it, emptyList(), false)) } +
            (HomeRowKind.Continue to Loadable.Ready(HomeRow(HomeRowKind.Continue, listOf(sampleBook("1")), false))),
    )
    private val allFailed =
        HomeUiState(HomeRowKind.entries.associateWith { Loadable.Failed(ApiError.ServerUnreachable) })
    private var state by mutableStateOf(loaded)

    private val title get() = compose.onNodeWithText(context.getString(R.string.home_title))
    private val firstRow get() = compose.onNodeWithText(context.getString(R.string.home_row_continue))

    @Composable
    private fun Action() {
        if (withAction) Box(Modifier.size(48.dp).testTag(ActionTag))
    }

    private fun show() {
        compose.setContent {
            LirelleTheme {
                HomeContent(
                    state = state,
                    onRetry = {},
                    onRefresh = {},
                    onOpenBook = {},
                    onRead = {},
                    onListen = {},
                    onNarrate = {},
                    onSeeAll = {},
                    onSignInAgain = {},
                    onOpenDownloads = {},
                    titleAction = { Action() },
                )
            }
        }
    }

    @Test
    fun `the action stands at the end of the title's row, whole, centred on the title`() {
        withAction = true
        show()

        val heading = title.getUnclippedBoundsInRoot()
        val action = compose.onNodeWithTag(ActionTag).assertIsWhollyShown().getUnclippedBoundsInRoot()
        title.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        // The screen's edge padding, at the end.
        assertThat(action.right).isEqualTo(411.dp - 16.dp)
        assertThat(action.left).isGreaterThan(heading.right)
        assertThat(((action.top + action.bottom) / 2).value)
            .isWithin(0.5f).of(((heading.top + heading.bottom) / 2).value)
    }

    /** With two profiles and the current one's server down, the way to the other profile is still there. */
    @Test
    fun `when everything failed the title and its action stand above the failure, where they always are`() {
        withAction = true
        show()
        val heading = title.getUnclippedBoundsInRoot()
        val action = compose.onNodeWithTag(ActionTag).getUnclippedBoundsInRoot()

        state = allFailed

        val retry = compose.onNodeWithText(context.getString(CoreR.string.state_retry)).assertIsDisplayed()
        title.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        assertThat(title.getUnclippedBoundsInRoot()).isEqualTo(heading)
        assertThat(compose.onNodeWithTag(ActionTag).assertIsWhollyShown().getUnclippedBoundsInRoot())
            .isEqualTo(action)
        assertThat(retry.getUnclippedBoundsInRoot().top).isAtLeast(action.bottom)
    }

    @Test
    fun `nothing of Home moves when the action comes or goes`() {
        show()
        val heading = title.getUnclippedBoundsInRoot()
        val row = firstRow.getUnclippedBoundsInRoot()

        withAction = true

        compose.onNodeWithTag(ActionTag).assertExists()
        assertThat(title.getUnclippedBoundsInRoot().top).isEqualTo(heading.top)
        assertThat(title.getUnclippedBoundsInRoot().left).isEqualTo(heading.left)
        assertThat(firstRow.getUnclippedBoundsInRoot().top).isEqualTo(row.top)
        // And what stands under the title is clear of the action.
        assertThat(compose.onNodeWithTag(ActionTag).getUnclippedBoundsInRoot().bottom).isAtMost(row.top)
    }
}
