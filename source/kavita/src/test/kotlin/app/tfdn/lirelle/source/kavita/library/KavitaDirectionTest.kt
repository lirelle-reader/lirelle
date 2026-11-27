package app.tfdn.lirelle.source.kavita.library

import app.tfdn.lirelle.core.library.model.PageDirection
import app.tfdn.lirelle.source.kavita.library.dto.ReadingProfileDto
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class KavitaDirectionTest {
    @Test
    fun `a profile of the user's says the direction`() {
        assertThat(directionOf(ReadingProfileDto(kind = 1, readingDirection = 1), libraryType = 1))
            .isEqualTo(PageDirection.RightToLeft)
        assertThat(directionOf(ReadingProfileDto(kind = 1, readingDirection = 0), libraryType = 0))
            .isEqualTo(PageDirection.LeftToRight)
    }

    @Test
    fun `Kavita's default profile on a Manga library reads right to left`() {
        assertThat(directionOf(ReadingProfileDto(kind = 0, readingDirection = 0), libraryType = 0))
            .isEqualTo(PageDirection.RightToLeft)
    }

    @Test
    fun `Kavita's default profile elsewhere reads left to right`() {
        assertThat(directionOf(ReadingProfileDto(kind = 0, readingDirection = 0), libraryType = 1))
            .isEqualTo(PageDirection.LeftToRight)
    }

    @Test
    fun `no profile says nothing`() {
        assertThat(directionOf(null, libraryType = 0)).isNull()
    }
}
