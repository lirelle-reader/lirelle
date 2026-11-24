package app.tfdn.lirelle.source.bookorbit.network

import app.tfdn.lirelle.core.ids.AuthorId
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.ids.MarkId
import app.tfdn.lirelle.core.ids.ReadId
import app.tfdn.lirelle.core.ids.SeriesId
import app.tfdn.lirelle.core.ids.ShelfId
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class BookOrbitIdsTest {

    @Test
    fun `a BookOrbit number becomes an id of its decimal text`() {
        assertThat(bookIdOf(12)).isEqualTo(BookId("12"))
        assertThat(fileIdOf(3)).isEqualTo(FileId("3"))
        assertThat(seriesIdOf(4)).isEqualTo(SeriesId("4"))
        assertThat(authorIdOf(5)).isEqualTo(AuthorId("5"))
        assertThat(shelfIdOf(6)).isEqualTo(ShelfId("6"))
        assertThat(markIdOf(7)).isEqualTo(MarkId("7"))
        assertThat(readIdOf(8)).isEqualTo(ReadId("8"))
    }

    @Test
    fun `an id made from a number gives the number back`() {
        val big = Long.MAX_VALUE

        assertThat(bookIdOf(big).bookOrbit()).isEqualTo(big)
        assertThat(fileIdOf(big).bookOrbit()).isEqualTo(big)
        assertThat(seriesIdOf(big).bookOrbit()).isEqualTo(big)
        assertThat(authorIdOf(big).bookOrbit()).isEqualTo(big)
        assertThat(shelfIdOf(big).bookOrbit()).isEqualTo(big)
        assertThat(markIdOf(big).bookOrbit()).isEqualTo(big)
        assertThat(readIdOf(big).bookOrbit()).isEqualTo(big)
    }

    @Test
    fun `an id that is not a BookOrbit number fails loudly, naming its type`() {
        val failures = listOf(
            "BookId" to { BookId("0B5Y").bookOrbit() },
            "FileId" to { FileId("1.5").bookOrbit() },
            "SeriesId" to { SeriesId("").bookOrbit() },
            "AuthorId" to { AuthorId(" 1").bookOrbit() },
            "ShelfId" to { ShelfId("99999999999999999999").bookOrbit() },
            "MarkId" to { MarkId("x").bookOrbit() },
            "ReadId" to { ReadId("+").bookOrbit() },
        )

        failures.forEach { (type, convert) ->
            val error = assertThrows(IllegalStateException::class.java) { convert() }
            assertThat(error).hasMessageThat().contains(type)
        }
    }

    @Test
    fun `a BookOrbit revision becomes a version of its decimal text, and back`() {
        assertThat(versionOf(2)).isEqualTo("2")
        assertThat(revisionOf(versionOf(Int.MAX_VALUE))).isEqualTo(Int.MAX_VALUE)
        assertThat(revisionOf("0")).isEqualTo(0)
        // No version known: the write creates the state, on revision 0.
        assertThat(revisionOf(null)).isEqualTo(0)
    }

    @Test
    fun `a version that is not a BookOrbit revision fails loudly`() {
        listOf("v7", "07", "+7", "", " 1", "1.5", "99999999999").forEach { version ->
            val error = assertThrows(IllegalStateException::class.java) { revisionOf(version) }
            assertThat(error).hasMessageThat().contains("version")
        }
    }

    @Test
    fun `only the text BookOrbit itself would give is a number`() {
        // "007" and "+7" parse, but no id made from a BookOrbit number has that text: it can only be a bug.
        listOf("007", "+7", "-0").forEach { text ->
            assertThrows(IllegalStateException::class.java) { BookId(text).bookOrbit() }
        }
    }
}
