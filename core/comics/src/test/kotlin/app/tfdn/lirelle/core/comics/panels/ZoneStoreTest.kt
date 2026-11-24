package app.tfdn.lirelle.core.comics.panels

import app.tfdn.lirelle.core.comics.ComicKey
import app.tfdn.lirelle.core.comics.settings.Direction
import app.tfdn.lirelle.core.ids.FileId
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Test

class ZoneStoreTest {
    private val directory: File = Files.createTempDirectory("zones").toFile()
    private val key = ComicKey(FileId("7"), "v1")
    private val zones = listOf(
        Zone(0.05f, 0.05f, 0.5f, 0.3f, ZoneKind.Filled),
        Zone(0.05f, 0.35f, 0.95f, 0.6f, ZoneKind.Sparse),
    )

    @Test
    fun `what was kept of a page is given back`() {
        ZoneStore(directory).keep(key, Direction.LeftToRight, index = 3, pictureBytes = 1000, zones)
        val kept = ZoneStore(directory).of(key, Direction.LeftToRight)
        assertThat(kept[3]).isEqualTo(KeptZones(1000, zones))
        assertThat(kept[4]).isNull()
    }

    @Test
    fun `a page that could not be cut is kept as such`() {
        ZoneStore(directory).keep(key, Direction.LeftToRight, 0, 10, emptyList())
        assertThat(ZoneStore(directory).of(key, Direction.LeftToRight)[0]).isEqualTo(KeptZones(10, emptyList()))
    }

    @Test
    fun `several pages of an album are kept together`() {
        val store = ZoneStore(directory)
        store.keep(key, Direction.LeftToRight, 0, 10, zones)
        store.keep(key, Direction.LeftToRight, 1, 20, zones.take(1))
        assertThat(ZoneStore(directory).of(key, Direction.LeftToRight).keys).containsExactly(0, 1)
    }

    @Test
    fun `another version of the file, another direction, another cut have nothing`() {
        ZoneStore(directory).keep(key, Direction.LeftToRight, 0, 10, zones)
        assertThat(ZoneStore(directory).of(key.copy(contentVersion = "v2"), Direction.LeftToRight)).isEmpty()
        assertThat(ZoneStore(directory).of(key, Direction.RightToLeft)).isEmpty()
        assertThat(ZoneStore(directory, cutVersion = CutVersion + 1).of(key, Direction.LeftToRight)).isEmpty()
    }

    @Test
    fun `versions that differ by a sign only are kept apart`() {
        val versions = listOf("1.2", "1:2", "1_2")
        versions.forEachIndexed { page, version ->
            ZoneStore(directory).keep(key.copy(contentVersion = version), Direction.LeftToRight, page, 10, zones)
        }

        assertThat(directory.listFiles()).hasLength(3)
        versions.forEachIndexed { page, version ->
            val kept = ZoneStore(directory).of(key.copy(contentVersion = version), Direction.LeftToRight)
            assertThat(kept.keys).containsExactly(page)
        }
    }

    @Test
    fun `no version, an empty one and one that reads none are kept apart`() {
        val versions = listOf(null, "", "none", "~none")
        versions.forEachIndexed { page, version ->
            ZoneStore(directory).keep(key.copy(contentVersion = version), Direction.LeftToRight, page, 10, zones)
        }

        assertThat(directory.listFiles()).hasLength(4)
        versions.forEachIndexed { page, version ->
            val kept = ZoneStore(directory).of(key.copy(contentVersion = version), Direction.LeftToRight)
            assertThat(kept.keys).containsExactly(page)
        }
    }

    @Test
    fun `a file and a version are not read as another file and another version`() {
        ZoneStore(directory).keep(ComicKey(FileId("a-b"), "c"), Direction.LeftToRight, 0, 10, zones)

        assertThat(ZoneStore(directory).of(ComicKey(FileId("a"), "b-c"), Direction.LeftToRight)).isEmpty()
    }

