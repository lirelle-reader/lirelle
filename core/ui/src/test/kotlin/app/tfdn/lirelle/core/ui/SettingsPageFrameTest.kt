package app.tfdn.lirelle.core.ui

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsPageFrameTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val back: String get() = context.getString(R.string.settings_back)

    @Test
    fun `a page of its own has an arrow that goes back`() {
        var backs = 0
        compose.setContent {
            LirelleTheme { SettingsPageFrame(title = "Comics", onBack = { backs++ }) { Text("Fit") } }
        }

        compose.onNodeWithContentDescription(back).performClick()

        assertThat(backs).isEqualTo(1)
    }

    @Test
    fun `a page in a pane has its title and no arrow`() {
        compose.setContent {
            LirelleTheme { SettingsPageFrame(title = "Comics", onBack = null) { Text("Fit") } }
        }

        compose.onNodeWithText("Comics").assertIsDisplayed()
        compose.onNodeWithContentDescription(back).assertDoesNotExist()
    }

    @Test
    fun `the title is a heading and the content is under it`() {
        compose.setContent {
            LirelleTheme { SettingsPageFrame(title = "Comics", onBack = {}) { Text("Fit") } }
        }

        compose.onNodeWithText("Comics").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithText("Fit").assertIsDisplayed()
    }
}
