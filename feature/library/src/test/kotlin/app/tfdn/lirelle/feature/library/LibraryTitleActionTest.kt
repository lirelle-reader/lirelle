package app.tfdn.lirelle.feature.library

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.testing.assertIsWhollyShown
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val ActionTag = "title-action"

/** The Library's title takes an action at its end — the profile button — and nothing of the Library moves. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the title's place is compared.
@Config(qualifiers = "w411dp-h891dp")
class LibraryTitleActionTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var withAction by mutableStateOf(false)

    private val title get() = compose.onNodeWithText(context.getString(R.string.library_title))
    private val tabs get() = compose.onNodeWithTag("tab-Books")

    private fun show() {
        compose.setContent {
            LirelleTheme {
                LibraryScreen(
                    titleAction = { if (withAction) Box(Modifier.size(48.dp).testTag(ActionTag)) },
                    page = { tab -> Text("page of ${tab.name}") },
                )
            }
        }
    }

    @Test
    fun `the action stands at the end of the title's row, whole, level with the title and clear of the tabs`() {
        withAction = true
        show()

        val heading = title.getUnclippedBoundsInRoot()
        val action = compose.onNodeWithTag(ActionTag).assertIsWhollyShown().getUnclippedBoundsInRoot()
        title.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        assertThat(action.right).isEqualTo(411.dp - 16.dp)
        assertThat(action.left).isGreaterThan(heading.right)
        // The tabs are right under the title: the action is as near the title's middle as they allow.
        assertThat(action.bottom).isAtMost(tabs.getUnclippedBoundsInRoot().top)
        assertThat(((action.top + action.bottom) / 2).value)
            .isWithin(2.5f).of(((heading.top + heading.bottom) / 2).value)
        assertThat(action.top).isAtLeast(0.dp)
    }

    @Test
    fun `nothing of the Library moves when the action comes or goes`() {
        show()
        val heading = title.getUnclippedBoundsInRoot()
        val row = tabs.getUnclippedBoundsInRoot()

        withAction = true

        compose.onNodeWithTag(ActionTag).assertExists()
        assertThat(title.getUnclippedBoundsInRoot().top).isEqualTo(heading.top)
        assertThat(title.getUnclippedBoundsInRoot().left).isEqualTo(heading.left)
        assertThat(tabs.getUnclippedBoundsInRoot().top).isEqualTo(row.top)
    }
}
