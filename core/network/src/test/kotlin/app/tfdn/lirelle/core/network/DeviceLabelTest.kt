package app.tfdn.lirelle.core.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeviceLabelTest {

    @Test
    fun `appends the app name`() {
        assertThat(formatDeviceLabel("Galaxy Tab S10+")).isEqualTo("Galaxy Tab S10+ (Lirelle)")
    }

    @Test
    fun `trims the device name`() {
        assertThat(formatDeviceLabel("  Phone  ")).isEqualTo("Phone (Lirelle)")
    }

    @Test
    fun `never exceeds the server limit of 100 characters`() {
        val label = formatDeviceLabel("x".repeat(300))

        assertThat(label).hasLength(100)
        assertThat(label).endsWith(" (Lirelle)")
    }

    @Test
    fun `truncation never splits a character made of two code units`() {
        // 89 letters, then an emoji that straddles the 90-character cut.
        val label = formatDeviceLabel("x".repeat(89) + "\uD83D\uDCDA" + "tail")

        assertThat(label).isEqualTo("x".repeat(89) + " (Lirelle)")
    }

    @Test
    fun `a two-unit character that fits is kept whole`() {
        val label = formatDeviceLabel("x".repeat(88) + "\uD83D\uDCDA" + "tail")

        assertThat(label).isEqualTo("x".repeat(88) + "\uD83D\uDCDA" + " (Lirelle)")
        assertThat(label).hasLength(100)
    }
}
