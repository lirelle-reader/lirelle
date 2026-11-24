package app.tfdn.lirelle

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.feature.login.R as LoginR
import app.tfdn.lirelle.feature.player.service.PlayerSessionContract
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** The reader a narration's notification asks for: handed to the screens once, from a cold or a warm start. */
@RunWith(RobolectricTestRunner::class)
class ReaderRequestIntentTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private fun launcher(): Intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
        .setAction(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)

    // An intent holds no id: its text.
    private fun notificationTap(bookId: String, flags: Int = 0): Intent =
        launcher().putExtra(PlayerSessionContract.EXTRA_OPEN_READER_BOOK_ID, bookId).addFlags(flags)

    @Test
    fun `a narration's notification asks for the reader of its book, once`() {
        val intent = notificationTap("42")

        assertThat(takeReaderRequest(intent)).isEqualTo(BookId("42"))
        assertThat(intent.hasExtra(PlayerSessionContract.EXTRA_OPEN_READER_BOOK_ID)).isFalse()
        assertThat(takeReaderRequest(intent)).isNull()
    }

    @Test
    fun `the launcher, the player's notification and no intent ask for no reader`() {
        assertThat(takeReaderRequest(launcher())).isNull()
        assertThat(takeReaderRequest(null)).isNull()
    }

    @Test
    fun `an intent replayed from the recent apps asks for no reader`() {
        val intent = notificationTap("42", Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)

        assertThat(takeReaderRequest(intent)).isNull()
        assertThat(intent.hasExtra(PlayerSessionContract.EXTRA_OPEN_READER_BOOK_ID)).isFalse()
    }

    // The activity is only created in the tests below: its screens are not composed yet, so the request is seen as
    // delivered. Composed on this device never signed in, they would drop it (see the last test); what the
    // signed-in screens do with it is ReaderRequestNavigationTest's.

    @Test
    fun `a cold start from the notification takes the request out of its intent and hands it to the screens`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, notificationTap("42")).create()

        assertThat(controller.get().intent.hasExtra(PlayerSessionContract.EXTRA_OPEN_READER_BOOK_ID)).isFalse()
        assertThat(controller.get().readerRequests.pending).isEqualTo(BookId("42"))
    }

    @Test
    fun `a tap on the notification while the app runs hands the request to the screens`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, launcher()).create()
        assertThat(controller.get().readerRequests.pending).isNull()

        controller.newIntent(notificationTap("42"))

        assertThat(controller.get().readerRequests.pending).isEqualTo(BookId("42"))
    }

    /** The notification of a profile that closed: its book is not opened for whoever is, or is not, signed in. */
    @Test
    fun `a notification of another profile than the open one opens no reader`() {
        val tap = notificationTap("42").putExtra(PlayerSessionContract.EXTRA_OPEN_READER_PROFILE_ID, "p1")
        val activity = Robolectric.buildActivity(MainActivity::class.java, tap).create().get()
        assertThat(activity.readerRequests.pending).isEqualTo(BookId("42"))

        assertThat(activity.readerRequests.take()).isNull()
        assertThat(activity.readerRequests.pending).isNull()
    }

    @Test
    fun `an activity made again from its saved state does not deliver its intent again`() {
        val intent = launcher()
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).create()
        // As if its intent still asked for the reader: only the saved state keeps it from being delivered again.
        intent.putExtra(PlayerSessionContract.EXTRA_OPEN_READER_BOOK_ID, "42")

        controller.recreate()

        assertThat(controller.get().intent.hasExtra(PlayerSessionContract.EXTRA_OPEN_READER_BOOK_ID)).isTrue()
        assertThat(controller.get().readerRequests.pending).isNull()
    }

    @Test
    fun `the app never signed in drops a cold start's request once the sign-in level shows`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java, notificationTap("42")).setup().get()
        val title = activity.getString(LoginR.string.server_title)

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()

        assertThat(activity.readerRequests.pending).isNull()
    }
}
