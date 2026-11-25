package app.tfdn.lirelle.feature.player.car

import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.ids.BookId
import app.tfdn.lirelle.core.testing.FakeLibraryRepository
import app.tfdn.lirelle.feature.player.service.FakeKeptBookData
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

private const val Gearhead = "com.google.android.projection.gearhead"

/** Who is served a cover, and of which profile. */
@RunWith(AndroidJUnit4::class)
class CarCoverProviderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val authority = CarCovers.authority(context.packageName)
    private val kept = FakeKeptBookData()
    private val urls = CarCoverUrls()
    private val server = CoverServer()
    private val fetches = CarCoverFetches({ 0 }, 4)

    /** The profile open now; null when none is. */
    private var open: String? = "profile-1"

    /** How many times the open profile was asked. */
    private var asked = 0

    private fun provider(timeoutMillis: Long = 10_000): CarCoverProvider {
        val files = CarCoverFiles(
            kept, urls, FakeLibraryRepository(), server.client, File(folder.root, "car-covers"), fetches,
        )
        val provider = CarCoverProvider(timeoutMillis) {
            asked++
            open?.let { ProfileCovers(it, files) }
        }
        provider.attachInfo(context, ProviderInfo().apply { this.authority = this@CarCoverProviderTest.authority })
        return provider
    }

    private fun uri(profileId: String = "profile-1", bookId: String = "1"): Uri =
        CarCovers.uri(authority, profileId, BookId(bookId))

    private fun keep(bookId: String, content: String = "cover-$bookId") {
        kept.covers[BookId(bookId)] = folder.newFile().apply { writeText(content) }
    }

    @Test
    fun `the car's host is served the cover of a book of the open profile`() {
        keep("1")
        val provider = provider()
        shadowOf(provider).setCallingPackage(Gearhead)

        val served = provider.openFile(uri(), "r")

        assertThat(ParcelFileDescriptor.AutoCloseInputStream(served).use { it.readBytes().decodeToString() })
            .isEqualTo("cover-1")
    }

    @Test
    fun `the app itself is served too, and a fetched cover as a kept one`() {
        urls.remember(BookId("1"), "https://books.example/c/1")

        val file = provider().coverFile(uri(), context.packageName)

        assertThat(file.readText()).isEqualTo("https://books.example/c/1")
    }

    @Test
    fun `an app that is not the car's is refused, and nothing is asked of the profile`() {
        keep("1")
        val provider = provider()

        listOf("com.example.gallery", "com.android.systemui", null).forEach { caller ->
            assertThrows(FileNotFoundException::class.java) { provider.coverFile(uri(), caller) }
        }
        shadowOf(provider).setCallingPackage("com.example.gallery")
        assertThrows(FileNotFoundException::class.java) { provider.openFile(uri(), "r") }
        assertThat(asked).isEqualTo(0)
    }

    @Test
    fun `another profile's cover is refused, and nothing without an open profile`() {
        keep("1")
        val provider = provider()

        assertThrows(FileNotFoundException::class.java) { provider.coverFile(uri("profile-2"), Gearhead) }
        open = null
        assertThrows(FileNotFoundException::class.java) { provider.coverFile(uri(), Gearhead) }
    }

    @Test
    fun `the profile changed - its covers are the new one's, asked anew each time`() {
        keep("1")
        val provider = provider()
        provider.coverFile(uri(), Gearhead)

        open = "profile-2"

        assertThrows(FileNotFoundException::class.java) { provider.coverFile(uri(), Gearhead) }
        assertThat(provider.coverFile(uri("profile-2"), Gearhead).readText()).isEqualTo("cover-1")
        assertThat(asked).isEqualTo(3)
    }

    @Test
    fun `a cover that cannot be had, or not in time, is not found - the car shows its placeholder`() {
        urls.remember(BookId("1"), "https://books.example/c/1")
        urls.remember(BookId("2"), "https://books.example/c/2")

        server.code = null
        assertThrows(FileNotFoundException::class.java) { provider().coverFile(uri(), Gearhead) }
        server.code = 200
        server.delayMillis = 5_000
        assertThrows(FileNotFoundException::class.java) {
            provider(timeoutMillis = 50).coverFile(uri(bookId = "2"), Gearhead)
        }
        // Neither is asked again at once, whatever the server would answer now: a thread is not held twice.
        server.delayMillis = 0
        listOf("1", "2").forEach {
            assertThrows(FileNotFoundException::class.java) { provider().coverFile(uri(bookId = it), Gearhead) }
        }
        assertThat(server.asked).hasSize(2)
    }

    @Test
    fun `an address that is not a cover's is not found, and a cover is only read`() {
        keep("1")
        val provider = provider()
        val wrong = listOf(
            Uri.parse("content://$authority/"),
            Uri.parse("content://$authority/profile-1"),
            Uri.parse("content://$authority/profile-1/1/more"),
        )

        wrong.forEach { assertThrows(FileNotFoundException::class.java) { provider.coverFile(it, Gearhead) } }
        shadowOf(provider).setCallingPackage(Gearhead)
        assertThrows(FileNotFoundException::class.java) { provider.openFile(uri(), "rw") }
    }

    @Test
    fun `a cover is a picture, and nothing is listed or written`() {
        val provider = provider()

        assertThat(provider.getType(uri())).isEqualTo("image/*")
        assertThat(provider.query(uri(), null, null, null, null)).isNull()
        assertThrows(UnsupportedOperationException::class.java) { provider.insert(uri(), ContentValues()) }
        assertThrows(UnsupportedOperationException::class.java) { provider.update(uri(), null, null, null) }
        assertThrows(UnsupportedOperationException::class.java) { provider.delete(uri(), null, null) }
    }

    @Test
    fun `the manifest declares the provider at the authority the covers' addresses have`() {
        val info = context.packageManager.resolveContentProvider(authority, 0)

        assertThat(info?.name).isEqualTo(CarCoverProvider::class.java.name)
        assertThat(info?.exported).isTrue()
    }
}
