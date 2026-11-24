package app.tfdn.lirelle.feature.library

import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.designsystem.LocalWindowWidthClass
import app.tfdn.lirelle.core.designsystem.WindowWidthClass
import app.tfdn.lirelle.core.library.model.BookFilters
import app.tfdn.lirelle.core.library.model.BookListRequest
import app.tfdn.lirelle.core.library.model.BookSort
import app.tfdn.lirelle.core.library.model.FormatFilter
import app.tfdn.lirelle.core.library.model.SortDirection
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.core.testing.KomgaLikeCapabilities
import app.tfdn.lirelle.core.testing.assertAllTextFits
import app.tfdn.lirelle.core.testing.assertIsWhollyShown
import app.tfdn.lirelle.feature.library.books.BookListChoices
import app.tfdn.lirelle.feature.library.books.BookListUiState
import app.tfdn.lirelle.feature.library.books.ChipRowTag
import app.tfdn.lirelle.feature.library.books.SortFilterBar
import app.tfdn.lirelle.feature.library.books.labelRes
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlin.math.abs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.GraphicsMode

/**
 * The Library of a source that offers less (spec 2c): its fewer tabs, chips and menu choices are neither clipped nor
 * out of line, on a narrow phone and a tablet in landscape, in both languages, at the largest text size too.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real font metrics: the default mode counts one pixel per character.
class LibraryFewerChoicesTextFitTest(private val screen: String, language: String, fontScale: Float) {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "$language-$screen", fontScale = fontScale)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val width = if (screen == TabletLandscape) WindowWidthClass.Expanded else WindowWidthClass.Compact

    private val choices = BookListChoices.of(KomgaLikeCapabilities)

    private val bar = BookListUiState(
        request = BookListRequest(
            BookSort.Published,
            SortDirection.Descending,
            BookFilters(formats = setOf(FormatFilter.Epub)),
        ),
        total = 1234,
        choices = choices,
    )

    @Test
    fun `three tabs share the width, whole and in line`() {
        showLibrary()

        compose.assertAllTextFits()
        val tabs = KomgaLikeCapabilities.libraryTabs()
        assertThat(tabs).containsExactly(LibraryTab.Books, LibraryTab.Series, LibraryTab.Shelves).inOrder()
        compose.onAllNodes(hasTestTag("tab-${LibraryTab.Authors.name}")).assertCountEquals(0)
        val boxes = tabs.map { compose.onNode(hasTestTag("tab-${it.name}")).fetchSemanticsNode().boundsInRoot }
        val rowWidth = boxes.last().right - boxes.first().left
        boxes.forEachIndexed { index, box ->
            assertWithMessage("top of tab $index").that(box.top).isWithin(Tolerance).of(boxes.first().top)
            assertWithMessage("height of tab $index").that(box.height).isWithin(Tolerance).of(boxes.first().height)
            assertWithMessage("width of tab $index").that(box.width).isWithin(Tolerance).of(rowWidth / tabs.size)
        }
        tabs.zip(boxes).forEach { (tab, box) ->
            val label = compose.assertAllTextFits().single { text ->
                text.text == string(tab.label) && box.contains(text.boundsInRoot.center)
            }
            assertWithMessage("lines of the tab $label").that(label.lineCount).isEqualTo(1)
            // As with four tabs (spec 2026-11-13), a label too long for its tab ends with an ellipsis: only when
            // it takes all the room its tab has, as "Étagères" on a narrow phone at the largest text size.
            if (label.isEllipsized) {
                val room = box.width - with(compose.density) { TabLabelSides.toPx() }
                assertWithMessage("$label cut short with room left").that(label.boundsInRoot.width)
                    .isWithin(Tolerance).of(room)
            }
            assertWithMessage("$label is whole").that(label.isWhollyShown).isTrue()
            val offCentre = abs(label.boundsInRoot.center.x - box.center.x)
            assertWithMessage("$label off the centre of its tab").that(offCentre).isAtMost(Tolerance)
        }
    }

    @Test
    fun `the sort and two filters, each whole once scrolled to`() {
        showLibrary()

        compose.assertAllTextFits()
        val chips = hasClickAction() and hasAnyAncestor(hasTestTag(ChipRowTag))
        compose.onAllNodes(chips).assertCountEquals(3)
        assertThat(compose.onAllNodes(hasText(string(R.string.filter_read_along))).fetchSemanticsNodes()).isEmpty()
        repeat(3) { index ->
            val chip = compose.onAllNodes(chips)[index].performScrollTo().assertIsWhollyShown()
            val labels = chip.fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text }
            assertThat(labels).hasSize(1)

            val label = compose.assertAllTextFits().single { it.text in labels }
            assertWithMessage("lines of the chip $label").that(label.lineCount).isEqualTo(1)
            assertWithMessage("$label is cut short").that(label.isEllipsized).isFalse()
            assertWithMessage("$label is whole").that(label.isWhollyShown).isTrue()
        }
    }

    @Test
    fun `the sort menu`() = assertMenuFits(
        chip = string(BookSort.Published.labelRes()),
        offered = choices.sorts.map { string(it.labelRes()) },
    )

    @Test
    fun `the format menu`() = assertMenuFits(
        chip = string(R.string.filter_format),
        offered = choices.formats.map { string(it.labelRes()) },
    )

    @Test
    fun `the status menu`() = assertMenuFits(
        chip = string(R.string.filter_status),
        offered = choices.statuses.map { string(it.labelRes()) },
    )

    /** The menu of [chip] opened: each of its [offered] items is whole, on one line, and every text still fits. */
    private fun assertMenuFits(chip: String, offered: List<String>) {
        showLibrary()
        compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag(ChipRowTag)) and hasText(chip))
            .performScrollTo()
            .performClick()
        compose.waitForIdle()

        val texts = compose.assertAllTextFits()
        offered.forEach { item ->
            val shown = texts.filter { it.text == item && it.isWhollyShown && !it.isEllipsized && it.lineCount == 1 }
            assertWithMessage("menu item \"$item\" whole on one line").that(shown).isNotEmpty()
        }
    }

    private fun showLibrary() {
        compose.setContent {
            LirelleTheme {
                CompositionLocalProvider(LocalWindowWidthClass provides width) {
                    LibraryScreen(tabs = KomgaLikeCapabilities.libraryTabs()) {
                        SortFilterBar(
                            state = bar,
                            onSort = {},
                            onToggleFormat = {},
                            onToggleStatus = {},
                            onReadAlongOnly = {},
                            onPresentation = {},
                            // As in the book list, which keeps this margin around its content.
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }
    }

    private fun string(@StringRes id: Int): String = compose.activity.getString(id)

    companion object {
        private const val Narrow = "w320dp-h640dp"
        private const val TabletLandscape = "w1280dp-h800dp-land"

        /** In pixels: rounding of the layout. */
        private const val Tolerance = 1.5f

        /** What a tab keeps on both sides of its label together. */
        private val TabLabelSides = 8.dp

        @JvmStatic
        @Parameters(name = "{0} {1} x{2}")
        fun cases(): List<Array<Any>> = listOf(Narrow, TabletLandscape).flatMap { screen ->
            listOf("en", "fr").flatMap { language ->
                listOf(1.0f, 2.0f).map { scale -> arrayOf<Any>(screen, language, scale) }
            }
        }
    }
}