    @Test
    fun `a very long version is kept and read back, under a short name`() {
        val long = key.copy(contentVersion = "v/".repeat(500))
        val other = key.copy(contentVersion = "v/".repeat(499) + "w/")
        ZoneStore(directory).keep(long, Direction.LeftToRight, 0, 10, zones)

        assertThat(ZoneStore(directory).of(long, Direction.LeftToRight)).containsExactly(0, KeptZones(10, zones))
        assertThat(ZoneStore(directory).of(other, Direction.LeftToRight)).isEmpty()
        assertThat(directory.listFiles()!!.single().name.length).isAtMost(200)
    }

    @Test
    fun `a line that cannot be read is passed over, the others stay`() {
        val store = ZoneStore(directory)
        store.keep(key, Direction.LeftToRight, 0, 10, zones)
        store.keep(key, Direction.LeftToRight, 2, 30, zones.take(1))
        val file = directory.listFiles()!!.single()
        file.writeText(file.readLines().let { (first, last) -> listOf(first, "1;ten;what", "", "no line", last) }
            .joinToString("\n"))

        assertThat(ZoneStore(directory).of(key, Direction.LeftToRight))
            .containsExactly(0, KeptZones(10, zones), 2, KeptZones(30, zones.take(1)))
    }

    @Test
    fun `a page with a zone that does not lie in a page is passed over`() {
        val lines = listOf(
            "0;10;0.1,0.1,0.5,0.5,P",
            "1;10;0.1,0.1,0.5,0.5,P 0.5,0.1,1.5,0.5,P",
            "2;10;-0.1,0.1,0.5,0.5,P",
            "3;10;0.5,0.1,0.5,0.5,P",
            "4;10;0.1,0.6,0.5,0.5,W",
            "5;10;0.1,NaN,0.5,0.5,P",
            "6;10;0.1,0.1,0.5,0.5,X",
            "7;10;0.0,0.0,1.0,1.0,W",
        )
        ZoneStore(directory).keep(key, Direction.LeftToRight, 0, 10, zones)
        directory.listFiles()!!.single().writeText(lines.joinToString("\n"))

        assertThat(ZoneStore(directory).of(key, Direction.LeftToRight)).containsExactly(
            0, KeptZones(10, listOf(Zone(0.1f, 0.1f, 0.5f, 0.5f, ZoneKind.Filled))),
            7, KeptZones(10, listOf(Zone(0f, 0f, 1f, 1f, ZoneKind.Sparse))),
        )
    }

    @Test
    fun `keeping a page again replaces what was kept of it`() {
        val store = ZoneStore(directory)
        store.keep(key, Direction.LeftToRight, 0, 10, zones)
        store.keep(key, Direction.LeftToRight, 1, 20, zones)
        store.keep(key, Direction.LeftToRight, 0, 30, zones.take(1))

        assertThat(ZoneStore(directory).of(key, Direction.LeftToRight))
            .containsExactly(0, KeptZones(30, zones.take(1)), 1, KeptZones(20, zones))
    }

    @Test
    fun `sides that are awkward numbers are given back as they were`() {
        val awkward = listOf(
            Zone(1.0E-4f, 1f / 3, 2f / 3, 1f - 1.0E-7f, ZoneKind.Filled),
            Zone(Float.MIN_VALUE, 0f, 0.1f + 0.2f, 1f, ZoneKind.Sparse),
        )
        ZoneStore(directory).keep(key, Direction.LeftToRight, 0, 10, awkward)

        assertThat(ZoneStore(directory).of(key, Direction.LeftToRight)[0]!!.zones).isEqualTo(awkward)
    }

    private fun album(name: String) = ComicKey(FileId(name), "v")

    private fun ZoneStore.has(name: String) = of(album(name), Direction.LeftToRight).isNotEmpty()

