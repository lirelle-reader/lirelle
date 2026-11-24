package app.tfdn.lirelle.core.ui

import android.content.Context
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.testing.laidOutTexts
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real line breaking, needed to know whether text is cut.
class ScreenPiecesTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun showLoadable(state: Loadable<String>, onRetry: () -> Unit = {}) {
        compose.setContent { LirelleTheme { LoadableContent(state, onRetry) { Text(it) } } }
    }

    @Test
    fun `results become loadable states`() {
        assertThat(ApiResult.Success("a").toLoadable()).isEqualTo(Loadable.Ready("a"))
        assertThat(ApiResult.Failure(ApiError.NotFound).toLoadable<String>())
            .isEqualTo(Loadable.Failed(ApiError.NotFound))
    }

    @Test
    fun `loading shows the loading slot`() {
        showLoadable(Loadable.Loading)

        compose.onNodeWithTag(LoadingTag).assertIsDisplayed()
    }

    @Test
    fun `a failure shows its message and retries`() {
        var retries = 0
        showLoadable(Loadable.Failed(ApiError.ServerUnreachable), onRetry = { retries++ })

        compose.onNodeWithText(context.getString(R.string.library_error_unreachable)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.state_retry)).performClick()
        assertThat(retries).isEqualTo(1)
    }

    @Test
    fun `ready shows the content`() {
        showLoadable(Loadable.Ready("the content"))

        compose.onNodeWithText("the content").assertIsDisplayed()
    }

    @Test
    fun `the detail scaffold shows its title and goes back`() {
        var backs = 0
        compose.setContent {
            LirelleTheme { DetailScaffold(title = "A series", onBack = { backs++ }) { Text("body") } }
        }

        compose.onNodeWithText("A series").assertIsDisplayed()
        compose.onNodeWithText("body").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.navigate_back)).performClick()
        assertThat(backs).isEqualTo(1)
    }

    @Test
    fun `a detail scaffold without a title has only back and its actions in its bar`() {
        var backs = 0
        compose.setContent {
            LirelleTheme {
                DetailScaffold(title = null, onBack = { backs++ }, actions = { Text("action") }) { Text("body") }
            }
        }

        assertThat(compose.laidOutTexts().map { it.text }).containsExactly("action", "body")
        compose.onAllNodes(isHeading()).assertCountEquals(0)
        compose.onNodeWithContentDescription(context.getString(R.string.navigate_back)).performClick()
        assertThat(backs).isEqualTo(1)
    }

    @Test
    fun `a compact bar is no taller than its back button`() {
        compose.setContent {
            LirelleTheme { DetailScaffold(title = null, onBack = {}, compactBar = true) { Text("body") } }
        }

        val back = compose.onNodeWithContentDescription(context.getString(R.string.navigate_back))
            .assertHasClickAction()
        val touchHeight = with(compose.density) { back.fetchSemanticsNode().touchBoundsInRoot.height.toDp() }
        val body = compose.onNodeWithText("body").getUnclippedBoundsInRoot()
        assertThat(touchHeight).isAtLeast(48.dp)
        assertThat(body.top).isAtLeast(back.getUnclippedBoundsInRoot().bottom)
        assertThat(body.top).isAtMost(48.dp)
    }

    @Test
    fun `the usual bar keeps its height`() {
        compose.setContent { LirelleTheme { DetailScaffold(title = "A series", onBack = {}) { Text("body") } } }

        assertThat(compose.onNodeWithText("body").getUnclippedBoundsInRoot().top).isEqualTo(64.dp)
    }

    @Test
    fun `an author without a portrait shows their initials`() {
        compose.setContent { LirelleTheme { AuthorPortrait(url = null, name = "émile Zed") } }

        compose.onNodeWithText("ÉZ").assertIsDisplayed()
    }

    @Test
    fun `a long text is collapsed and can be opened and closed`() {
        val more = context.getString(R.string.text_more)
        val less = context.getString(R.string.text_less)
        val long = "<p>" + "A sentence of the summary. ".repeat(40) + "</p>"
        compose.setContent { LirelleTheme { CollapsibleText(html = long, modifier = Modifier.width(300.dp)) } }

        compose.onNodeWithText(more).performClick()
        compose.onNodeWithText(less).assertIsDisplayed()
        compose.onNodeWithText(less).performClick()
        compose.onNodeWithText(more).assertIsDisplayed()
    }

    @Test
    fun `a short text has no button, and markup is not shown`() {
        compose.setContent { LirelleTheme { CollapsibleText(html = "<p>Short <em>and</em> sweet.</p>") } }

        compose.onNodeWithText("Short and sweet.", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText(context.getString(R.string.text_more)).assertCountEquals(0)
    }
}
