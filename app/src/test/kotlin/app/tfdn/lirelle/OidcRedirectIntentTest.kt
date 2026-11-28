package app.tfdn.lirelle

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
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

    /** Audiobookshelf's sign-in returns to Lirelle's own address, or else to the one of the official app. */
    @Test
    fun `an Audiobookshelf return carries its uri, to either address`() {
        listOf(
            "lirelle://oauth?code=code-1&state=state-1",
            "audiobookshelf://oauth?code=code-1&state=state-1",
            "lirelle://oauth?code=undefined&state=state-1",
        ).forEach { assertThat(oidcRedirectOf(view(it))).isEqualTo(it) }
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
        assertThat(oidcRedirectOf(view("lirelle://elsewhere?code=code-1&state=state-1"))).isNull()
        assertThat(oidcRedirectOf(view("lirelle://oauth2-callback?code=code-1&state=state-1"))).isNull()
        assertThat(oidcRedirectOf(view("bookorbit://oauth?code=code-1&state=state-1"))).isNull()
        assertThat(oidcRedirectOf(view("audiobookshelf://elsewhere?code=code-1&state=state-1"))).isNull()
        assertThat(oidcRedirectOf(view("https://books.example.com/oauth?code=code-1&state=state-1"))).isNull()
    }

    /** Each address is compared whole, before its query: a path, a user or a port makes another one. */
    @Test
    fun `a return address with anything more carries no redirect`() {
        listOf(
            "lirelle://oauth/x?code=code-1&state=state-1",
            "lirelle://oauth/?code=code-1&state=state-1",
            "audiobookshelf://oauth/x?code=code-1&state=state-1",
            "lirelle://user@oauth?code=code-1&state=state-1",
            "bookorbit://oauth2-callback/x?code=code-1&state=state-1",
        ).forEach { assertThat(oidcRedirectOf(view(it))).isNull() }
    }

    /** What the browser's return finds: the activity, by the manifest's filters, for the three addresses only. */
    @Test
    fun `the browser's returns open the app, other links of its schemes do not`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun opensTheApp(uri: String): Boolean {
            val intent = view(uri).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(context.packageName)
            return context.packageManager.queryIntentActivities(intent, 0)
                .any { it.activityInfo.name == MainActivity::class.java.name }
        }

        assertThat(opensTheApp(redirect)).isTrue()
        assertThat(opensTheApp("lirelle://oauth?code=code-1&state=state-1")).isTrue()
        assertThat(opensTheApp("audiobookshelf://oauth?code=code-1&state=state-1")).isTrue()
        assertThat(opensTheApp("lirelle://elsewhere?code=code-1&state=state-1")).isFalse()
        assertThat(opensTheApp("bookorbit://oauth?code=code-1&state=state-1")).isFalse()
        assertThat(opensTheApp("lirelle://oauth2-callback?code=code-1&state=state-1")).isFalse()
    }

    @Test
    fun `no intent carries no redirect`() {
        assertThat(oidcRedirectOf(null)).isNull()
    }
}