    @Test
    fun `only the albums last written stay`() {
        val store = ZoneStore(directory, limit = 2, now = sequenceOf(1000L, 2000L, 3000L, 4000L).iterator()::next)
        listOf("2", "3", "1").forEach { store.keep(album(it), Direction.LeftToRight, 0, 10, zones) }

        assertThat(listOf("1", "2", "3").filter { store.has(it) }).containsExactly("1", "3")

        store.keep(album("0"), Direction.LeftToRight, 0, 10, zones)

        assertThat(listOf("0", "1", "2", "3").filter { store.has(it) }).containsExactly("0", "1")
    }

    @Test
    fun `an album kept again is among the last written`() {
        val store = ZoneStore(directory, limit = 2, now = sequenceOf(1000L, 2000L, 3000L, 4000L).iterator()::next)
        listOf("a", "b", "a", "c").forEach { store.keep(album(it), Direction.LeftToRight, 0, 10, zones) }

        assertThat(listOf("a", "b", "c").filter { store.has(it) }).containsExactly("a", "c")
    }

    @Test
    fun `the album just written stays, whatever the time says`() {
        val store = ZoneStore(directory, limit = 1, now = sequenceOf(5000L, 1000L).iterator()::next)
        listOf("a", "b").forEach { store.keep(album(it), Direction.LeftToRight, 0, 10, zones) }

        assertThat(listOf("a", "b").filter { store.has(it) }).containsExactly("b")
    }

    @Test
    fun `what a crash left of a file being written goes once old, and is no album`() {
        directory.mkdirs()
        val old = File(directory, "zones1.part").apply { writeText("0;10;") }.also { it.setLastModified(1_000_000) }
        val recent = File(directory, "zones2.part").apply { writeText("0;10;") }.also { it.setLastModified(1_050_000) }
        val store = ZoneStore(directory, limit = 1, now = { 1_100_000 })

        store.keep(key, Direction.LeftToRight, 0, 10, zones)

        assertThat(old.exists()).isFalse()
        assertThat(recent.exists()).isTrue()
        assertThat(store.of(key, Direction.LeftToRight)).isNotEmpty()
        assertThat(directory.listFiles()).hasLength(2)
    }

    @Test
    fun `two stores on one folder keeping pages of one album at once lose none`() {
        val pages = 200
        val writers = listOf(0, 1).map { parity ->
            val store = ZoneStore(directory)
            Thread {
                (parity until pages step 2).forEach { store.keep(key, Direction.LeftToRight, it, it.toLong(), zones) }
            }
        }
        writers.forEach(Thread::start)
        writers.forEach(Thread::join)

        val kept = ZoneStore(directory).of(key, Direction.LeftToRight)
        assertThat(kept.keys).containsExactlyElementsIn(0 until pages)
        assertThat(kept.values.map { it.zones }.toSet()).containsExactly(zones)
        assertThat(directory.listFiles()).hasLength(1)
    }

    @Test
    fun `a folder that cannot be made keeps nothing and breaks nothing`() {
        val blocked = File(Files.createTempFile("not-a-folder", "").toFile(), "zones")
        ZoneStore(blocked).keep(key, Direction.LeftToRight, 0, 10, zones)
        assertThat(ZoneStore(blocked).of(key, Direction.LeftToRight)).isEmpty()
    }

    @Test
    fun `once its profile closed nothing is kept and no folder is made`() {
        val gone = File(directory, "panels")
        val store = ZoneStore(gone)

        store.close()
        store.keep(key, Direction.LeftToRight, 0, 10, zones)

        assertThat(gone.exists()).isFalse()
        assertThat(store.of(key, Direction.LeftToRight)).isEmpty()
    }

    @Test
    fun `once its profile closed what was kept is still read, and stays as it was`() {
        val store = ZoneStore(directory)
        store.keep(key, Direction.LeftToRight, 0, 10, zones)

        store.close()
        store.keep(key, Direction.LeftToRight, 0, 20, emptyList())
        store.keep(key, Direction.LeftToRight, 1, 20, zones)

        assertThat(store.of(key, Direction.LeftToRight)).containsExactly(0, KeptZones(10, zones))
    }
}
