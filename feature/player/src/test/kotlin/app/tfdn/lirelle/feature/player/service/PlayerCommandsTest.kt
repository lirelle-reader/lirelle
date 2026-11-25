package app.tfdn.lirelle.feature.player.service

import android.content.Context
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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
import app.tfdn.lirelle.feature.player.car.CarAnswer
import app.tfdn.lirelle.feature.player.car.CarAsks
import app.tfdn.lirelle.feature.player.car.CarNode
import app.tfdn.lirelle.feature.player.car.CarPlace
import app.tfdn.lirelle.feature.player.car.CarSection
import app.tfdn.lirelle.feature.player.car.CarTexts
import app.tfdn.lirelle.feature.player.car.ControllerKind
import app.tfdn.lirelle.feature.player.car.FakeCarShelves
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
        val command = SessionCommand(dismissed, Bundle.EMPTY)
        assertThat(PlayerCommands.sessionCommandsFor(ControllerKind.Own).contains(command)).isTrue()
        assertThat(PlayerCommands.sessionCommandsFor(ControllerKind.Other).contains(command)).isFalse()
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
        val others = PlayerCommands.sessionCommandsFor(ControllerKind.Other)
        val allowed = setOf(
            PlayerSessionContract.ACTION_BACK,
            PlayerSessionContract.ACTION_FORWARD,
            PlayerSessionContract.ACTION_PREVIOUS_CHAPTER,
            PlayerSessionContract.ACTION_NEXT_CHAPTER,
        )

        PlayerSessionContract.actions.forEach {
            assertThat(others.contains(SessionCommand(it, Bundle.EMPTY))).isEqualTo(it in allowed)
        }
        assertThat(PlayerCommands.sessionCommandsFor(ControllerKind.Own)).isEqualTo(PlayerCommands.sessionCommands)
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
        assertThat(commands.kindOf(session, controller("com.android.car.media"))).isEqualTo(ControllerKind.Other)
        assertThat(commands.kindOf(session, controller("com.example.watch"))).isEqualTo(ControllerKind.Other)
    }

    @Test
    fun `the app's own controllers and other apps are offered what they were before the car`() {
        val dismissed = MediaNotification.NOTIFICATION_DISMISSED_EVENT_KEY
        val own = PlayerCommands.sessionCommandsFor(ControllerKind.Own)
        val others = PlayerCommands.sessionCommandsFor(ControllerKind.Other)

        assertThat(own).isEqualTo(custom(*PlayerSessionContract.actions.toTypedArray(), dismissed))
        assertThat(others).isEqualTo(
            custom(
                PlayerSessionContract.ACTION_BACK,
                PlayerSessionContract.ACTION_FORWARD,
                PlayerSessionContract.ACTION_PREVIOUS_CHAPTER,
                PlayerSessionContract.ACTION_NEXT_CHAPTER,
            ),
        )
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

    @Test
    fun `what a car sets or adds is what its asks answer, and another app is refused as before`() =
        withSession { session ->
            val shelves = FakeCarShelves()
            val context = ApplicationProvider.getApplicationContext<Context>()
            val asks = CarAsks(shelves, CarTexts(context.resources), CoroutineScope(Dispatchers.Unconfined))
            val commands = PlayerCommands("app.tfdn.lirelle", handle = {}, buttons = { emptyList() }, asks = asks)
            val tapped = listOf(MediaItem.Builder().setMediaId("book/7").build())
            val car = controller("com.google.android.projection.gearhead")
            val watch = controller("com.example.watch")

            val set = commands.onSetMediaItems(session, car, tapped, C.INDEX_UNSET, C.TIME_UNSET).get()
            val added = commands.onAddMediaItems(session, car, tapped).get()

            assertThat(set.mediaItems.map { it.mediaId }).containsExactly("start/7")
            assertThat(set.startIndex).isEqualTo(0)
            assertThat(set.startPositionMs).isEqualTo(C.TIME_UNSET)
            assertThat(added.map { it.mediaId }).containsExactly("start/7")
            listOf(watch, controller("app.tfdn.lirelle"), controller("com.android.systemui")).forEach { other ->
                assertThat(runCatching { commands.onSetMediaItems(session, other, tapped, 0, 0).get() }.isFailure)
                    .isTrue()
                assertThat(runCatching { commands.onAddMediaItems(session, other, tapped).get() }.isFailure).isTrue()
            }
        }

    @Test
    fun `a car's play with nothing loaded starts what its asks answer, anyone else's the last book as before`() =
        withSession { session ->
            val shelves = FakeCarShelves()
            shelves.places[CarPlace.Tab.Home] = CarAnswer.Nodes(
                listOf(CarNode.Book(BookId("2"), "Begun", null, CarSection.Continue)),
            )
            val context = ApplicationProvider.getApplicationContext<Context>()
            val asks = CarAsks(shelves, CarTexts(context.resources), CoroutineScope(Dispatchers.Unconfined))
            var resumed = 0
            val commands = PlayerCommands("app.tfdn.lirelle", {}, { emptyList() }, asks = asks) {
                resumed++
                Futures.immediateFailedFuture(UnsupportedOperationException("No book to resume"))
            }

            val car = commands
                .onPlaybackResumption(session, controller("com.google.android.projection.gearhead"), true).get()

            // No last book: the first book to continue.
            assertThat(car.mediaItems.map { it.mediaId }).containsExactly("start/2")
            assertThat(resumed).isEqualTo(0)
            listOf(controller("com.android.systemui"), controller("com.example.headset")).forEach { other ->
                assertThat(runCatching { commands.onPlaybackResumption(session, other, true).get() }.isFailure)
                    .isTrue()
            }
            assertThat(resumed).isEqualTo(2)
        }
}
