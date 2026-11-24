package app.tfdn.lirelle.feature.account.switcher

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.LaidOutText
import app.tfdn.lirelle.core.testing.assertAllTextFits
import app.tfdn.lirelle.core.ui.R as CoreR
import app.tfdn.lirelle.feature.account.R
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

private const val LongServer = "bibliotheque.famille-delacroix-de-la-fontaine.example.org:8443/livres"
private const val LongName = "marie-christine.delacroix-de-la-fontaine"

/**
 * No text of the list of profiles overflows, is clipped or breaks mid-word, on a narrow phone, a phone and at
 * the menu's width, in both languages, at the default and at the largest text size. The rows grow with their
 * texts; only a server's address and a "`<username>` · `<kind>`" line are cut: each stays on one line and ends
 * with an ellipsis, which at 320 dp and twice the text size even an ordinary one does. Nothing overlaps and
 * nothing else is cut.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class ProfilesTextFitTest(
    screen: String,
    language: String,
    fontScale: Float,
) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screen", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun text(id: Int) = context.getString(id)

    /** Scrolling, as both of its hosts make it: at a large text size it is taller than a small screen. */
    private fun show(state: SwitcherState) {
        compose.setContent {
            LirelleTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ProfilesList(
                        state = state,
                        actions = ProfilesActions({}, {}, {}),
                        ground = MaterialTheme.colorScheme.surfaceContainerLow,
                        edge = 24.dp,
                    )
                }
            }
        }
    }

    /**
     * The mark of a row and the actions are in buttons, and may take two lines, whole. A row's line and its
     * server are not named here: the rule keeps each on one line.
     */
    private val wrapping
        get() = setOf(
            text(CoreR.string.sign_in_needed),
            text(R.string.profiles_add),
            text(R.string.profiles_manage),
        )

    private fun assertRowsHold(state: SwitcherState, texts: List<LaidOutText>) {
        state.rows.forEach { row ->
            val bounds = compose.onNodeWithTag(ProfileRowTagPrefix + row.id).getUnclippedBoundsInRoot()
            assertWithMessage("height of the row of ${row.line}")
                .that(bounds.height).isAtLeast(if (row.signInNeeded) 88.dp else 72.dp)
        }
        // Every line and every server is on one line, whatever its length.
        texts.filter { laidOut -> state.rows.any { it.server == laidOut.text || it.line == laidOut.text } }
            .also { assertThat(it).hasSize(state.rows.size * 2) }
            .forEach { assertWithMessage("lines of $it").that(it.lineCount).isEqualTo(1) }
    }

    @Test
    fun `the list of three profiles fits`() {
        show(ThreeProfiles)

        val texts = compose.assertAllTextFits(wrappingLabels = wrapping)

        assertRowsHold(ThreeProfiles, texts)
        // Nothing but a profile's line and its server is ever cut: at a large text size even ordinary ones are.
        val mayBeCut = ThreeProfiles.rows.flatMap { listOf(it.line, it.server) }
        assertThat(texts.filter { it.isEllipsized }.map { it.text }.toSet() - mayBeCut).isEmpty()
        assertThat(texts.map { it.text }).containsAtLeast(
            text(R.string.profiles_title),
            "camille · BookOrbit",
            "livres.example.org",
            text(CoreR.string.sign_in_needed),
            text(R.string.profiles_add),
            text(R.string.profiles_manage),
        )
    }

    @Test
    fun `a long username and a long address end with an ellipsis and keep clear of the check`() {
        val state = SwitcherState(
            listOf(
                row("p0", LongName, LongServer, current = true, signInNeeded = true),
                row("p1", LongName, LongServer),
            ),
        )
        show(state)

        val texts = compose.assertAllTextFits(wrappingLabels = wrapping)

        assertRowsHold(state, texts)
        val cut = texts.filter { it.text == LongServer || it.text == state.rows.first().line }
        assertThat(cut).hasSize(4)
        cut.forEach { assertWithMessage("$it is cut short").that(it.isEllipsized).isTrue() }
        val check = compose.onNodeWithTag(ProfilesCheckTag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val row = compose.onNodeWithTag(ProfileRowTagPrefix + "p0").getUnclippedBoundsInRoot()
        with(compose.density) {
            texts.filter { it.boundsInRoot.top >= row.top.toPx() && it.boundsInRoot.bottom <= row.bottom.toPx() }
                .also { assertThat(it).hasSize(3) }
                .forEach { laidOut ->
                    assertWithMessage("$laidOut ends before the check")
                        .that(laidOut.boundsInRoot.right).isAtMost(check.left.toPx())
                }
            assertWithMessage("the check is inside its row").that(check.right).isAtMost(row.right)
        }
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}, {1}, font scale {2}")
        fun conditions(): List<Array<Any>> =
            listOf("w320dp-h640dp-xhdpi", "w411dp-h891dp-420dpi", "w380dp-h800dp-xhdpi").flatMap { screen ->
                listOf("en", "fr").flatMap { language ->
                    listOf(1.0f, 2.0f).map { fontScale -> arrayOf(screen, language, fontScale) }
                }
            }
    }
}
