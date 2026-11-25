package app.tfdn.lirelle.feature.player.service

import android.content.Context
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaController
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.audio.timer.SleepChoice
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.feature.player.car.ControllerKind
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

    private fun controller(packageName: String, version: Int = 0, hints: Bundle = Bundle.EMPTY) =
        MediaSession.ControllerInfo.createTestOnlyControllerInfo(packageName, 0, 0, version, 0, false, hints, false)

    private fun withSession(block: (MediaSession) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val session = MediaSession.Builder(context, FakeEngine()).setId("commands-test").build()
        try {
            block(session)
        } finally {
            session.release()
        }
    }

    private fun custom(vararg actions: String): SessionCommands =
        MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
            .apply { actions.forEach { add(SessionCommand(it, Bundle.EMPTY)) } }
            .build()

    @Test
    fun `a controller is the app's own, a car, or another app`() = withSession { session ->
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notificationHints =
            Bundle().apply { putBoolean(MediaController.KEY_MEDIA_NOTIFICATION_CONTROLLER_FLAG, true) }
        val notification = controller(context.packageName, version = 1, hints = notificationHints)

        assertThat(commands.kindOf(session, notification)).isEqualTo(ControllerKind.Own)
        assertThat(commands.kindOf(session, controller("app.tfdn.lirelle"))).isEqualTo(ControllerKind.Own)
        assertThat(commands.kindOf(session, controller("com.android.systemui"))).isEqualTo(ControllerKind.Own)
        assertThat(commands.kindOf(session, controller("com.google.android.projection.gearhead")))
            .isEqualTo(ControllerKind.Car)
        assertThat(commands.kindOf(session, controller("com.android.car.media"))).isEqualTo(ControllerKind.Car)
        assertThat(commands.kindOf(session, controller("com.example.watch"))).isEqualTo(ControllerKind.Other)
    }

    @Test
    fun `the app's own controllers and other apps are offered what they were before the car`() {
        val dismissed = MediaNotification.NOTIFICATION_DISMISSED_EVENT_KEY
        val own = PlayerCommands.sessionCommandsFor(ControllerKind.Own)
        val others = PlayerCommands.sessionCommandsFor(ControllerKind.Other)

        assertThat(own).isEqualTo(custom(*PlayerSessionContract.actions.toTypedArray(), dismissed))
        assertThat(own).isEqualTo(PlayerCommands.sessionCommandsFor(trusted = true))
        assertThat(others).isEqualTo(
            custom(
                PlayerSessionContract.ACTION_BACK,
                PlayerSessionContract.ACTION_FORWARD,
                PlayerSessionContract.ACTION_PREVIOUS_CHAPTER,
                PlayerSessionContract.ACTION_NEXT_CHAPTER,
            ),
        )
        assertThat(others).isEqualTo(PlayerCommands.sessionCommandsFor(trusted = false))
        // Neither browses the library.
        listOf(own, others).forEach {
            assertThat(it.contains(SessionCommand.COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT)).isFalse()
            assertThat(it.contains(SessionCommand.COMMAND_CODE_LIBRARY_GET_CHILDREN)).isFalse()
            assertThat(it.contains(SessionCommand.COMMAND_CODE_LIBRARY_SEARCH)).isFalse()
        }
        // Nor sets the player's items.
        listOf(ControllerKind.Own, ControllerKind.Other).forEach {
            assertThat(PlayerCommands.playerCommandsFor(it)).isEqualTo(PlayerCommands.offeredPlayerCommands)
        }
        assertThat(PlayerCommands.offeredPlayerCommands).isEqualTo(
            MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .removeAll(Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS)
                .build(),
        )
    }

    @Test
    fun `a car is offered the skips, the chapters, the next speed and the next volume, and browses`() {
        val car = PlayerCommands.sessionCommandsFor(ControllerKind.Car)
        val allowed = setOf(
            PlayerSessionContract.ACTION_BACK,
            PlayerSessionContract.ACTION_FORWARD,
            PlayerSessionContract.ACTION_PREVIOUS_CHAPTER,
            PlayerSessionContract.ACTION_NEXT_CHAPTER,
            PlayerSessionContract.ACTION_NEXT_SPEED,
            PlayerSessionContract.ACTION_LISTEN_TO_NEXT,
        )

        PlayerSessionContract.actions.forEach {
            assertThat(car.contains(SessionCommand(it, Bundle.EMPTY))).isEqualTo(it in allowed)
        }
        assertThat(car.contains(SessionCommand(MediaNotification.NOTIFICATION_DISMISSED_EVENT_KEY, Bundle.EMPTY)))
            .isFalse()
        assertThat(car.commands).containsAtLeastElementsIn(
            MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.commands,
        )
        assertThat(car.contains(SessionCommand.COMMAND_CODE_LIBRARY_GET_CHILDREN)).isTrue()
        assertThat(car.contains(SessionCommand.COMMAND_CODE_LIBRARY_SEARCH)).isTrue()
    }

    @Test
    fun `a car that connects may set the player's items, and gets the buttons every controller gets`() =
        withSession { session ->
            val speed = CommandButton.Builder(CommandButton.ICON_PLAYBACK_SPEED)
                .setSessionCommand(PlayerCommand.NextSpeed.sessionCommand)
                .setDisplayName("Speed")
                .build()
            val commands = PlayerCommands(ownPackage = "app.tfdn.lirelle", handle = {}, buttons = { listOf(speed) })

            val car = commands.onConnect(session, controller("com.google.android.projection.gearhead"))
            val watch = commands.onConnect(session, controller("com.example.watch"))
            val own = commands.onConnect(session, controller("app.tfdn.lirelle"))

            listOf(car, watch, own).forEach { assertThat(it.mediaButtonPreferences).containsExactly(speed) }
            assertThat(car.availablePlayerCommands).isEqualTo(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
            assertThat(car.availableSessionCommands).isEqualTo(PlayerCommands.sessionCommandsFor(ControllerKind.Car))
            assertThat(watch.availablePlayerCommands).isEqualTo(PlayerCommands.offeredPlayerCommands)
            // Another app is offered the button with everyone, not its command: Media3 gives it to it disabled.
            assertThat(watch.availableSessionCommands)
                .isEqualTo(PlayerCommands.sessionCommandsFor(ControllerKind.Other))
            assertThat(watch.availableSessionCommands.contains(PlayerCommand.NextSpeed.sessionCommand)).isFalse()
        }
}
