package app.tfdn.lirelle.feature.reader

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Looper
import android.provider.Settings
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class RunningLinesTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun millis(hour: Int, minute: Int, second: Int = 0) =
        LocalDateTime.of(2026, 11, 14, hour, minute, second).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `two slots that fit keep their widths`() {
        assertThat(shareWidth(available = 300, left = 100, right = 50)).isEqualTo(100 to 50)
    }

    @Test
    fun `a long slot leaves a short one whole`() {
        assertThat(shareWidth(available = 300, left = 500, right = 50)).isEqualTo(250 to 50)
        assertThat(shareWidth(available = 300, left = 50, right = 500)).isEqualTo(50 to 250)
    }

    @Test
    fun `two long slots get a half each`() {
        assertThat(shareWidth(available = 300, left = 500, right = 400)).isEqualTo(150 to 150)
    }

    @Test
    fun `beside go back each slot gets half of what is left, less the gap`() {
        assertThat(sideWidth(available = 300, centre = 100, gap = 10)).isEqualTo(90)
        assertThat(sideWidth(available = 301, centre = 100, gap = 10)).isEqualTo(90)
    }

    @Test
    fun `a line too narrow for go back leaves its slots no room, never a negative one`() {
        assertThat(sideWidth(available = 100, centre = 100, gap = 10)).isEqualTo(0)
    }

    @Test
    fun `the time follows the device's 24-hour setting`() {
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "24")
        assertThat(timeText(context, millis(15, 7))).isEqualTo("15:07")

        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "12")
        assertThat(timeText(context, millis(15, 7))).startsWith("3:07")
    }

    @Test
    fun `the time changes with the minute`() {
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "24")
        var now = millis(15, 7, second = 30)
        compose.mainClock.autoAdvance = false
        compose.setContent { Text(rememberTimeText(now = { now }).orEmpty()) }
        compose.onNodeWithText("15:07").assertExists()

        now = millis(15, 8)
        compose.mainClock.advanceTimeBy(31_000)

        compose.onNodeWithText("15:08").assertExists()
    }

    @Test
    fun `the battery is read from the system, and followed`() {
        fun battery(level: Int) = Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_LEVEL, level)
            .putExtra(BatteryManager.EXTRA_SCALE, 200)
        context.sendStickyBroadcast(battery(84))
        compose.setContent { Text(rememberBatteryPercent()?.toString() ?: "?") }
        compose.onNodeWithText("42").assertExists()

        context.sendStickyBroadcast(battery(40))
        shadowOf(Looper.getMainLooper()).idle()

        compose.onNodeWithText("20").assertExists()
    }
}
