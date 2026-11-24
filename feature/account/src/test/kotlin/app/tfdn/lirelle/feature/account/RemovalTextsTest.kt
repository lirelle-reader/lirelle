package app.tfdn.lirelle.feature.account

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.ui.fileSize
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val Bytes = 1_400_000_000L

/** The sentences of the removal's confirmation, built from what the removal takes, in both languages. */
@RunWith(RobolectricTestRunner::class)
class RemovalTextsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun warning(counts: PendingCounts) = pendingWarning(context.resources, counts)

    private fun lines(cost: RemovalCost) = removalLines(context, cost)

    private val size get() = context.fileSize(Bytes)

    @Test
    fun `nothing waiting gives no warning`() {
        assertThat(warning(PendingCounts())).isNull()
    }

    @Test
    fun `one kind, one item`() {
        assertThat(warning(PendingCounts(bookmarks = 1)))
            .isEqualTo("1 bookmark has not been sent to the server yet: it will be lost.")
    }

    @Test
    fun `one kind, several items`() {
        assertThat(warning(PendingCounts(positions = 2)))
            .isEqualTo("2 reading positions have not been sent to the server yet: they will be lost.")
    }

    @Test
    fun `two kinds of one item each are several`() {
        assertThat(warning(PendingCounts(positions = 1, highlights = 1)))
            .isEqualTo("1 reading position and 1 highlight have not been sent to the server yet: they will be lost.")
    }

    @Test
    fun `every kind, in a fixed order`() {
        assertThat(warning(PendingCounts(positions = 1, sessions = 3, bookmarks = 2, highlights = 4))).isEqualTo(
            "1 reading position, 3 reading sessions, 2 bookmarks and 4 highlights have not been sent to the " +
                "server yet: they will be lost.",
        )
    }

    @Test
    @Config(qualifiers = "fr")
    fun `in French, one item`() {
        assertThat(warning(PendingCounts(bookmarks = 1)))
            .isEqualTo("Un changement n'a pas encore été envoyé au serveur et sera perdu : 1 marque-page.")
    }

    @Test
    @Config(qualifiers = "fr")
    fun `in French, every kind, the list last`() {
        assertThat(warning(PendingCounts(positions = 1, sessions = 3, bookmarks = 2, highlights = 4))).isEqualTo(
            "Des changements n'ont pas encore été envoyés au serveur et seront perdus : 1 position de lecture, " +
                "3 sessions de lecture, 2 marque-pages et 4 surlignages.",
        )
    }

    @Test
    fun `the current profile with downloads and writes at risk has both lines, counted`() {
        val cost = RemovalCost.Counted(books = 12, bytes = Bytes, lost = PendingCounts(positions = 2, highlights = 1))

        assertThat(lines(cost)).isEqualTo(
            RemovalLines(
                kept = "12 downloaded books ($size) will be deleted from this device.",
                atRisk = "2 reading positions and 1 highlight have not been sent to the server yet: " +
                    "they will be lost.",
            ),
        )
    }

    @Test
    @Config(qualifiers = "fr")
    fun `in French, the current profile with downloads and writes at risk`() {
        val cost = RemovalCost.Counted(books = 12, bytes = Bytes, lost = PendingCounts(positions = 2, highlights = 1))

        assertThat(lines(cost)).isEqualTo(
            RemovalLines(
                kept = "12 livres téléchargés ($size) seront supprimés de l'appareil.",
                atRisk = "Des changements n'ont pas encore été envoyés au serveur et seront perdus : " +
                    "2 positions de lecture et 1 surlignage.",
            ),
        )
    }

    @Test
    fun `one downloaded book is said in the singular`() {
        assertThat(lines(RemovalCost.Counted(books = 1, bytes = Bytes)).kept)
            .isEqualTo("1 downloaded book ($size) will be deleted from this device.")
    }

    @Test
    @Config(qualifiers = "fr")
    fun `in French, one downloaded book is said in the singular`() {
        assertThat(lines(RemovalCost.Counted(books = 1, bytes = Bytes)).kept)
            .isEqualTo("1 livre téléchargé ($size) sera supprimé de l'appareil.")
    }

    @Test
    fun `the current profile with nothing on the device has no line`() {
        assertThat(lines(RemovalCost.Counted())).isEqualTo(RemovalLines(kept = null, atRisk = null))
    }

    @Test
    fun `a profile that sleeps has one line, without numbers`() {
        assertThat(lines(RemovalCost.NotCounted)).isEqualTo(
            RemovalLines(kept = "Its downloads and anything not sent yet will be erased with it.", atRisk = null),
        )
    }

    @Test
    @Config(qualifiers = "fr")
    fun `in French, a profile that sleeps has one line, without numbers`() {
        assertThat(lines(RemovalCost.NotCounted)).isEqualTo(
            RemovalLines(
                kept = "Ses téléchargements et ce qui n'a pas encore été envoyé seront effacés avec lui.",
                atRisk = null,
            ),
        )
    }

    @Test
    fun `the question, and what it says of the current profile's removal`() {
        assertThat(context.getString(R.string.profiles_remove_title)).isEqualTo("Remove this profile?")
        assertThat(context.getString(R.string.profiles_remove_body)).isEqualTo(
            "This profile will be signed out and everything the device keeps for it will be erased. " +
                "Nothing changes on the server.",
        )
    }

    @Test
    @Config(qualifiers = "fr")
    fun `in French, the question, and what it says of the current profile's removal`() {
        assertThat(context.getString(R.string.profiles_remove_title)).isEqualTo("Supprimer ce profil ?")
        assertThat(context.getString(R.string.profiles_remove_body)).isEqualTo(
            "Ce profil sera déconnecté et tout ce que l'appareil garde pour lui sera effacé. " +
                "Rien ne change sur le serveur.",
        )
    }

    @Test
    fun `a profile that sleeps is not said to be signed out, its server not being told`() {
        assertThat(context.getString(R.string.profiles_remove_body_sleeping)).isEqualTo(
            "Everything the device keeps for this profile will be erased. Nothing changes on the server.",
        )
    }

    @Test
    @Config(qualifiers = "fr")
    fun `in French, a profile that sleeps is not said to be signed out`() {
        assertThat(context.getString(R.string.profiles_remove_body_sleeping)).isEqualTo(
            "Tout ce que l'appareil garde pour ce profil sera effacé. Rien ne change sur le serveur.",
        )
    }
}
