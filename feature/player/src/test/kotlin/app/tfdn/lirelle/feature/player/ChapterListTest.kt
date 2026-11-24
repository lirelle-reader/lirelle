package app.tfdn.lirelle.feature.player

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.feature.player.controller.ChapterRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The list of chapters shows the current one, and follows it while it is in view. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp")
class ChapterListTest {

    @get:Rule
    val compose = createComposeRule()

    private var chapters by mutableStateOf(emptyList<ChapterRow>())
    private var current by mutableIntStateOf(Playing)

    private fun rows(current: Int) =
        (0 until PartCount).map { ChapterRow("Part ${it + 1}", 600_000, heard = it < current, current = it == current) }

    private fun show() {
        compose.setContent {
            LirelleTheme(darkTheme = true) {
                ChapterList(
                    bookId = BookId("42"),
                    chapters = chapters,
                    chapterIndex = current,
                    currentLeftMs = 300_000,
                    onChoose = {},
                    modifier = Modifier.height(600.dp),
                )
            }
        }
    }

    private fun playing(index: Int) {
        current = index
        chapters = rows(index)
        compose.waitForIdle()
    }

    @Test
    fun `a column composed before its rows shows the current chapter once they arrive`() {
        show()

        playing(Playing)

        compose.onNodeWithText("Part ${Playing + 1}").assertIsDisplayed()
        compose.onNodeWithText("Part 1").assertDoesNotExist()
    }

    @Test
    fun `a position that lands after the rows brings the current chapter into view`() {
        show()
        playing(0)

        playing(Playing)

        compose.onNodeWithText("Part ${Playing + 1}").assertIsDisplayed()
    }

    @Test
    fun `the next chapters are followed while the current one is in view`() {
        show()
        playing(Playing)

        playing(Playing + 1)
        playing(Playing + 2)
        playing(Playing + Far)

        compose.onNodeWithText("Part ${Playing + Far + 1}").assertIsDisplayed()
    }

    @Test
    fun `a list scrolled elsewhere stays where the user put it`() {
        show()
        playing(Playing)
        compose.onNodeWithTag(ChapterListTag).performScrollToIndex(0)

        playing(Playing + Far)

        compose.onNodeWithText("Part 1").assertIsDisplayed()
        compose.onNodeWithText("Part ${Playing + Far + 1}").assertDoesNotExist()
    }

    private companion object {
        const val PartCount = 407
        const val Playing = 349
        const val Far = 30
    }
}
