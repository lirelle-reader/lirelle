package app.tfdn.lirelle.core.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.tfdn.lirelle.core.designsystem.LirelleTheme
import app.tfdn.lirelle.core.session.profile.FileProfileAvatars
import coil3.EventListener
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val Ground = "ground"
private const val LoadLimitMillis = 60_000L
private const val PictureSide = 96
private const val Readable = 4.5f

/** Around the avatar, wide enough for the badge and the mark, which stand out of its square. */
private val Around = 16.dp

/** No colour of either palette: what lies under the avatar, and what rings its badge. */
private val Under = Color(0xFF0000FF)
private val Ring = Color(0xFF00FF00)

/** No colour of either palette either: the badge's own, as the model gives them. */
private val Badge = Color(0xFFFFFF00)
private val BadgeLetter = Color(0xFF400040)

private val PictureColor = Color(0xFFFF00FF)
private val NewPictureColor = Color(0xFF00FFFF)

/** The avatar as it is drawn, in both themes: pixels, not nodes — it has none worth asking. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class ProfileAvatarTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private var dark by mutableStateOf(false)
    private var model by mutableStateOf(model(seedOf(colour = 0)))
    private var size by mutableStateOf(ProfileAvatarSize.Header)
    private var direction by mutableStateOf(LayoutDirection.Ltr)
    private var fontScale by mutableStateOf(1f)
    private lateinit var scheme: ColorScheme

    private fun model(seed: String, picture: File? = null, signInNeeded: Boolean = false) =
        ProfileAvatarModel(seed, initial = "C", kindLetter = "B", Badge, BadgeLetter, picture, signInNeeded)

    /** A profile id that gets the colour [colour] of the six. */
    private fun seedOf(colour: Int) = (0..200).map { "p$it" }.first { avatarColorIndex(it, 6) == colour }

    private fun show() {
        compose.setContent {
            val density = Density(LocalDensity.current.density, fontScale)
            CompositionLocalProvider(LocalLayoutDirection provides direction, LocalDensity provides density) {
                LirelleTheme(darkTheme = dark) {
                    scheme = MaterialTheme.colorScheme
                    Box(Modifier.testTag(Ground).background(Under).padding(Around)) {
                        ProfileAvatar(model, size, ringColor = Ring)
                    }
                }
            }
        }
    }

    /**
     * Runs [test] with a loader that counts the loads that failed, in place of the one every avatar shares;
     * the shared one is as it was afterwards.
     */
    @OptIn(DelicateCoilApi::class)
    private fun countingFailedLoads(test: (failed: AtomicInteger) -> Unit) {
        val failed = AtomicInteger()
        val listener = object : EventListener() {
            override fun onError(request: ImageRequest, result: ErrorResult) {
                failed.incrementAndGet()
            }
        }
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(ApplicationProvider.getApplicationContext()).eventListener(listener).build(),
        )
        try {
            test(failed)
        } finally {
            SingletonImageLoader.reset()
        }
    }

    private fun drawn(): PixelMap {
        compose.waitForIdle()
        return compose.onNodeWithTag(Ground).captureToImage().toPixelMap()
    }

    private fun Color.near(other: Color) =
        abs(red - other.red) < 0.02f && abs(green - other.green) < 0.02f && abs(blue - other.blue) < 0.02f

    /** Where [colour] is drawn: every pixel of it, as parts of the width and of the height. */
    private fun PixelMap.placesOf(colour: Color): List<Pair<Float, Float>> =
        (0 until width).flatMap { x ->
            (0 until height)
                .filter { y -> this[x, y].near(colour) }
                .map { y -> x / width.toFloat() to y / height.toFloat() }
        }

    /** The ground of the disc: at mid-height, well to the start of the initial. */
    private fun PixelMap.discGround() = this[width * 3 / 10, height / 2]

    private fun PixelMap.centre() = this[width / 2, height / 2]

    private fun pictureFile(name: String, colour: Color): File =
        File(folder.root, name).apply { writeBytes(png(colour)) }

    private fun png(colour: Color): ByteArray {
        val bitmap = Bitmap.createBitmap(PictureSide, PictureSide, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(colour.toArgb())
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun contrast(a: Color, b: Color): Float {
        val (light, dim) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05f) / (dim + 0.05f)
    }

    @Test
    fun `without a picture the initial lies on the colour its profile was given, in both themes`() {
        show()

        listOf(false, true).forEach { theme ->
            dark = theme
            model = model(seedOf(colour = 0))
            val first = drawn()
            model = model(seedOf(colour = 2))
            val second = drawn()

            assertWithMessage("dark $theme: primary").that(first.discGround().near(scheme.primary)).isTrue()
            assertWithMessage("dark $theme: tertiary").that(second.discGround().near(scheme.tertiary)).isTrue()
            assertWithMessage("dark $theme: two profiles").that(first.discGround().near(second.discGround())).isFalse()
            // The initial is written on it, in the ink the theme pairs with that ground.
            assertWithMessage("dark $theme: the initial").that(first.placesOf(scheme.onPrimary)).isNotEmpty()
            // Round: the corner of its square is what lies under it.
            assertThat(first[first.width * 3 / 16, first.height * 3 / 16].near(Under)).isTrue()
        }
    }

    @Test
    fun `every colour an initial may lie on reads, in both themes, and they are six different ones`() {
        show()

        listOf(false, true).forEach { theme ->
            dark = theme
            compose.waitForIdle()
            val colours = scheme.avatarColors()

            assertThat(colours.map { it.ground }.distinct()).hasSize(6)
            colours.forEachIndexed { at, pair ->
                assertWithMessage("dark $theme, colour $at")
                    .that(contrast(pair.ground, pair.content)).isAtLeast(Readable)
            }
            assertThat(contrast(scheme.error, scheme.onError)).isAtLeast(Readable)
        }
    }

    @Test
    fun `a profile keeps its colour from a process to another, and the profiles share the six`() {
        // Written down, not computed: the value must not move with the code.
        assertThat(avatarColorIndex("a", 6)).isEqualTo(0)
        assertThat(avatarColorIndex("3fa9c1d2", 6)).isEqualTo(avatarColorIndex("3fa9c1d2", 6))
        assertThat((0..200).map { avatarColorIndex("p$it", 6) }.toSet()).containsExactly(0, 1, 2, 3, 4, 5)
    }

    @Test
    fun `the picture kept is drawn in the disc, in place of the initial`() {
        model = model(seedOf(colour = 0), pictureFile("picture", PictureColor))
        show()

        compose.waitUntil(LoadLimitMillis) { drawn().centre().near(PictureColor) }

        val pixels = drawn()
        assertThat(pixels.discGround().near(PictureColor)).isTrue()
        assertThat(pixels.placesOf(scheme.primary)).isEmpty()
        // Cropped to the circle, and the badge still over it.
        assertThat(pixels[pixels.width * 3 / 16, pixels.height * 3 / 16].near(Under)).isTrue()
        assertThat(pixels.placesOf(Badge)).isNotEmpty()
    }

    @Test
    fun `a picture that cannot be read leaves the initial`() = countingFailedLoads { failed ->
        model = model(seedOf(colour = 0), File(folder.root, "broken").apply { writeText("not a picture") })
        show()

        // Until the load has failed, the initial is there anyway: only then does the avatar say what it does of it.
        compose.waitUntil(LoadLimitMillis) { failed.get() > 0 }

        val pixels = drawn()
        assertThat(pixels.discGround().near(scheme.primary)).isTrue()
        assertThat(pixels.placesOf(scheme.onPrimary)).isNotEmpty()
    }

    @Test
    fun `a picture that replaced another at the same place is the one drawn`(): Unit = runBlocking {
        val kept = FileProfileAvatars(folder.newFolder("avatars"))
        kept.keep("p0", "/avatar?v=1", png(PictureColor))
        model = model("p0", kept.picture("p0").first())
        show()
        compose.waitUntil(LoadLimitMillis) { drawn().centre().near(PictureColor) }

        kept.keep("p0", "/avatar?v=2", png(NewPictureColor))
        model = model("p0", kept.picture("p0").first())

        compose.waitUntil(LoadLimitMillis) { drawn().centre().near(NewPictureColor) }
    }

    @Test
    fun `the badge is at the bottom end, ringed by the ground it was given, in every size`() {
        show()
        ProfileAvatarSize.entries.forEach { shown ->
            size = shown

            val pixels = drawn()
            val badge = pixels.placesOf(Badge)

            assertWithMessage("$size: the badge").that(badge).isNotEmpty()
            assertWithMessage("$size: at the end").that(badge.all { (x, _) -> x > 0.5f }).isTrue()
            assertWithMessage("$size: at the bottom").that(badge.all { (_, y) -> y > 0.5f }).isTrue()
            assertWithMessage("$size: its ring").that(pixels.placesOf(Ring)).isNotEmpty()
            assertWithMessage("$size: no mark").that(pixels.placesOf(scheme.error)).isEmpty()
        }
    }

    @Test
    fun `the badge has the colours of the model, the same in both themes`() {
        show()
        listOf(false, true).forEach { theme ->
            dark = theme

            val pixels = drawn()

            assertWithMessage("dark $theme: its ground").that(pixels.placesOf(Badge)).isNotEmpty()
            assertWithMessage("dark $theme: its letter").that(pixels.placesOf(BadgeLetter)).isNotEmpty()
        }
    }

    @Test
    fun `from right to left the badge and the mark are at the layout's end, the left`() {
        direction = LayoutDirection.Rtl
        model = model(seedOf(colour = 0), signInNeeded = true)
        show()
        ProfileAvatarSize.entries.forEach { shown ->
            size = shown

            val pixels = drawn()
            val badge = pixels.placesOf(Badge)
            val mark = pixels.placesOf(scheme.error)

            assertWithMessage("$size: the badge").that(badge).isNotEmpty()
            assertWithMessage("$size: the badge at the left").that(badge.all { (x, _) -> x < 0.5f }).isTrue()
            assertWithMessage("$size: the badge at the bottom").that(badge.all { (_, y) -> y > 0.5f }).isTrue()
            assertWithMessage("$size: the mark").that(mark).isNotEmpty()
            assertWithMessage("$size: the mark at the left").that(mark.all { (x, _) -> x < 0.5f }).isTrue()
            assertWithMessage("$size: the mark at the top").that(mark.all { (_, y) -> y < 0.5f }).isTrue()
        }
    }

    @Test
    fun `text twice as large changes nothing of what is drawn, the letters stay in their circles`() {
        model = model(seedOf(colour = 0), signInNeeded = true)
        show()
        ProfileAvatarSize.entries.forEach { shown ->
            size = shown
            fontScale = 1f
            val plain = drawn()

            fontScale = 2f
            val large = drawn()

            assertWithMessage("$size: its width").that(large.width).isEqualTo(plain.width)
            assertWithMessage("$size: its height").that(large.height).isEqualTo(plain.height)
            val moved = (0 until plain.width).sumOf { x ->
                (0 until plain.height).count { y -> !plain[x, y].near(large[x, y]) }
            }
            assertWithMessage("$size: pixels that changed").that(moved).isEqualTo(0)
            assertWithMessage("$size: the letters").that(large.placesOf(scheme.onPrimary)).isNotEmpty()
        }
    }

    /** The size of the sign-in screens' bar, beside a line of text: the smallest, and nothing of it is lost. */
    @Test
    fun `the small size is 28 dp across, and still draws the initial, the badge and the mark`() {
        size = ProfileAvatarSize.Small
        model = model(seedOf(colour = 0), signInNeeded = true)
        show()

        val pixels = drawn()

        val across = with(compose.density) { (28.dp + Around * 2).roundToPx() }
        assertThat(pixels.width).isEqualTo(across)
        assertThat(pixels.height).isEqualTo(across)
        assertThat(ProfileAvatarSize.entries.minBy { it.diameter }).isEqualTo(ProfileAvatarSize.Small)
        assertWithMessage("the disc").that(pixels.discGround().near(scheme.primary)).isTrue()
        assertWithMessage("the initial").that(pixels.placesOf(scheme.onPrimary)).isNotEmpty()
        assertWithMessage("the badge").that(pixels.placesOf(Badge)).isNotEmpty()
        assertWithMessage("the mark").that(pixels.placesOf(scheme.error)).isNotEmpty()
    }

    @Test
    fun `the mark of a sign-in needed is at the top end, and only when it is needed`() {
        show()
        assertThat(drawn().placesOf(scheme.error)).isEmpty()

        listOf(false, true).forEach { theme ->
            dark = theme
            model = model(seedOf(colour = 0), signInNeeded = true)

            val mark = drawn().placesOf(scheme.error)

            assertWithMessage("dark $theme: the mark").that(mark).isNotEmpty()
            assertWithMessage("dark $theme: at the end").that(mark.all { (x, _) -> x > 0.5f }).isTrue()
            assertWithMessage("dark $theme: at the top").that(mark.all { (_, y) -> y < 0.5f }).isTrue()

            model = model(seedOf(colour = 0), signInNeeded = false)
            assertWithMessage("dark $theme: gone").that(drawn().placesOf(scheme.error)).isEmpty()
        }
    }

    @Test
    fun `it says nothing to a screen reader`() {
        model = model(seedOf(colour = 0), pictureFile("picture", PictureColor), signInNeeded = true)
        show()
        compose.waitUntil(LoadLimitMillis) { drawn().centre().near(PictureColor) }

        val said = SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) or
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)

        compose.onAllNodes(said, useUnmergedTree = true).assertCountEquals(0)
    }
}
