package app.tfdn.lirelle.core.ids

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Test

class IdsTest {

    @Serializable
    private data class Holder(
        val book: BookId,
        val file: FileId,
        val series: SeriesId,
        val author: AuthorId,
        val shelf: ShelfId,
        val mark: MarkId,
        val read: ReadId,
        val library: LibraryId,
    )

    private val holder = Holder(
        book = BookId("12"),
        file = FileId("0B5Y"),
        series = SeriesId("s-3"),
        author = AuthorId("a"),
        shelf = ShelfId("7"),
        mark = MarkId("m"),
        read = ReadId("r"),
        library = LibraryId("l-1"),
    )

    @Test
    fun `an id is stored as its plain text`() {
        val json = Json.encodeToString(Holder.serializer(), holder)

        assertThat(json).isEqualTo(
            """{"book":"12","file":"0B5Y","series":"s-3","author":"a","shelf":"7","mark":"m","read":"r",""" +
                """"library":"l-1"}""",
        )
    }

    @Test
    fun `an id reads back from its plain text`() {
        val json = Json.encodeToString(Holder.serializer(), holder)

        assertThat(Json.decodeFromString(Holder.serializer(), json)).isEqualTo(holder)
    }

    @Serializable
    private data class Keyed(val byBook: Map<BookId, Int>, val byFile: Map<FileId, String>)

    @Test
    fun `a map keyed by ids is stored with the ids' plain text as its keys, and reads back`() {
        val keyed = Keyed(mapOf(BookId("12") to 1, BookId("0B5Y") to 2), mapOf(FileId("f.1") to "a"))

        val json = Json.encodeToString(Keyed.serializer(), keyed)

        assertThat(json).isEqualTo("""{"byBook":{"12":1,"0B5Y":2},"byFile":{"f.1":"a"}}""")
        assertThat(Json.decodeFromString(Keyed.serializer(), json)).isEqualTo(keyed)
    }

    @Test
    fun `an id prints as its text`() {
        assertThat(BookId("12").toString()).isEqualTo("12")
        assertThat("${FileId("x")} ${SeriesId("s")} ${AuthorId("a")}").isEqualTo("x s a")
        assertThat("${ShelfId("h")} ${MarkId("m")} ${ReadId("r")} ${LibraryId("l")}").isEqualTo("h m r l")
    }

    @Test
    fun `two ids of the same text are equal`() {
        assertThat(BookId("12")).isEqualTo(BookId("12"))
        assertThat(BookId("12")).isNotEqualTo(BookId("13"))
    }
}
