package app.tfdn.lirelle.core.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val FewMegabytes = 3_500_000L
private const val HundredsOfMegabytes = 412_000_000L
private const val AGigabyte = 1_000_000_000L
private const val OverAGigabyte = 1_080_000_000L
private const val TensOfGigabytes = 41_600_000_000L

/**
 * Sizes are written by the platform, in the language of the app: in its short form under a gigabyte, with two
 * decimals from there, where a tenth is a hundred megabytes.
 */
@RunWith(RobolectricTestRunner::class)
class FileSizesTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    @Config(qualifiers = "en")
    fun `sizes in English`() {
        assertThat(context.fileSize(FewMegabytes).plainSpaces()).isEqualTo("3.5 MB")
        assertThat(context.fileSize(HundredsOfMegabytes).plainSpaces()).isEqualTo("412 MB")
        assertThat(context.fileSize(AGigabyte).plainSpaces()).isEqualTo("1.00 GB")
        assertThat(context.fileSize(OverAGigabyte).plainSpaces()).isEqualTo("1.08 GB")
        assertThat(context.fileSize(TensOfGigabytes).plainSpaces()).isEqualTo("41.60 GB")
    }

    @Test
    @Config(qualifiers = "fr")
    fun `sizes in French`() {
        assertThat(context.fileSize(FewMegabytes).plainSpaces()).isEqualTo("3,5 Mo")
        assertThat(context.fileSize(HundredsOfMegabytes).plainSpaces()).isEqualTo("412 Mo")
        assertThat(context.fileSize(AGigabyte).plainSpaces()).isEqualTo("1,00 Go")
        assertThat(context.fileSize(OverAGigabyte).plainSpaces()).isEqualTo("1,08 Go")
        assertThat(context.fileSize(TensOfGigabytes).plainSpaces()).isEqualTo("41,60 Go")
    }

    @Test
    @Config(qualifiers = "fr")
    fun `a composable gets the size of the screen's language`() {
        var shown = ""
        compose.setContent { shown = fileSize(OverAGigabyte) }

        compose.runOnIdle { assertThat(shown.plainSpaces()).isEqualTo("1,08 Go") }
    }
}

/** The platform puts a no-break space, and marks of direction, between the number and its unit. */
private fun String.plainSpaces(): String =
    filterNot { it == '‎' || it == '‏' }.replace(' ', ' ').replace(' ', ' ')
