package app.tfdn.lirelle.core.audio.timeline

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CreditsTest {

    private val anne = "Anne Verlaine"
    private val paul = "Paul Ardent"
    private val lise = "Lise Moreau"
    private val thomas = "Thomas Rivière"
    private val claire = "Claire Dubois"
    private val marc = "Marc Olivier"

    @Test
    fun `one author and one narrator`() {
        assertThat(creditsOf(listOf(anne), listOf(thomas))).isEqualTo(
            CreditsLine.AuthorsAndNarrators(
                authors = Shortened(anne, null, 0),
                narrators = Shortened(thomas, null, 0),
            ),
        )
    }

    @Test
    fun `two authors are both named`() {
        assertThat(creditsOf(listOf(anne, paul), listOf(thomas))).isEqualTo(
            CreditsLine.AuthorsAndNarrators(
                authors = Shortened(anne, paul, 0),
                narrators = Shortened(thomas, null, 0),
            ),
        )
    }

    @Test
    fun `three authors or more name the first and count the others`() {
        assertThat(creditsOf(listOf(anne, paul, lise), listOf(thomas))).isEqualTo(
            CreditsLine.AuthorsAndNarrators(
                authors = Shortened(anne, null, 2),
                narrators = Shortened(thomas, null, 0),
            ),
        )
    }

    @Test
    fun `several narrators follow the same rule`() {
        assertThat(creditsOf(listOf(anne), listOf(thomas, claire))).isEqualTo(
            CreditsLine.AuthorsAndNarrators(
                authors = Shortened(anne, null, 0),
                narrators = Shortened(thomas, claire, 0),
            ),
        )
        assertThat(creditsOf(listOf(anne), listOf(thomas, claire, marc))).isEqualTo(
            CreditsLine.AuthorsAndNarrators(
                authors = Shortened(anne, null, 0),
                narrators = Shortened(thomas, null, 2),
            ),
        )
    }

    @Test
    fun `no narrator shows the authors only`() {
        assertThat(creditsOf(listOf(anne, paul), emptyList()))
            .isEqualTo(CreditsLine.AuthorsOnly(Shortened(anne, paul, 0)))
    }

    @Test
    fun `no author shows the narrators only`() {
        assertThat(creditsOf(emptyList(), listOf(thomas)))
            .isEqualTo(CreditsLine.NarratorsOnly(Shortened(thomas, null, 0)))
    }

    @Test
    fun `neither shows nothing`() {
        assertThat(creditsOf(emptyList(), emptyList())).isEqualTo(CreditsLine.None)
    }

    @Test
    fun `blank names are ignored`() {
        assertThat(creditsOf(listOf("", anne, "  "), listOf(" ")))
            .isEqualTo(CreditsLine.AuthorsOnly(Shortened(anne, null, 0)))
        assertThat(creditsOf(listOf(" "), listOf("", thomas)))
            .isEqualTo(CreditsLine.NarratorsOnly(Shortened(thomas, null, 0)))
        assertThat(creditsOf(listOf("\t"), listOf(""))).isEqualTo(CreditsLine.None)
    }

    @Test
    fun `blank names do not count among the others`() {
        assertThat(creditsOf(listOf(anne, " ", paul), emptyList()))
            .isEqualTo(CreditsLine.AuthorsOnly(Shortened(anne, paul, 0)))
    }

    @Test
    fun `names are trimmed`() {
        assertThat(creditsOf(listOf(" $anne "), emptyList()))
            .isEqualTo(CreditsLine.AuthorsOnly(Shortened(anne, null, 0)))
    }

    @Test
    fun `a name given twice counts once`() {
        assertThat(creditsOf(listOf(anne, " $anne", paul), listOf(thomas, thomas)))
            .isEqualTo(
                CreditsLine.AuthorsAndNarrators(
                    authors = Shortened(anne, paul, 0),
                    narrators = Shortened(thomas, null, 0),
                ),
            )
    }
}
