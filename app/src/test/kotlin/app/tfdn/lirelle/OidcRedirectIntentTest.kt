package app.tfdn.lirelle

import android.content.Intent
import androidx.core.net.toUri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OidcRedirectIntentTest {

    private val redirect = "bookorbit://oauth2-callback?code=code-1&state=state-1"

    private fun view(uri: String, flags: Int = 0) = Intent(Intent.ACTION_VIEW, uri.toUri()).addFlags(flags)

    @Test
    fun `an oidc redirect intent carries its uri`() {
        assertThat(oidcRedirectOf(view(redirect))).isEqualTo(redirect)
    }

    @Test
    fun `an intent replayed from the recent apps carries no redirect`() {
        assertThat(oidcRedirectOf(view(redirect, Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))).isNull()
    }

    @Test
    fun `the launcher intent carries no redirect`() {
        assertThat(oidcRedirectOf(Intent(Intent.ACTION_MAIN))).isNull()
    }

    @Test
    fun `another link carries no redirect`() {
        assertThat(oidcRedirectOf(view("https://books.example.com/oauth2-callback?code=code-1"))).isNull()
        assertThat(oidcRedirectOf(view("bookorbit://elsewhere?code=code-1&state=state-1"))).isNull()
    }

    @Test
    fun `no intent carries no redirect`() {
        assertThat(oidcRedirectOf(null)).isNull()
    }
}
