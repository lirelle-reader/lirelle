package app.tfdn.lirelle.feature.player.service

import androidx.media3.session.CommandButton
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.audio.settings.PlayerSettings
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.audio.timer.SleepChoice
import app.tfdn.lirelle.core.audio.timer.SleepState
import app.tfdn.lirelle.core.library.model.NextVolume
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class PlayerNotificationTest {

    private val texts = PlayerTexts(ApplicationProvider.getApplicationContext<android.content.Context>().resources)
    private val book = SessionBook(BookId("42"), "The Long Road", emptyList(), emptyList(), null, 160_000)

    private fun CommandButton.action() = sessionCommand?.customAction

    @Test
    fun `five buttons - previous chapter, back, play-pause from the player, forward, next chapter`() {
        val buttons = PlayerNotification.buttons(texts, PlayerSettings(), finished = null)

        assertThat(buttons.map { it.action() }).containsExactly(
            PlayerSessionContract.ACTION_PREVIOUS_CHAPTER,
            PlayerSessionContract.ACTION_BACK,
            PlayerSessionContract.ACTION_FORWARD,
            PlayerSessionContract.ACTION_NEXT_CHAPTER,
        ).inOrder()
        assertThat(buttons.map { it.displayName.toString() })
            .containsExactly("Previous chapter", "Back 15 seconds", "Forward 30 seconds", "Next chapter").inOrder()
    }

    @Test
    fun `back and forward take the slots of the compact view, the chapters the overflow`() {
        val buttons = PlayerNotification.buttons(texts, PlayerSettings(), finished = null)
            .associateBy { it.action() }

        assertThat(buttons.getValue(PlayerSessionContract.ACTION_BACK).slots.get(0)).isEqualTo(CommandButton.SLOT_BACK)
        assertThat(buttons.getValue(PlayerSessionContract.ACTION_FORWARD).slots.get(0))
            .isEqualTo(CommandButton.SLOT_FORWARD)
        assertThat(buttons.getValue(PlayerSessionContract.ACTION_PREVIOUS_CHAPTER).slots.get(0))
            .isEqualTo(CommandButton.SLOT_OVERFLOW)
        assertThat(buttons.getValue(PlayerSessionContract.ACTION_NEXT_CHAPTER).slots.get(0))
            .isEqualTo(CommandButton.SLOT_OVERFLOW)
    }

    @Test
    fun `the skip icons follow the intervals when one is drawn for them`() {
        val tens = PlayerNotification.buttons(texts, PlayerSettings(backSeconds = 10, forwardSeconds = 10), null)
            .associateBy { it.action() }
        val odd = PlayerNotification.buttons(texts, PlayerSettings(backSeconds = 20, forwardSeconds = 45), null)
            .associateBy { it.action() }

        assertThat(tens.getValue(PlayerSessionContract.ACTION_BACK).icon).isEqualTo(CommandButton.ICON_SKIP_BACK_10)
        assertThat(tens.getValue(PlayerSessionContract.ACTION_FORWARD).icon)
            .isEqualTo(CommandButton.ICON_SKIP_FORWARD_10)
        assertThat(odd.getValue(PlayerSessionContract.ACTION_BACK).icon).isEqualTo(CommandButton.ICON_SKIP_BACK)
        assertThat(odd.getValue(PlayerSessionContract.ACTION_FORWARD).displayName.toString())
            .isEqualTo("Forward 45 seconds")
    }

    @Test
    fun `a finished book offers the next one when there is one, and nothing else`() {
        val withNext =
            PlayerNotification.buttons(texts, PlayerSettings(), FinishedBook(NextVolume(BookId("43"), "Next", "2")))

        assertThat(withNext.map { it.action() }).containsExactly(PlayerSessionContract.ACTION_LISTEN_TO_NEXT)
        assertThat(withNext.single().displayName.toString()).isEqualTo("Listen to the next one")
        assertThat(PlayerNotification.buttons(texts, PlayerSettings(), FinishedBook(null))).isEmpty()
    }

    @Test
    fun `the sleep timer's time left follows the chapter's title`() {
        val timed =
            PlayerSessionExtras(book = book, sleep = SleepState(SleepChoice.Minutes(30), 22 * 60_000 + 1, false))
        val chapter = PlayerSessionExtras(book = book, sleep = SleepState(SleepChoice.EndOfChapter, null, false))

        assertThat(PlayerNotification.override(texts, timed, "Middle").artist.toString())
            .isEqualTo("Middle · Stops in 23 min")
        assertThat(PlayerNotification.override(texts, chapter, "Middle").artist.toString())
            .isEqualTo("Middle · Stops at the end of the chapter")
        assertThat(PlayerNotification.override(texts, PlayerSessionExtras(book = book), "Middle"))
            .isEqualTo(ChapterPlayer.Override())
    }

    @Test
    fun `a finished book says so, and that the next volume is there`() {
        val finished = PlayerSessionExtras(book = book, finished = FinishedBook(NextVolume(BookId("43"), "Next", "2")))

        val override = PlayerNotification.override(texts, finished, "Closing")

        assertThat(override.title.toString()).isEqualTo("The Long Road — finished")
        assertThat(override.artist.toString()).isEqualTo("The next volume is available")
        assertThat(PlayerNotification.override(texts, finished.copy(finished = FinishedBook(null)), "Closing").artist)
            .isNull()
    }

    @Test
    fun `a narration's end keeps the book's title over "Narration finished", without buttons`() {
        val ended = PlayerSessionExtras(book = book, narrationFileId = FileId("1"), narrationFinished = true)

        val override = PlayerNotification.override(texts, ended, "Chapter 7")

        assertThat(override.title).isNull()
        assertThat(override.artist.toString()).isEqualTo("Narration finished")
        assertThat(PlayerNotification.buttons(texts, PlayerSettings(), null, narrationFinished = true)).isEmpty()
    }

    @Test
    fun `a narration shows its section's title, the sleep timer after it`() {
        val narrating = PlayerSessionExtras(book = book, narrationFileId = FileId("1"))
        val sleeping = narrating.copy(sleep = SleepState(SleepChoice.EndOfChapter, null, false))

        assertThat(PlayerNotification.override(texts, narrating, "Section 3").artist.toString())
            .isEqualTo("Section 3")
        assertThat(PlayerNotification.override(texts, sleeping, "Section 3").artist.toString())
            .isEqualTo("Section 3 · Stops at the end of the chapter")
    }

    @Test
    @Config(qualifiers = "fr")
    fun `a narration in French`() {
        val frenchTexts = PlayerTexts(ApplicationProvider.getApplicationContext<android.content.Context>().resources)
        val ended = PlayerSessionExtras(book = book, narrationFileId = FileId("1"), narrationFinished = true)

        assertThat(PlayerNotification.override(frenchTexts, ended, "Chapitre 7").artist.toString())
            .isEqualTo("Narration terminée")
        assertThat(frenchTexts.narration.section(12)).isEqualTo("Section 12")
    }

    @Test
    @Config(qualifiers = "fr")
    fun `in French`() {
        val frenchTexts = PlayerTexts(ApplicationProvider.getApplicationContext<android.content.Context>().resources)
        val timed = PlayerSessionExtras(book = book, sleep = SleepState(SleepChoice.Minutes(5), 60_000, false))

        assertThat(PlayerNotification.override(frenchTexts, timed, "Milieu").artist.toString())
            .isEqualTo("Milieu · Arrêt dans 1 min")
        assertThat(PlayerNotification.buttons(frenchTexts, PlayerSettings(), null).map { it.displayName.toString() })
            .containsExactly(
                "Chapitre précédent",
                "Reculer de 15 secondes",
                "Avancer de 30 secondes",
                "Chapitre suivant",
            )
        assertThat(frenchTexts.part(3)).isEqualTo("Partie 3")
    }

    @Test
    fun `the car gets the same four buttons and the speed, last`() {
        val buttons = PlayerNotification.buttons(texts, PlayerSettings(), finished = null, speed = 1.25f)

        assertThat(buttons.map { it.action() }).containsExactly(
            PlayerSessionContract.ACTION_PREVIOUS_CHAPTER,
            PlayerSessionContract.ACTION_BACK,
            PlayerSessionContract.ACTION_FORWARD,
            PlayerSessionContract.ACTION_NEXT_CHAPTER,
            PlayerSessionContract.ACTION_NEXT_SPEED,
        ).inOrder()
        assertThat(buttons.dropLast(1)).isEqualTo(PlayerNotification.buttons(texts, PlayerSettings(), null))
        assertThat(buttons.last().slots.get(0)).isEqualTo(CommandButton.SLOT_OVERFLOW)
    }

    @Test
    fun `the speed button says the speed, without a needless zero`() {
        fun name(speed: Float) =
            PlayerNotification.buttons(texts, PlayerSettings(), null, speed = speed).last().displayName.toString()

        assertThat(name(1f)).isEqualTo("Speed: 1×")
        assertThat(name(1.25f)).isEqualTo("Speed: 1.25×")
        assertThat(name(2f)).isEqualTo("Speed: 2×")
    }

    @Test
    @Config(qualifiers = "fr")
    fun `the speed button in French`() {
        val frenchTexts = PlayerTexts(ApplicationProvider.getApplicationContext<android.content.Context>().resources)
        fun name(speed: Float) = PlayerNotification.buttons(frenchTexts, PlayerSettings(), null, speed = speed)
            .last().displayName.toString()

        // A non-breaking space before the colon.
        assertThat(name(1f)).isEqualTo("Vitesse\u00A0: 1×")
        assertThat(name(1.25f)).isEqualTo("Vitesse\u00A0: 1,25×")
        assertThat(name(2f)).isEqualTo("Vitesse\u00A0: 2×")
    }

    @Test
    fun `the speed's icon is the nearest one drawn, else the plain one`() {
        fun icon(speed: Float) = PlayerNotification.buttons(texts, PlayerSettings(), null, speed = speed).last().icon

        assertThat(icon(1f)).isEqualTo(CommandButton.ICON_PLAYBACK_SPEED_1_0)
        assertThat(icon(1.25f)).isEqualTo(CommandButton.ICON_PLAYBACK_SPEED_1_2)
        assertThat(icon(1.5f)).isEqualTo(CommandButton.ICON_PLAYBACK_SPEED_1_5)
        assertThat(icon(2f)).isEqualTo(CommandButton.ICON_PLAYBACK_SPEED_2_0)
        assertThat(icon(0.75f)).isEqualTo(CommandButton.ICON_PLAYBACK_SPEED_0_8)
        assertThat(icon(1.1f)).isEqualTo(CommandButton.ICON_PLAYBACK_SPEED)
        assertThat(icon(3f)).isEqualTo(CommandButton.ICON_PLAYBACK_SPEED)
    }

    @Test
    fun `at the end the car is offered the next one as everyone, and nothing at a narration's end`() {
        val next = FinishedBook(NextVolume(BookId("43"), "Next", "2"))

        assertThat(PlayerNotification.buttons(texts, PlayerSettings(), next, speed = 1.5f))
            .isEqualTo(PlayerNotification.buttons(texts, PlayerSettings(), next))
        assertThat(PlayerNotification.buttons(texts, PlayerSettings(), FinishedBook(null), speed = 1.5f)).isEmpty()
        assertThat(PlayerNotification.buttons(texts, PlayerSettings(), null, narrationFinished = true, speed = 1.5f))
            .isEmpty()
    }

    @Test
    fun `when playback may not start from the background, a notification asks for a tap`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val notification = PlayerNotification.continueNotification(context, "The Long Road", null)

        assertThat(notification.extras.getString(androidx.core.app.NotificationCompat.EXTRA_TITLE))
            .isEqualTo("Tap to continue listening")
        assertThat(notification.extras.getString(androidx.core.app.NotificationCompat.EXTRA_TEXT))
            .isEqualTo("The Long Road")
    }

    @Test
    fun `once playback really starts, the notification asking for a tap goes`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager.notify(
            PlayerNotification.ContinueId,
            PlayerNotification.continueNotification(context, "The Long Road", null),
        )

        PlayerNotification.dismissContinue(context)

        assertThat(org.robolectric.Shadows.shadowOf(manager).allNotifications).isEmpty()
    }
}
