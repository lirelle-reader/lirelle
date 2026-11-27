package app.tfdn.lirelle.source.kavita.reading

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.core.library.model.ReadingPosition
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test

/** Kavita's progress read into the app's place, and the app's place written as Kavita's page. */
class KavitaPositionsTest {

    private val file = FileId("1-7-7-44-44")
    private val at = "2026-11-26T08:27:17.927461"

    @Test
    fun `a page from 0 is the app's page from 1`() {
        val position = positionOf(file, ProgressDto(pageNum = 4, lastModifiedUtc = at), pages = 53, epub = false)

        assertThat(position.page).isEqualTo(5)
        assertThat(position.percent).isWithin(1e-9).of(5 * 100.0 / 53)
        assertThat(position.updatedAt).isEqualTo(Instant.parse("2026-11-26T08:27:17.927461Z"))
        assertThat(position.cfi).isNull()
    }

    @Test
    fun `the first page is the app's page 1`() {
        val position = positionOf(file, ProgressDto(pageNum = 0, lastModifiedUtc = at), pages = 53, epub = false)

        assertThat(position.page).isEqualTo(1)
        assertThat(position.percent).isWithin(1e-9).of(100.0 / 53)
        assertThat(position.updatedAt).isNotNull()
    }

    @Test
    fun `the last page is at 100, as the page count read`() {
        val last = positionOf(file, ProgressDto(pageNum = 52, lastModifiedUtc = at), pages = 53, epub = false)

        assertThat(last.page).isEqualTo(53)
        assertThat(last.percent).isEqualTo(100.0)
    }

    @Test
    fun `the page count read is the last page at 100`() {
        val position = positionOf(file, ProgressDto(pageNum = 53, lastModifiedUtc = at), pages = 53, epub = false)

        assertThat(position.page).isEqualTo(53)
        assertThat(position.percent).isEqualTo(100.0)
    }

    @Test
    fun `a chapter never opened is at 0 with no place and no date`() {
        val never = ProgressDto(pageNum = 0, lastModifiedUtc = "0001-01-01T00:00:00")

        assertThat(positionOf(file, never, pages = 53, epub = false))
            .isEqualTo(ReadingPosition(file, cfi = null, percent = 0.0, updatedAt = null))
        assertThat(positionOf(file, never, pages = 31, epub = true))
            .isEqualTo(ReadingPosition(file, cfi = null, percent = 0.0, updatedAt = null))
    }

    @Test
    fun `an EPUB's CFI written by the app is its place`() {
        val dto = ProgressDto(pageNum = 6, bookScrollId = "epubcfi(/6/14!/4/2/1:0)", lastModifiedUtc = at)

        val position = positionOf(file, dto, pages = 31, epub = true)

        assertThat(position.cfi).isEqualTo("epubcfi(/6/14!/4/2/1:0)")
        assertThat(position.page).isNull()
        assertThat(position.percent).isWithin(1e-9).of(6 * 100.0 / 31)
    }

    @Test
    fun `an EPUB place from Kavita's web reader opens at its spine item`() {
        val dto = ProgressDto(pageNum = 6, bookScrollId = "//html[1]/BODY/APP-ROOT[1]/DIV[1]", lastModifiedUtc = at)

        assertThat(positionOf(file, dto, pages = 31, epub = true).cfi).isEqualTo("epubcfi(/6/14)")
    }

    @Test
    fun `an EPUB read to the end on Kavita's web reader opens at its last spine item`() {
        val dto = ProgressDto(pageNum = 31, lastModifiedUtc = at)

        val position = positionOf(file, dto, pages = 31, epub = true)

        assertThat(position.cfi).isEqualTo("epubcfi(/6/62)")
        assertThat(position.percent).isEqualTo(100.0)
    }

    @Test
    fun `a comic's page is written from 0, the end as the page count`() {
        assertThat(pageNumOf(page = 5, percent = 9.4, pages = 53)).isEqualTo(4)
        assertThat(pageNumOf(page = 53, percent = 100.0, pages = 53)).isEqualTo(53)
        assertThat(pageNumOf(page = 60, percent = 50.0, pages = 53)).isEqualTo(52)
        assertThat(pageNumOf(page = 0, percent = 0.0, pages = 53)).isEqualTo(0)
    }

    @Test
    fun `an EPUB is written as its spine item, the end as the spine count`() {
        assertThat(spineNumOf(cfi = "epubcfi(/6/14!/4/2/1:0)", percent = 40.0, pages = 31)).isEqualTo(6)
        assertThat(spineNumOf(cfi = "epubcfi(/6/62!/4/2/1:0)", percent = 100.0, pages = 31)).isEqualTo(31)
        assertThat(spineNumOf(cfi = null, percent = 10.0, pages = 31)).isEqualTo(0)
    }
}
