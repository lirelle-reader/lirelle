package app.tfdn.lirelle.source.bookorbit.library.dto

import app.tfdn.lirelle.source.bookorbit.library.testing.Fixtures
import app.tfdn.lirelle.source.bookorbit.network.ApiJson
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReadAlongDtosTest {

    private inline fun <reified T> recorded(name: String): T = ApiJson.decodeFromString(Fixtures.recorded(name))

    @Test
    fun `a recorded map reads, seconds as they come`() {
        val map = recorded<MediaOverlayMapDto>("readalong-map-m4b")

        assertThat(map.items).isNotEmpty()
        assertThat(map.items.first().clipEndSeconds).isNotNull()
        assertThat(map.sections).isNotEmpty()
        assertThat(map.resources).isNotEmpty()
    }

    @Test
    fun `the info keeps the spine and the manifest, and ignores the rest`() {
        val info = recorded<EpubInfoDto>("readalong-info-mp3")

        assertThat(info.spine).hasSize(90)
        assertThat(info.manifest).hasSize(90)
    }

    @Test
    fun `a position carries its narration`() {
        val item = recorded<PositionsDto>("readalong-progress-narration").items.single()

        assertThat(item.narration).isEqualTo(
            PositionNarrationDto(
                seconds = 19412.16,
                fragment = "OEBPS/Text/section111.xhtml#p014-s182",
                sectionIndex = 27,
                percentage = 50.0,
                updatedAt = "2026-11-15T18:47:13.498Z",
            ),
        )
    }

    @Test
    fun `a position without narration reads as before`() {
        val item = ApiJson.decodeFromString<PositionDto>("""{"fileId":1,"percentage":3}""")

        assertThat(item.narration).isNull()
    }
}
