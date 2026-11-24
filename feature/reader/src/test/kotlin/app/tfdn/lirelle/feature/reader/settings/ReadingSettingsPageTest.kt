package app.tfdn.lirelle.feature.reader.settings

import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.sp
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.reader.settings.BrightnessEdge
import app.tfdn.lirelle.core.reader.settings.DefaultFontStep
import app.tfdn.lirelle.core.reader.settings.FontSizesPx
import app.tfdn.lirelle.core.reader.settings.MarginSize
import app.tfdn.lirelle.core.reader.settings.ReaderAlign
import app.tfdn.lirelle.core.reader.settings.ReaderFlow
import app.tfdn.lirelle.core.reader.settings.ReaderFont
import app.tfdn.lirelle.core.reader.settings.ReaderSettings
import app.tfdn.lirelle.core.reader.settings.ReaderTheme
import app.tfdn.lirelle.core.reader.settings.RunningSlot
import app.tfdn.lirelle.core.testing.DeviceRule
import app.tfdn.lirelle.feature.reader.HeaderPreviewTag
import app.tfdn.lirelle.feature.reader.R
import app.tfdn.lirelle.feature.reader.colours
import app.tfdn.lirelle.feature.reader.label
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import app.tfdn.lirelle.core.ui.R as UiR

/** The Reading page of the settings on a phone: every reading setting but the brightness level, and a preview. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadingSettingsPageTest {

    @get:Rule(order = 0)
    val device = DeviceRule(qualifiers = "en-w411dp-h891dp", fontScale = 1f)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var wentBack = 0

    private fun show(host: PageHost = PageHost(), inPane: Boolean = false): PageHost {
        compose.setContent {
            LirelleTheme {
                ReadingSettingsPage(host.settings, host.change, onBack = if (inPane) null else ({ wentBack++ }))
            }
        }
        return host
    }

    private fun choice(@StringRes label: Int): SemanticsNodeInteraction =
        compose.onNode(hasText(string(label)) and isSelectable())

    private fun switch(@StringRes label: Int): SemanticsNodeInteraction =
        compose.onNode(hasText(string(label)) and isToggleable())

    private fun button(@StringRes spoken: Int): SemanticsNodeInteraction =
        compose.onNodeWithContentDescription(string(spoken))

    private fun heading(@StringRes title: Int): SemanticsNodeInteraction =
        compose.onNode(hasText(string(title)) and isHeading())

    private fun SemanticsNodeInteraction.press() = performScrollTo().performClick()

    @Test
    fun `it has its title and a way back`() {
        show()

        heading(R.string.reader_reading_settings_title).assertExists()
        compose.onNodeWithContentDescription(string(UiR.string.settings_back)).performClick()

        assertThat(wentBack).isEqualTo(1)
    }

    @Test
    fun `in a pane it keeps its title and has no arrow`() {
        show(inPane = true)

        heading(R.string.reader_reading_settings_title).assertExists()
        compose.onNodeWithContentDescription(string(UiR.string.settings_back)).assertDoesNotExist()
        compose.preview().assertExists()
    }

    @Test
    fun `it shows the preview, then its three sections with every control`() {
        show()

        compose.preview().assertExists()
        compose.onNodeWithText(string(R.string.reader_preview_approximate)).assertExists()
        val headings = listOf(
            R.string.reader_settings_text,
            R.string.reader_settings_running_lines,
            R.string.reader_settings_layout_and_gestures,
        ).map { heading(it).bounds().top }
        assertThat(headings).isInStrictOrder()
        assertThat(compose.preview().bounds().bottom).isAtMost(headings.first())

        ReaderTheme.entries.forEach { choice(it.label).assertExists() }
        listOf(R.string.reader_font_serif, R.string.reader_font_sans, R.string.reader_font_dyslexic)
            .forEach { choice(it).assertExists() }
        listOf(
            R.string.reader_settings_size_larger,
            R.string.reader_settings_line_spacing_looser,
            R.string.reader_settings_margins_wider,
            R.string.reader_settings_alignment_next,
        ).forEach { button(it).assertExists() }
        switch(R.string.reader_settings_hyphenation).assertExists()
        listOf(
            R.string.reader_running_header_left,
            R.string.reader_running_header_right,
            R.string.reader_running_footer_left,
            R.string.reader_running_footer_right,
        ).forEach { compose.onNodeWithText(string(it)).assertExists() }
        listOf(R.string.reader_flow_paginated, R.string.reader_flow_scrolled).forEach { choice(it).assertExists() }
        listOf(
            R.string.reader_more_volume_keys,
            R.string.reader_more_footnote_panel,
            R.string.reader_more_corner_bookmark,
        ).forEach { switch(it).assertExists() }
        listOf(R.string.reader_edge_left, R.string.reader_edge_right, R.string.reader_edge_off)
            .forEach { choice(it).assertExists() }
    }

    @Test
    fun `it has no brightness level, which is set while reading`() {
        show()

        compose.onNodeWithText(string(R.string.reader_settings_brightness)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.reader_settings_brightness_system)).assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .fetchSemanticsNodes().let { assertThat(it).isEmpty() }
        // Nor the rows that lead to the reader's other sheets: their content is on the page.
        compose.onNodeWithText(string(R.string.reader_settings_more)).assertDoesNotExist()
    }

    @Test
    fun `nothing is shown under the title until the settings are known`() {
        val host = show(PageHost(initial = null))
        heading(R.string.reader_reading_settings_title).assertExists()
        compose.preview().assertDoesNotExist()
        compose.onNodeWithText(string(R.string.reader_settings_theme)).assertDoesNotExist()

        host.settings = ReaderSettings(theme = ReaderTheme.Sepia)

        choice(R.string.reader_theme_sepia).assertIsSelected()
    }

    @Test
    fun `a theme chosen is stored and the preview takes its colours`() {
        val host = show()

        choice(R.string.reader_theme_sepia).press()

        assertThat(host.stored.theme).isEqualTo(ReaderTheme.Sepia)
        assertThat(compose.previewState()).contains(string(R.string.reader_theme_sepia))
        assertThat(compose.sampleLayout().layoutInput.style.color)
            .isEqualTo(ReaderTheme.Sepia.colours(systemDark = false).foreground)
    }

    @Test
    fun `a larger size is stored and the preview's text grows`() {
        val host = show()

        button(R.string.reader_settings_size_larger).press()

        val size = FontSizesPx[DefaultFontStep + 1]
        assertThat(host.stored.fontSizeStep).isEqualTo(DefaultFontStep + 1)
        assertThat(compose.previewState()).contains(size.toString())
        assertThat(compose.sampleLayout().layoutInput.style.fontSize).isEqualTo(size.sp)
    }

    @Test
    fun `the other settings of the text are stored`() {
        val host = show()

        choice(R.string.reader_font_dyslexic).press()
        button(R.string.reader_settings_line_spacing_tighter).press()
        button(R.string.reader_settings_margins_wider).press()
        button(R.string.reader_settings_alignment_next).press()
        switch(R.string.reader_settings_hyphenation).press()

        val defaults = ReaderSettings()
        assertThat(host.stored).isEqualTo(
            defaults.copy(
                font = ReaderFont.Dyslexic,
                lineHeightStep = defaults.lineHeightStep - 1,
                margins = MarginSize.Wide,
                align = ReaderAlign.Justify,
                hyphenate = false,
            ),
        )
        switch(R.string.reader_settings_hyphenation).assertIsOff()
    }

    @Test
    fun `an end of the header chosen shows in the preview's header line`() {
        val host = show()

        compose.onNodeWithText(string(R.string.reader_running_header_left)).press()
        choice(R.string.reader_slot_book_title).press()

        assertThat(host.stored.header.left).isEqualTo(RunningSlot.BookTitle)
        compose.onNodeWithTag(HeaderPreviewTag)
            .assert(hasContentDescription(string(R.string.reader_sample_book_title), substring = true))
        // The list closes on a choice, as in the reader's sheet.
        choice(R.string.reader_slot_battery).assertDoesNotExist()
    }

    @Test
    fun `the layout and the gestures are stored`() {
        val host = show()

        choice(R.string.reader_flow_scrolled).press()
        switch(R.string.reader_more_volume_keys).press()
        switch(R.string.reader_more_footnote_panel).press()
        switch(R.string.reader_more_corner_bookmark).press()
        choice(R.string.reader_edge_off).press()

        assertThat(host.stored).isEqualTo(
            ReaderSettings(
                flow = ReaderFlow.Scrolled,
                volumeKeys = true,
                footnotePanel = false,
                cornerBookmark = false,
                brightnessEdge = BrightnessEdge.Off,
            ),
        )
    }
}
