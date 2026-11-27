package app.tfdn.lirelle.source.kavita.library

import app.tfdn.lirelle.core.ids.FileId
import app.tfdn.lirelle.source.kavita.library.dto.FileDto
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class KavitaFilesTest {

    @Test
    fun `a file id carries the library, series, volume, chapter and file`() {
        val ref = KavitaFileRef(libraryId = 1, seriesId = 7, volumeId = 7, chapterId = 44, fileId = 44)

        assertThat(ref.appId).isEqualTo(FileId("1-7-7-44-44"))
        assertThat(FileId("1-7-7-44-44").kavitaFile()).isEqualTo(ref)
    }

    @Test
    fun `a file id that is not one of Kavita's is none`() {
        assertThat(FileId("44").kavitaFile()).isNull()
        assertThat(FileId("1-7-x-44-44").kavitaFile()).isNull()
        assertThat(FileId("1-7-7-44-0").kavitaFile()).isNull()
    }

    @Test
    fun `each Kavita format and extension is a format of the app`() {
        assertThat(formatOf(FileDto(format = 1, extension = ".cbz"))).isEqualTo("CBZ")
        assertThat(formatOf(FileDto(format = 1, extension = ".zip"))).isEqualTo("CBZ")
        assertThat(formatOf(FileDto(format = 1, extension = ".cbr"))).isEqualTo("CBR")
        assertThat(formatOf(FileDto(format = 1, extension = ".rar"))).isEqualTo("CBR")
        assertThat(formatOf(FileDto(format = 1, extension = ".cb7"))).isEqualTo("CB7")
        assertThat(formatOf(FileDto(format = 1, extension = ".7z"))).isEqualTo("CB7")
        assertThat(formatOf(FileDto(format = 0, extension = ".jpg"))).isEqualTo("CBZ")
        assertThat(formatOf(FileDto(format = 3, extension = ".epub"))).isEqualTo("EPUB")
        assertThat(formatOf(FileDto(format = 4, extension = ".pdf"))).isEqualTo("PDF")
        assertThat(formatOf(FileDto(format = 2, extension = ".txt"))).isNull()
        assertThat(formatOf(FileDto(format = 1, extension = ".cbt"))).isNull()
    }
}
