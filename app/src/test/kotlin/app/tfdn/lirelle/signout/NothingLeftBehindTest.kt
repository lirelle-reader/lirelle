package app.tfdn.lirelle.signout

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.audio.cache.AudioCache
import app.tfdn.lirelle.di.AppProfileEntry
import app.tfdn.lirelle.di.ProfileDataOnDevice
import coil3.asImage
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A privacy requirement, on the application's real graph and its real stores: once an account signs out, the
 * device holds none of its books, and the next account (a child's after an adult's) finds nothing. Signing
 * out is closing the profile and deleting its folders: nothing forgets a store by hand, so a store that is
 * not in the profile's folders shows here.
 */
@RunWith(RobolectricTestRunner::class)
class NothingLeftBehindTest {

    @get:Rule
    val keystore = FakeAndroidKeystore()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val device = TestDevice(context)
    private val settings by lazy { DeviceSettingsKept(context, device.data) }
    private val traces = listOf(DownloadTraces(device), ReadingTraces(device), ListeningTraces(device))

    /** Signs in and uses the app. @return the id of the profile. */
    private suspend fun useTheApp(userId: Long): String {
        val profile = device.signIn(userId)
        traces.forEach { it.leave() }
        traces.forEach { it.assertLeft() }
        assertThat(device.profileFolders().map { it.name }.distinct()).containsExactly(profile)
        assertThat(device.listed()).containsExactly(profile)
        return profile
    }

    /** The device has no profile any more: neither a folder, nor a record, nor a database that names books. */
    private suspend fun assertNoProfileLeft() {
        assertWithMessage("profile folders").that(device.profileFolders().map { it.path }).isEmpty()
        assertWithMessage("profiles listed").that(device.listed()).isEmpty()
        // The index of the audio cache was the profile's own file: no database of the application names its books.
        assertWithMessage("databases").that(context.databaseList().filter { "exoplayer" in it }).isEmpty()
    }

    /**
     * What the account that left still held open writes nothing: it would make the folders of its profile
     * again, after they were deleted.
     */
    private fun assertLetGo(covers: DiskCache, audio: AudioCache) {
        // A cover that was still loading.
        assertWithMessage("a cover written after the sign-out")
            .that(runCatching { covers.openEditor("$TestServer/late") }.getOrNull()).isNull()
        // The cache was let go of, and is not made again for an account that left.
        assertWithMessage("an audio cache used after the sign-out")
            .that(runCatching { audio.cache }.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
    }

    /** The profile [id] is a new one, and holds nothing: the only one on the device. */
    private suspend fun assertNewAndEmpty(id: String, vararg earlier: String) {
        assertWithMessage("profile id").that(earlier.toList()).doesNotContain(id)
        assertWithMessage("profile folders").that(device.profileFolders().map { it.name }.distinct())
            .containsExactly(id)
        assertWithMessage("profiles listed").that(device.listed()).containsExactly(id)
        traces.forEach { it.assertNone() }
    }

    @Test
    fun `after a sign-out the device keeps nothing of the account, and the next one finds nothing`() = runBlocking {
        settings.change()
        val first = useTheApp(userId = 1)
        val loader = device.profile<AppProfileEntry>().coverImageLoader()
        val covers = checkNotNull(loader.diskCache)
        val audio = device.profile<ProfileDataOnDevice>().audioCache()
        // A cover seen on a screen: held in memory, where the folders' deletion does not reach.
        val seen = checkNotNull(loader.memoryCache)
        val cover = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).asImage()
        seen[MemoryCache.Key("$TestServer/seen")] = MemoryCache.Value(cover)
        assertWithMessage("a cover in memory").that(seen.keys).isNotEmpty()

        device.signOut()

        assertLetGo(covers, audio)
        assertWithMessage("covers in memory after the sign-out").that(seen.keys).isEmpty()
        assertNoProfileLeft()
        // What is the device's stays, the speed of a new book among it; and the sign-in screen offers the server.
        settings.assertKept(lastSpeed = BookSpeed)
        assertWithMessage("server address").that(device.serverOffered()).isEqualTo(TestServer)

        val second = device.signIn(userId = 2)
        assertNewAndEmpty(second, first)
        settings.assertKept(lastSpeed = BookSpeed)
    }

    /** Its stores would take it for their owner: only the deletion keeps what it left from it. */
    @Test
    fun `the account that left finds nothing either when it signs in again`() = runBlocking {
        val first = useTheApp(userId = 1)
        device.signOut()
        assertNoProfileLeft()

        val again = device.signIn(userId = 1)
        assertNewAndEmpty(again, first)

        // And after another account in between.
        traces.forEach { it.leave() }
        device.signOut()
        val other = device.signIn(userId = 2)
        assertNewAndEmpty(other, first, again)
        device.signOut()
        assertNoProfileLeft()
        assertNewAndEmpty(device.signIn(userId = 1), first, again, other)
    }
}
