package app.tfdn.lirelle.feature.login

import androidx.browser.auth.AuthTabIntent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AuthTabResultTest {

    private val redirect = "bookorbit://oauth2-callback?code=code-1&state=state-1"

    @Test
    fun `a redirect uri is a redirect whatever the browser`() {
        val expected = AuthTabResult.Redirected(redirect)

        assertThat(authTabResultOf(AuthTabIntent.RESULT_OK, redirect, fromAuthTab = true)).isEqualTo(expected)
        assertThat(authTabResultOf(AuthTabIntent.RESULT_OK, redirect, fromAuthTab = false)).isEqualTo(expected)
    }

    @Test
    fun `a closed auth tab is a cancellation`() {
        assertThat(authTabResultOf(AuthTabIntent.RESULT_CANCELED, null, fromAuthTab = true))
            .isEqualTo(AuthTabResult.Cancelled)
    }

    @Test
    fun `a closed custom tab means nothing - its redirect comes through the intent filter`() {
        assertThat(authTabResultOf(AuthTabIntent.RESULT_CANCELED, null, fromAuthTab = false)).isNull()
        assertThat(authTabResultOf(AuthTabIntent.RESULT_UNKNOWN_CODE, null, fromAuthTab = false)).isNull()
        assertThat(authTabResultOf(AuthTabIntent.RESULT_OK, null, fromAuthTab = false)).isNull()
    }

    @Test
    fun `a failed or timed out verification is a failure`() {
        listOf(AuthTabIntent.RESULT_VERIFICATION_FAILED, AuthTabIntent.RESULT_VERIFICATION_TIMED_OUT).forEach { code ->
            assertThat(authTabResultOf(code, null, fromAuthTab = true)).isEqualTo(AuthTabResult.Failed)
            assertThat(authTabResultOf(code, null, fromAuthTab = false)).isEqualTo(AuthTabResult.Failed)
        }
    }

    @Test
    fun `any other answer of an auth tab is a failure`() {
        assertThat(authTabResultOf(AuthTabIntent.RESULT_UNKNOWN_CODE, null, fromAuthTab = true))
            .isEqualTo(AuthTabResult.Failed)
        assertThat(authTabResultOf(1, null, fromAuthTab = true)).isEqualTo(AuthTabResult.Failed)
        // "OK" with no redirect URI cannot complete anything.
        assertThat(authTabResultOf(AuthTabIntent.RESULT_OK, null, fromAuthTab = true))
            .isEqualTo(AuthTabResult.Failed)
    }
}
