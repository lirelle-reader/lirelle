package app.tfdn.lirelle.core.ids

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class IdNamesTest {

    @Test
    fun `a BookOrbit number is its own name`() {
        assertThat(fileNameOf("42")).isEqualTo("42")
        assertThat(fileNameOf("9007199254740993")).isEqualTo("9007199254740993")
    }

    @Test
    fun `letters, digits, dash and underscore are kept`() {
        assertThat(fileNameOf("0B5Y2s1Z_a-b")).isEqualTo("0B5Y2s1Z_a-b")
    }

    @Test
    fun `any other character is encoded as its UTF-8 bytes`() {
        assertThat(fileNameOf("a.b")).isEqualTo("a~2Eb")
        assertThat(fileNameOf("a/b")).isEqualTo("a~2Fb")
        assertThat(fileNameOf("a:b")).isEqualTo("a~3Ab")
        assertThat(fileNameOf("a\\b")).isEqualTo("a~5Cb")
        assertThat(fileNameOf("a b")).isEqualTo("a~20b")
        assertThat(fileNameOf("é")).isEqualTo("~C3~A9")
        assertThat(fileNameOf("\uD83D\uDCDA")).isEqualTo("~F0~9F~93~9A")
    }

    @Test
    fun `the escape character itself is encoded, so two ids never share a name`() {
        assertThat(fileNameOf("~2E")).isEqualTo("~7E2E")
        assertThat(fileNameOf("~2E")).isNotEqualTo(fileNameOf("."))
    }

    @Test
    fun `no name is ever a path or a way out of its folder`() {
        listOf(".", "..", "../..", "/", "a/../../b", "\\", "C:\\x", "./x", "~").forEach { id ->
            val name = fileNameOf(id)

            assertThat(name).doesNotContain("/")
            assertThat(name).doesNotContain("\\")
            assertThat(name).doesNotContain(".")
            assertThat(name).isNotEmpty()
        }
    }

    @Test
    fun `an empty id is a bug`() {
        assertThrows(IllegalArgumentException::class.java) { fileNameOf("") }
    }

    @Test
    fun `each id type gives the name of its text`() {
        assertThat(BookId("a.b").fileName).isEqualTo("a~2Eb")
        assertThat(FileId("1").fileName).isEqualTo("1")
        assertThat(SeriesId("s/").fileName).isEqualTo("s~2F")
        assertThat(AuthorId("x").fileName).isEqualTo("x")
        assertThat(ShelfId("y").fileName).isEqualTo("y")
        assertThat(MarkId("z").fileName).isEqualTo("z")
        assertThat(ReadId("r").fileName).isEqualTo("r")
    }
}
