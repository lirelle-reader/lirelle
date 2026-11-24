package app.tfdn.lirelle.feature.player.service

import android.content.Context
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.audio.timer.SleepChoice
import app.tfdn.lirelle.core.ids.BookId
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerCommandsTest {

    private val handled = mutableListOf<PlayerCommand>()
    private val commands =
        PlayerCommands(ownPackage = "app.tfdn.lirelle", handle = { handled += it }, buttons = { emptyList() })

    @Test
    fun `a custom command from a controller is handled as its player command`() {
        val sleep = PlayerCommand.StartSleep(SleepChoice.Minutes(15))

        assertThat(commands.dispatch(sleep.action, sleep.args())).isEqualTo(SessionResult.RESULT_SUCCESS)
        assertThat(commands.dispatch(PlayerSessionContract.ACTION_NEXT_CHAPTER, Bundle.EMPTY))
            .isEqualTo(SessionResult.RESULT_SUCCESS)

        assertThat(handled).containsExactly(sleep, PlayerCommand.NextChapter).inOrder()
    }

    @Test
    fun `the notification dismissed by the user closes the book, as stopping listening does`() {
        val dismissed = MediaNotification.NOTIFICATION_DISMISSED_EVENT_KEY

        assertThat(commands.dispatch(dismissed, Bundle.EMPTY)).isEqualTo(SessionResult.RESULT_SUCCESS)

        assertThat(handled).containsExactly(PlayerCommand.CloseBook)
        // Offered to the notification, which sends it only then; not to other apps.
        assertThat(PlayerCommands.sessionCommandsFor(trusted = true).contains(SessionCommand(dismissed, Bundle.EMPTY)))
            .isTrue()
        assertThat(PlayerCommands.sessionCommandsFor(trusted = false).contains(SessionCommand(dismissed, Bundle.EMPTY)))
            .isFalse()
    }

    @Test
    fun `an unknown one is refused and nothing is done`() {
        assertThat(commands.dispatch("elsewhere.DO", Bundle.EMPTY)).isEqualTo(SessionError.ERROR_NOT_SUPPORTED)
        assertThat(commands.dispatch(PlayerSessionContract.ACTION_LOAD_BOOK, Bundle.EMPTY))
            .isEqualTo(SessionError.ERROR_NOT_SUPPORTED)
        assertThat(handled).isEmpty()
    }

    @Test
    fun `controllers are offered every custom command of the contract`() {
        PlayerSessionContract.actions.forEach {
            assertThat(PlayerCommands.sessionCommands.contains(SessionCommand(it, Bundle.EMPTY))).isTrue()
        }
    }

    @Test
    fun `the app, its notification and the system's media controls get every command`() {
        assertThat(commands.isTrusted("app.tfdn.lirelle", isNotification = false)).isTrue()
        assertThat(commands.isTrusted("app.tfdn.lirelle", isNotification = true)).isTrue()
        assertThat(commands.isTrusted("com.android.systemui", isNotification = false)).isTrue()
        assertThat(commands.isTrusted("com.example.watch", isNotification = false)).isFalse()
    }

    @Test
    fun `other controllers get the skips and the chapters only`() {
        val others = PlayerCommands.sessionCommandsFor(trusted = false)
        val allowed = setOf(
            PlayerSessionContract.ACTION_BACK,
            PlayerSessionContract.ACTION_FORWARD,
            PlayerSessionContract.ACTION_PREVIOUS_CHAPTER,
            PlayerSessionContract.ACTION_NEXT_CHAPTER,
        )

        PlayerSessionContract.actions.forEach {
            assertThat(others.contains(SessionCommand(it, Bundle.EMPTY))).isEqualTo(it in allowed)
        }
        assertThat(PlayerCommands.sessionCommandsFor(trusted = true)).isEqualTo(PlayerCommands.sessionCommands)
    }

    @Test
    fun `no controller may set or add items, nor is offered to`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = MediaSession.Builder(context, FakeEngine()).setId("commands-test").build()
        val controller = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            "com.example.remote", 0, 0, 0, 0, false, Bundle.EMPTY, false,
        )
        val item = PlayerSessionContract.resumeItem(LastBook(BookId("42"), "The Long Road", null))
        try {
            val connection = commands.onConnect(session, controller)
            assertThat(connection.availablePlayerCommands.contains(Player.COMMAND_SET_MEDIA_ITEM)).isFalse()
            assertThat(connection.availablePlayerCommands.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS)).isFalse()
            assertThat(connection.availablePlayerCommands.contains(Player.COMMAND_PLAY_PAUSE)).isTrue()

            val set = commands.onSetMediaItems(session, controller, listOf(item), 0, 0)
            val added = commands.onAddMediaItems(session, controller, listOf(item))
            assertThat(runCatching { set.get() }.isFailure).isTrue()
            assertThat(runCatching { added.get() }.isFailure).isTrue()
        } finally {
            session.release()
        }
    }
}
