package app.tfdn.lirelle.feature.reader

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.reader.settings.BrightnessEdge
import app.tfdn.lirelle.core.reader.settings.FontSizesPx
import app.tfdn.lirelle.core.reader.settings.LineHeights
import app.tfdn.lirelle.core.reader.settings.MarginSize
import app.tfdn.lirelle.core.reader.settings.ReaderAlign
import app.tfdn.lirelle.core.reader.settings.ReaderFlow
import app.tfdn.lirelle.core.reader.settings.ReaderFont
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.core.reader.settings.ReaderTheme
import app.tfdn.lirelle.core.reader.settings.RunningLine
import app.tfdn.lirelle.core.reader.settings.RunningSlot
import app.tfdn.lirelle.core.testing.DeviceRule
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderSheetsTest {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "en-w411dp-h891dp", fontScale = 1f)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun string(@StringRes id: Int, vararg arguments: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(id, *arguments)

    private fun show(
        sheet: ReaderSheet?,
        settings: ReaderSettings = ReaderSettings(),
        systemBrightness: Float? = null,
    ): SheetHost {
        val host = SheetHost(sheet, settings, systemBrightness)
        compose.setContent { LirelleTheme { ReaderSheets(host.state, host.actions) } }
        return host
    }

    private fun choice(@StringRes label: Int): SemanticsNodeInteraction =
        compose.onNode(hasText(string(label)) and isSelectable())

    private fun switch(@StringRes label: Int): SemanticsNodeInteraction =
        compose.onNode(hasText(string(label)) and isToggleable())

    private fun button(@StringRes spoken: Int): SemanticsNodeInteraction =
        compose.onNodeWithContentDescription(string(spoken))

    private fun SemanticsNodeInteraction.press() = performScrollTo().performClick()

    // Which sheet

    @Test
    fun `nothing is shown when no sheet is open, nor for the table of contents`() {
        val host = show(null)
        compose.onNodeWithTag(ReaderSheetTag).assertDoesNotExist()

        host.actions.showSheet(ReaderSheet.Contents)
        compose.onNodeWithTag(ReaderSheetTag).assertDoesNotExist()
    }

    @Test
    fun `each sheet is the one asked for`() {
        val host = show(ReaderSheet.Settings)
        compose.onNodeWithText(string(R.string.reader_settings_title)).assertExists()
        compose.onNodeWithText(string(R.string.reader_settings_theme)).assertExists()

        host.actions.showSheet(ReaderSheet.RunningLines)
        compose.onNodeWithText(string(R.string.reader_settings_title)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.reader_running_header_left)).assertExists()

        host.actions.showSheet(ReaderSheet.MoreOptions)
        compose.onNodeWithText(string(R.string.reader_running_header_left)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.reader_more_layout)).assertExists()
    }

    @Test
    fun `a sheet leaves the upper part of the screen to the page`() {
        val host = show(null)
        for (sheet in listOf(ReaderSheet.Settings, ReaderSheet.RunningLines, ReaderSheet.MoreOptions)) {
            host.actions.showSheet(sheet)

            val screen = compose.onRoot().getUnclippedBoundsInRoot()
            val bounds = compose.onNodeWithTag(ReaderSheetTag).getUnclippedBoundsInRoot()

            assertThat(bounds.bottom).isEqualTo(screen.bottom)
            assertThat(bounds.height.value).isAtMost(screen.height.value * 0.6f + 1f)
            assertThat(bounds.top.value).isAtLeast(screen.height.value * 0.4f - 1f)
        }
    }

    @Test
    fun `the settings are taller than their sheet and scroll inside it`() {
        show(ReaderSheet.Settings)

        val more = compose.onNodeWithText(string(R.string.reader_settings_more))
        more.assertIsNotDisplayed()
        more.performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a tap on the page above the sheet closes it, a tap on the sheet does not`() {
        val host = show(ReaderSheet.Settings)

        compose.onNodeWithText(string(R.string.reader_settings_title)).performClick()
        assertThat(host.state.openSheet).isEqualTo(ReaderSheet.Settings)

        compose.onRoot().performTouchInput { click(topCenter.copy(y = 40f)) }
        assertThat(host.state.openSheet).isNull()
    }

    @Test
    fun `done closes the sheet`() {
        val host = show(ReaderSheet.Settings)

        compose.onNodeWithText(string(R.string.reader_settings_done)).performClick()

        assertThat(host.state.openSheet).isNull()
    }

    // The settings sheet

    @Test
    fun `a swatch chooses its theme and says which one is chosen`() {
        val host = show(ReaderSheet.Settings)
        choice(R.string.reader_theme_system).assertIsSelected()

        choice(R.string.reader_theme_sepia).press()

        assertThat(host.settings).isEqualTo(ReaderSettings(theme = ReaderTheme.Sepia))
        choice(R.string.reader_theme_sepia).assertIsSelected()
        choice(R.string.reader_theme_system).assertIsNotSelected()
    }

    @Test
    fun `every theme has its swatch`() {
        val host = show(ReaderSheet.Settings)
        val swatches = listOf(
            R.string.reader_theme_system to ReaderTheme.System,
            R.string.reader_theme_light to ReaderTheme.Light,
            R.string.reader_theme_sepia to ReaderTheme.Sepia,
            R.string.reader_theme_dark to ReaderTheme.Dark,
            R.string.reader_theme_black to ReaderTheme.Black,
        )

        swatches.reversed().forEach { (label, theme) ->
            choice(label).press()
            assertThat(host.settings.theme).isEqualTo(theme)
        }
    }

    @Test
    fun `a font is chosen by its name`() {
        val host = show(ReaderSheet.Settings)
        val fonts = listOf(
            R.string.reader_font_serif to ReaderFont.Serif,
            R.string.reader_font_sans to ReaderFont.Sans,
            R.string.reader_font_dyslexic to ReaderFont.Dyslexic,
            R.string.reader_font_book to ReaderFont.Book,
        )

        fonts.forEach { (label, font) ->
            choice(label).press()
            assertThat(host.settings).isEqualTo(ReaderSettings(font = font))
            choice(label).assertIsSelected()
        }
    }

    @Test
    fun `the first font is the book's layout and can always be chosen`() {
        show(ReaderSheet.Settings, ReaderSettings(font = ReaderFont.Serif))

        assertThat(string(R.string.reader_font_book)).isEqualTo("Book's layout")
        val fonts = compose.onAllNodes(isSelectable())
            .fetchSemanticsNodes()
            .map { node -> node.config[SemanticsProperties.Text].joinToString { it.text } }
            .drop(ReaderTheme.entries.size)
        assertThat(fonts.first()).isEqualTo("Book's layout")
        choice(R.string.reader_font_book).assertIsEnabled()
    }

    @Test
    fun `plus and minus move the text size one step`() {
        val host = show(ReaderSheet.Settings, ReaderSettings(fontSizeStep = 3))

        button(R.string.reader_settings_size_larger).press()
        assertThat(host.settings).isEqualTo(ReaderSettings(fontSizeStep = 4))
        compose.onNodeWithText(FontSizesPx[4].toString()).assertExists()

        button(R.string.reader_settings_size_smaller).press()
        button(R.string.reader_settings_size_smaller).press()
        assertThat(host.settings).isEqualTo(ReaderSettings(fontSizeStep = 2))
    }

    @Test
    fun `the text size stops at both ends`() {
        val host = show(ReaderSheet.Settings, ReaderSettings(fontSizeStep = 0))
        button(R.string.reader_settings_size_smaller).assertIsNotEnabled()
        button(R.string.reader_settings_size_larger).assertIsEnabled()

        host.actions.updateSettings { it.copy(fontSizeStep = FontSizesPx.lastIndex) }
        button(R.string.reader_settings_size_larger).assertIsNotEnabled()
        button(R.string.reader_settings_size_smaller).assertIsEnabled()
    }

    @Test
    fun `plus and minus move the line spacing one step`() {
        val host = show(ReaderSheet.Settings, ReaderSettings(lineHeightStep = 2))
        compose.onNodeWithText("1.3").assertExists()

        button(R.string.reader_settings_line_spacing_looser).press()
        assertThat(host.settings).isEqualTo(ReaderSettings(lineHeightStep = 3))

        button(R.string.reader_settings_line_spacing_tighter).press()
        button(R.string.reader_settings_line_spacing_tighter).press()
        assertThat(host.settings).isEqualTo(ReaderSettings(lineHeightStep = 1))
    }

    @Test
    fun `the line spacing stops at both ends`() {
        val host = show(ReaderSheet.Settings, ReaderSettings(lineHeightStep = 0))
        button(R.string.reader_settings_line_spacing_tighter).assertIsNotEnabled()
        button(R.string.reader_settings_line_spacing_looser).assertIsEnabled()

        host.actions.updateSettings { it.copy(lineHeightStep = LineHeights.lastIndex) }
        button(R.string.reader_settings_line_spacing_looser).assertIsNotEnabled()
        button(R.string.reader_settings_line_spacing_tighter).assertIsEnabled()
    }

    @Test
    fun `the margins go from narrow to wide and stop there`() {
        val host = show(ReaderSheet.Settings)
        compose.onNodeWithText(string(R.string.reader_margins_medium)).assertExists()

        button(R.string.reader_settings_margins_wider).press()
        assertThat(host.settings).isEqualTo(ReaderSettings(margins = MarginSize.Wide))
        compose.onNodeWithText(string(R.string.reader_margins_wide)).assertExists()
        button(R.string.reader_settings_margins_wider).assertIsNotEnabled()

        button(R.string.reader_settings_margins_narrower).press()
        button(R.string.reader_settings_margins_narrower).press()
        assertThat(host.settings).isEqualTo(ReaderSettings(margins = MarginSize.Narrow))
        compose.onNodeWithText(string(R.string.reader_margins_narrow)).assertExists()
        button(R.string.reader_settings_margins_narrower).assertIsNotEnabled()
    }

    @Test
    fun `the alignment goes from the book's to justified to left and stops there`() {
        val host = show(ReaderSheet.Settings)
        button(R.string.reader_settings_alignment_previous).assertIsNotEnabled()

        button(R.string.reader_settings_alignment_next).press()
        assertThat(host.settings).isEqualTo(ReaderSettings(align = ReaderAlign.Justify))
        compose.onNodeWithText(string(R.string.reader_align_justify)).assertExists()

        button(R.string.reader_settings_alignment_next).press()
        assertThat(host.settings).isEqualTo(ReaderSettings(align = ReaderAlign.Start))
        compose.onNodeWithText(string(R.string.reader_align_start)).assertExists()
        button(R.string.reader_settings_alignment_next).assertIsNotEnabled()

        button(R.string.reader_settings_alignment_previous).press()
        assertThat(host.settings).isEqualTo(ReaderSettings(align = ReaderAlign.Justify))
    }

    @Test
    fun `a stepper says its value with its name`() {
        show(ReaderSheet.Settings)

        compose.onNodeWithContentDescription("Size: 18").assertExists()
        compose.onNodeWithContentDescription("Margins: Medium").assertExists()
    }

    @Test
    fun `hyphenation is switched off and on`() {
        val host = show(ReaderSheet.Settings)
        switch(R.string.reader_settings_hyphenation).assertIsOn()

        switch(R.string.reader_settings_hyphenation).press()
        assertThat(host.settings).isEqualTo(ReaderSettings(hyphenate = false))
        switch(R.string.reader_settings_hyphenation).assertIsOff()

        switch(R.string.reader_settings_hyphenation).press()
        assertThat(host.settings).isEqualTo(ReaderSettings())
    }

    @Test
    fun `the brightness follows the system until told otherwise`() {
        val host = show(ReaderSheet.Settings)
        switch(R.string.reader_settings_brightness_system).assertIsOn()

        switch(R.string.reader_settings_brightness_system).press()
        assertThat(host.settings).isEqualTo(ReaderSettings(brightness = 0.5f))
        switch(R.string.reader_settings_brightness_system).assertIsOff()

        switch(R.string.reader_settings_brightness_system).press()
        assertThat(host.settings).isEqualTo(ReaderSettings())
    }

    @Test
    fun `a brightness of its own starts from the level the screen is at`() {
        val host = show(ReaderSheet.Settings, systemBrightness = 0.2f)

        switch(R.string.reader_settings_brightness_system).press()

        assertThat(host.settings).isEqualTo(ReaderSettings(brightness = 0.2f))
    }

    @Test
    fun `the slider stands at the level of the screen while the system is followed`() {
        show(ReaderSheet.Settings, systemBrightness = 0.2f)

        val range = button(R.string.reader_settings_brightness).fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo]
        assertThat(range.current).isEqualTo(0.2f)
    }

    @Test
    fun `a screen at its darkest starts the brightness at the lowest the reader sets`() {
        val host = show(ReaderSheet.Settings, systemBrightness = 0f)

        switch(R.string.reader_settings_brightness_system).press()

        assertThat(host.settings).isEqualTo(ReaderSettings(brightness = 0.01f))
    }

    @Test
    fun `the slider sets the brightness, taking over from the system`() {
        val host = show(ReaderSheet.Settings)

        button(R.string.reader_settings_brightness).performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.3f) }

        assertThat(host.settings).isEqualTo(ReaderSettings(brightness = 0.3f))
        assertThat(host.brightnessKept).isEqualTo(1)
        switch(R.string.reader_settings_brightness_system).assertIsOff()
    }

    @Test
    fun `a brightness slid along is set at each move and kept once, when it is let go`() {
        val host = show(ReaderSheet.Settings, ReaderSettings(brightness = 0.5f))
        val slider = button(R.string.reader_settings_brightness).performScrollTo()

        slider.performTouchInput {
            down(center)
            moveBy(Offset(60f, 0f))
            moveBy(Offset(60f, 0f))
            moveBy(Offset(60f, 0f))
        }

        assertThat(host.brightnessSet.size).isAtLeast(3)
        assertThat(host.brightnessSet).isInStrictOrder()
        assertThat(host.brightnessKept).isEqualTo(0)
        // The slider follows the finger from the state, which follows at once.
        val range = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertThat(range.current).isEqualTo(host.brightnessSet.last())

        slider.performTouchInput { up() }

        assertThat(host.brightnessKept).isEqualTo(1)
        // Nothing went the way of the other settings, which is a write each time.
        assertThat(host.updates).isEqualTo(0)
    }

    @Test
    fun `the slider stands where the brightness is`() {
        show(ReaderSheet.Settings, ReaderSettings(brightness = 0.8f))

        val range = button(R.string.reader_settings_brightness).fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo]
        assertThat(range.current).isEqualTo(0.8f)
        assertThat(range.range).isEqualTo(0.01f..1f)
    }

    @Test
    fun `two rows lead to the header and footer and to more options, with what they are set to`() {
        val host = show(ReaderSheet.Settings, ReaderSettings(volumeKeys = true))
        compose.onNodeWithText("Chapter title · Time · Pages left in the chapter · Percentage of the book")
            .assertExists()
        compose.onNodeWithText("Paginated · Volume keys · Brightness on the left edge").assertExists()

        compose.onNodeWithText(string(R.string.reader_settings_running_lines)).press()
        assertThat(host.state.openSheet).isEqualTo(ReaderSheet.RunningLines)

        host.actions.showSheet(ReaderSheet.Settings)
        compose.onNodeWithText(string(R.string.reader_settings_more)).press()
        assertThat(host.state.openSheet).isEqualTo(ReaderSheet.MoreOptions)
        assertThat(host.updates).isEqualTo(0)
    }

    @Test
    fun `a header and footer showing nothing say so`() {
        val nothing = RunningLine(RunningSlot.Nothing, RunningSlot.Nothing)
        val settings = ReaderSettings(header = nothing, footer = nothing, brightnessEdge = BrightnessEdge.Off)
        show(ReaderSheet.Settings, settings)

        compose.onNodeWithText("Nothing").assertExists()
        compose.onNodeWithText("Paginated").assertExists()
    }

    // Header and footer

    @Test
    fun `each of the four ends is chosen among the eight things it can show`() {
        val host = show(ReaderSheet.RunningLines)
        val default = ReaderSettings()
        val battery = RunningSlot.Battery
        val ends = listOf(
            R.string.reader_running_header_left to default.copy(header = default.header.copy(left = battery)),
            R.string.reader_running_header_right to default.copy(header = default.header.copy(right = battery)),
            R.string.reader_running_footer_left to default.copy(footer = default.footer.copy(left = battery)),
            R.string.reader_running_footer_right to default.copy(footer = default.footer.copy(right = battery)),
        )

        ends.forEach { (end, expected) ->
            host.actions.updateSettings { ReaderSettings() }
            compose.onNodeWithText(string(end)).press()
            assertThat(compose.onAllNodes(isSelectable()).fetchSemanticsNodes()).hasSize(RunningSlot.entries.size)

            choice(R.string.reader_slot_battery).press()

            assertThat(host.settings).isEqualTo(expected)
            // Chosen: the list closes.
            assertThat(compose.onAllNodes(isSelectable()).fetchSemanticsNodes()).isEmpty()
        }
    }

    @Test
    fun `a list of choices shows which one is in use, by its name`() {
        show(ReaderSheet.RunningLines)

        compose.onNodeWithText(string(R.string.reader_running_footer_left)).press()

        choice(R.string.reader_slot_pages_left).assertIsSelected()
        listOf(
            R.string.reader_slot_nothing,
            R.string.reader_slot_book_title,
            R.string.reader_slot_chapter_title,
            R.string.reader_slot_book_percent,
            R.string.reader_slot_chapter_percent,
            R.string.reader_slot_time,
            R.string.reader_slot_battery,
        ).forEach { choice(it).assertIsNotSelected() }
    }

    @Test
    fun `the percentage of the chapter can be chosen, apart from that of the book`() {
        val host = show(ReaderSheet.RunningLines)

        compose.onNodeWithText(string(R.string.reader_running_footer_left)).press()
        assertThat(string(R.string.reader_slot_chapter_percent)).isEqualTo("Percentage of the chapter")
        assertThat(string(R.string.reader_slot_book_percent)).isEqualTo("Percentage of the book")
        choice(R.string.reader_slot_chapter_percent).press()

        assertThat(host.settings.footer).isEqualTo(RunningLine(RunningSlot.ChapterPercent, RunningSlot.BookPercent))
        compose.onNodeWithTag(FooterPreviewTag).assert(hasContentDescription("Footer: 67%, 42%"))
    }

    @Test
    fun `the preview shows a sample of each line`() {
        val settings = ReaderSettings(
            header = RunningLine(RunningSlot.ChapterTitle, RunningSlot.BookPercent),
            footer = RunningLine(RunningSlot.Nothing, RunningSlot.BookTitle),
        )
        val host = show(ReaderSheet.RunningLines, settings)

        compose.onNodeWithTag(HeaderPreviewTag).assert(hasContentDescription("Header: Chapter 3, 42%"))
        compose.onNodeWithTag(FooterPreviewTag).assert(hasContentDescription("Footer: The Time Machine"))

        host.actions.updateSettings { it.copy(footer = RunningLine(RunningSlot.Nothing, RunningSlot.Nothing)) }
        compose.onNodeWithTag(FooterPreviewTag).assert(hasContentDescription("Footer: nothing"))
    }

    @Test
    fun `the back arrow returns to the settings and done closes`() {
        val host = show(null)
        for (sheet in listOf(ReaderSheet.RunningLines, ReaderSheet.MoreOptions)) {
            host.actions.showSheet(sheet)

            button(R.string.reader_settings_back).performClick()
            assertThat(host.state.openSheet).isEqualTo(ReaderSheet.Settings)

            host.actions.showSheet(sheet)
            compose.onNodeWithText(string(R.string.reader_settings_done)).performClick()
            assertThat(host.state.openSheet).isNull()
        }
    }

    // More options

    @Test
    fun `the layout is paginated or scrolled`() {
        val host = show(ReaderSheet.MoreOptions)
        choice(R.string.reader_flow_paginated).assertIsSelected()

        choice(R.string.reader_flow_scrolled).press()
        assertThat(host.settings).isEqualTo(ReaderSettings(flow = ReaderFlow.Scrolled))
        choice(R.string.reader_flow_scrolled).assertIsSelected()

        choice(R.string.reader_flow_paginated).press()
        assertThat(host.settings).isEqualTo(ReaderSettings())
    }

    @Test
    fun `the volume keys are switched on and off`() {
        val host = show(ReaderSheet.MoreOptions)
        switch(R.string.reader_more_volume_keys).assertIsOff()

        switch(R.string.reader_more_volume_keys).press()
        assertThat(host.settings).isEqualTo(ReaderSettings(volumeKeys = true))
        switch(R.string.reader_more_volume_keys).assertIsOn()

        switch(R.string.reader_more_volume_keys).press()
        assertThat(host.settings).isEqualTo(ReaderSettings())
    }

    @Test
    fun `the footnotes panel and the corner bookmark are on, and switched off and on`() {
        val host = show(ReaderSheet.MoreOptions)
        switch(R.string.reader_more_footnote_panel).assertIsOn()
        switch(R.string.reader_more_corner_bookmark).assertIsOn()

        switch(R.string.reader_more_footnote_panel).press()
        assertThat(host.settings).isEqualTo(ReaderSettings(footnotePanel = false))
        switch(R.string.reader_more_corner_bookmark).press()
        assertThat(host.settings).isEqualTo(ReaderSettings(footnotePanel = false, cornerBookmark = false))

        switch(R.string.reader_more_footnote_panel).press()
        switch(R.string.reader_more_corner_bookmark).press()
        assertThat(host.settings).isEqualTo(ReaderSettings())
    }

    @Test
    fun `the brightness gesture is on the left edge, the right one, or off`() {
        val host = show(ReaderSheet.MoreOptions)
        choice(R.string.reader_edge_left).assertIsSelected()

        choice(R.string.reader_edge_right).press()
        assertThat(host.settings).isEqualTo(ReaderSettings(brightnessEdge = BrightnessEdge.Right))

        choice(R.string.reader_edge_off).press()
        assertThat(host.settings).isEqualTo(ReaderSettings(brightnessEdge = BrightnessEdge.Off))
        choice(R.string.reader_edge_off).assertIsSelected()

        choice(R.string.reader_edge_left).press()
        assertThat(host.settings).isEqualTo(ReaderSettings())
    }

    // Reach

    @Test
    fun `everything that can be pressed is at least 48 dp high and wide`() {
        val host = show(null)
        for (sheet in listOf(ReaderSheet.Settings, ReaderSheet.RunningLines, ReaderSheet.MoreOptions)) {
            host.actions.showSheet(sheet)

            val pressable = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            pressable.fetchSemanticsNodes().indices.forEach { index ->
                val bounds = pressable[index].getUnclippedBoundsInRoot()
                assertWithMessage("width of ${pressable[index].fetchSemanticsNode().config}")
                    .that(bounds.width.value).isAtLeast(48f)
                assertWithMessage("height of ${pressable[index].fetchSemanticsNode().config}")
                    .that(bounds.height.value).isAtLeast(48f)
            }
        }
    }
}
