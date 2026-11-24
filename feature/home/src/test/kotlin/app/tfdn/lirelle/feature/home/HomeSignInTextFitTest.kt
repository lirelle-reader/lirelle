package app.tfdn.lirelle.feature.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.library.model.HomeRow
import app.tfdn.lirelle.core.library.model.HomeRowKind
import app.tfdn.lirelle.core.network.ApiError
import app.tfdn.lirelle.core.session.ServerKind
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.LaidOutText
import app.tfdn.lirelle.core.testing.assertAllTextFits
import app.tfdn.lirelle.core.testing.assertIsWhollyShown
import app.tfdn.lirelle.core.testing.sampleBook
import app.tfdn.lirelle.core.ui.Loadable
import app.tfdn.lirelle.core.ui.R as CoreR
import app.tfdn.lirelle.core.ui.profileLine
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

private const val LongUsername = "marie-christine.delacroix-de-la-fontaine"

/**
 * No text of the notice of an expired session overflows or is cut, on a narrow phone, a phone and a tablet in
 * landscape, in both languages, at the default and at the largest text size. Its sentence wraps, a username
 * too long for a line breaking where it must; its button is whole, inside the card, under the sentence.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class HomeSignInTextFitTest(private val screen: String, language: String, fontScale: Float) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screen", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun text(id: Int, vararg args: Any) = compose.activity.getString(id, *args)

    private fun body(username: String) = text(
        R.string.home_sign_in_notice_body,
        profileLine(compose.activity.resources, username, ServerKind.BookOrbit),
    )

    private fun fits(rows: (HomeRowKind) -> Loadable<HomeRow>, username: String): List<LaidOutText> {
        val width = if (screen == Wide) WindowWidthClass.Expanded else WindowWidthClass.Compact
        val state = HomeUiState(
            rows = HomeRowKind.entries.associateWith(rows),
            expired = ExpiredSession("p7", username, ServerKind.BookOrbit),
        )
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalWindowWidthClass provides width) {
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
                    )
                }
            }
        }
        // A username has nowhere to break: the sentence that holds a long one may break inside it.
        return compose.assertAllTextFits(
            unbreakable = setOf(body(LongUsername)),
            wrappingLabels = setOf(text(CoreR.string.sign_in_again), text(CoreR.string.no_network_open_downloads)),
        )
    }

    private fun assertNoticeWhole(texts: List<LaidOutText>, username: String) {
        val expected = listOf(
            text(CoreR.string.sign_in_needed),
            body(username),
            text(CoreR.string.sign_in_again),
        )
        expected.forEach { wanted ->
            val found = texts.filter { it.text == wanted }
            assertWithMessage("\"$wanted\" on screen").that(found).hasSize(1)
            assertWithMessage("\"$wanted\" cut short")
                .that(found.filter { it.isEllipsized || !it.isWhollyShown }).isEmpty()
        }
        val card = compose.onNodeWithTag(SignInNoticeTag).assertIsWhollyShown().getUnclippedBoundsInRoot()
        val button = compose.onNodeWithTag(SignInAgainTag).assertIsWhollyShown().getUnclippedBoundsInRoot()
        val sentence = compose.onNodeWithTag(SignInNoticeBodyTag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertThat(button.height).isAtLeast(48.dp)
        assertWithMessage("the button is under the sentence").that(button.top).isAtLeast(sentence.bottom)
        assertWithMessage("the button is inside the card").that(button.bottom).isAtMost(card.bottom)
        assertWithMessage("the button is inside the card").that(button.left).isAtLeast(card.left)
        assertWithMessage("the button ends where the sentence does").that(button.right).isEqualTo(sentence.right)
    }

    @Test
    fun `the notice above the rows`() {
        val books = { kind: HomeRowKind ->
            Loadable.Ready(HomeRow(kind, listOf(sampleBook("${kind.ordinal + 1}")), hasMore = true))
        }

        assertNoticeWhole(fits(books, "camille"), "camille")
    }

    @Test
    fun `the notice of a long username, alone on a Home that could load nothing`() {
        val texts = fits({ Loadable.Failed(ApiError.ServerUnreachable) }, LongUsername)

        assertNoticeWhole(texts, LongUsername)
        assertOfflineBooksWhole(texts)
        // Nothing but the title, the notice and the way to the offline books: no failure is said beside them.
        assertThat(texts).hasSize(5)
    }

    @Test
    fun `the notice above an empty library`() {
        val texts = fits({ Loadable.Ready(HomeRow(it, emptyList(), hasMore = false)) }, LongUsername)

        assertNoticeWhole(texts, LongUsername)
        assertOfflineBooksWhole(texts)
        assertThat(texts.map { it.text }).contains(text(R.string.home_empty))
    }

    /** The way to the offline books: whole, its label not cut, under the card and no wider than it. */
    private fun assertOfflineBooksWhole(texts: List<LaidOutText>) {
        val label = text(CoreR.string.no_network_open_downloads)
        val found = texts.filter { it.text == label }
        assertWithMessage("\"$label\" on screen").that(found).hasSize(1)
        assertWithMessage("\"$label\" cut short").that(found.filter { it.isEllipsized || !it.isWhollyShown }).isEmpty()
        val card = compose.onNodeWithTag(SignInNoticeTag).getUnclippedBoundsInRoot()
        val action = compose.onNodeWithTag(SignInOfflineBooksTag).assertIsWhollyShown().getUnclippedBoundsInRoot()
        assertThat(action.height).isAtLeast(48.dp)
        assertWithMessage("the action is under the card").that(action.top).isAtLeast(card.bottom)
        assertWithMessage("the action starts where the card does").that(action.left).isEqualTo(card.left)
        assertWithMessage("the action is no wider than the card").that(action.right).isAtMost(card.right)
    }

    companion object {
        // Tall enough for the whole of Home at twice the text size, as in HomeTextFitTest.
        private const val Narrow = "w320dp-h3200dp"
        private const val Phone = "w411dp-h3200dp"
        private const val Wide = "w1280dp-h3200dp"

        @JvmStatic
        @Parameters(name = "{0} {1} x{2}")
        fun cases(): List<Array<Any>> = listOf(Narrow, Phone, Wide).flatMap { screen ->
            listOf("en", "fr").flatMap { language ->
                listOf(1.0f, 2.0f).map { scale -> arrayOf<Any>(screen, language, scale) }
            }
        }
    }
}
