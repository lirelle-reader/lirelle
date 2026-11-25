package app.tfdn.lirelle.feature.player.car

import android.content.Context
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.SessionError
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The two ways a car is told why a place lists nothing: as an error it words, or as a line of the list. */
@RunWith(AndroidJUnit4::class)
class CarNoticesTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val texts get() = CarTexts(context.resources)
    private val link = FakeCarLink()

    @Test
    fun `the notices in English`() {
        assertThat(CarNotice.entries.map(texts::notice)).containsExactly(
            "This profile has nothing to listen to.",
            "Open Lirelle on the phone to sign in again.",
            "The server cannot be reached.",
        ).inOrder()
    }

    @Test
    @Config(qualifiers = "fr")
    fun `the notices in French`() {
        assertThat(CarNotice.entries.map(texts::notice)).containsExactly(
            "Ce profil n’a rien à écouter.",
            "Ouvrez Lirelle sur le téléphone pour vous reconnecter.",
            "Le serveur est injoignable.",
        ).inOrder()
    }

    @Test
    fun `said as an error, the list fails and the car is sent the sentence`() {
        val result = NoticeAsError(texts).answer(CarNotice.NothingToListen, link)

        assertThat(result.resultCode).isEqualTo(SessionError.ERROR_NOT_SUPPORTED)
        assertThat(result.value).isNull()
        assertThat(result.sessionError?.message).isEqualTo("This profile has nothing to listen to.")
        assertThat(link.said.map { it.code to it.message })
            .containsExactly(SessionError.ERROR_NOT_SUPPORTED to "This profile has nothing to listen to.")
    }

    @Test
    fun `said as an error, each notice has its code, and none that Media3 would repeat by itself`() {
        val codes = CarNotice.entries.associateWith { NoticeAsError(texts).answer(it, link).resultCode }

        assertThat(codes).containsExactly(
            CarNotice.NothingToListen, SessionError.ERROR_NOT_SUPPORTED,
            CarNotice.SignInNeeded, SessionError.ERROR_PERMISSION_DENIED,
            CarNotice.Unreachable, SessionError.ERROR_IO,
        )
        assertThat(link.said.map { it.message }).isEqualTo(CarNotice.entries.map(texts::notice))
    }

    @Test
    fun `said as a line, the list has one line with the sentence, neither played nor browsed`() {
        val result = NoticeAsLine(texts).answer(CarNotice.SignInNeeded, link)

        assertThat(result.resultCode).isEqualTo(LibraryResult.RESULT_SUCCESS)
        val line = checkNotNull(result.value).single()
        assertThat(line.mediaMetadata.title.toString()).isEqualTo("Open Lirelle on the phone to sign in again.")
        assertThat(line.mediaMetadata.isPlayable).isFalse()
        assertThat(line.mediaMetadata.isBrowsable).isFalse()
        // A sentence among covers: a line, not a cell of the grid.
        assertThat(line.mediaMetadata.extras?.getInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM))
            .isEqualTo(MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        assertThat(link.said).isEmpty()
    }

    @Test
    fun `said as a line, each notice has a line of its own, which names no place and no book`() {
        val ids = CarNotice.entries.map { checkNotNull(NoticeAsLine(texts).answer(it, link).value).single().mediaId }

        assertThat(ids.toSet()).hasSize(CarNotice.entries.size)
        ids.forEach {
            assertThat(CarIds.placeOf(it)).isNull()
            assertThat(CarIds.bookOf(it)).isNull()
        }
    }
}
