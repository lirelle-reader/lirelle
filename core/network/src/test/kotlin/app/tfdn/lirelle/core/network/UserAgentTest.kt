package app.tfdn.lirelle.core.network

import com.google.common.truth.Truth.assertThat
import okhttp3.Headers
import org.junit.Test

class UserAgentTest {

    @Test
    fun `names the app, its version, Android's and the device's model`() {
        val agent = formatUserAgent(version = "0.7.0", androidRelease = "16", model = "SM-S931B")

        assertThat(agent.value).isEqualTo("Lirelle/0.7.0 (Android 16; SM-S931B)")
        assertThat(agent.app).isEqualTo("Lirelle/0.7.0")
    }

    @Test
    fun `keeps a debug version as the build wrote it`() {
        assertThat(formatUserAgent("0.7.0-5-gabc1234", "16", "Pixel 9").value)
            .isEqualTo("Lirelle/0.7.0-5-gabc1234 (Android 16; Pixel 9)")
    }

    @Test
    fun `replaces what a header cannot carry, one mark per character`() {
        val agent = formatUserAgent("0.7.0", "16", "Téléphone 📚")

        assertThat(agent.value).isEqualTo("Lirelle/0.7.0 (Android 16; T_l_phone _)")
    }

    @Test
    fun `keeps the comment's shape whatever the model says`() {
        assertThat(formatUserAgent("0.7.0", "16", "Tab (2024); \\x").value)
            .isEqualTo("Lirelle/0.7.0 (Android 16; Tab _2024__ _x)")
    }

    @Test
    fun `keeps the version a single token`() {
        assertThat(formatUserAgent("1.0 beta/2", "16", "Pixel").app).isEqualTo("Lirelle/1.0_beta_2")
    }

    @Test
    fun `says unknown for a field left blank`() {
        assertThat(formatUserAgent(" ", "", "  ").value).isEqualTo("Lirelle/unknown (Android unknown; unknown)")
    }

    @Test
    fun `is always a value a request can carry`() {
        val awkward = listOf("Ünïcødé", "\u0000\t\n", "📚", "a\u007Fb", "(;)")
        for (field in awkward) {
            val agent = formatUserAgent(field, field, field)
            Headers.Builder().add("User-Agent", agent.value)
        }
    }
}
