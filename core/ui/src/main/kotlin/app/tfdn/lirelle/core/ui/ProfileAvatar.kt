package app.tfdn.lirelle.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import java.io.File

/**
 * The four places an avatar is drawn in: beside a line of text — the bar of the sign-in screens shown over a
 * profile —, the profile button, a row of the list of profiles, a page's header.
 */
enum class ProfileAvatarSize(val diameter: Dp) { Small(28.dp), Button(36.dp), Row(44.dp), Header(56.dp) }

/**
 * What the avatar draws of a profile; no type of the profiles' registry here, so that the drawing can be
 * shown, and tested, with nothing of it.
 *
 * @property seed the profile's id: chooses the colour under the initial, the same at every start.
 * @property initial the first letter of the username, upper case.
 * @property kindLetter the letter of the profile's kind of server, "B" for BookOrbit.
 * @property badgeGround the colour of the badge [kindLetter] is written on: its kind's own, the same in the light
 *   and in the dark theme.
 * @property badgeContent the colour of [kindLetter] on [badgeGround].
 * @property picture the account's picture as the device keeps it, or null. A picture that replaced another
 *   must be a file that does not equal the one before, even at the same path: an equal file is not loaded
 *   again.
 * @property signInNeeded the profile waits for its account to sign in again.
 */
data class ProfileAvatarModel(
    val seed: String,
    val initial: String,
    val kindLetter: String,
    val badgeGround: Color,
    val badgeContent: Color,
    val picture: File?,
    val signInNeeded: Boolean,
)

/** The height of the initial as a part of the avatar's diameter. */
private const val InitialOverDiameter = 0.43f

/** The badge and the mark, ring included, as a part of the avatar's diameter. */
private const val BadgeOverDiameter = 0.44f

/** How far the badge and the mark stand out of the avatar's square, as a part of its diameter. */
private const val OverhangOverDiameter = 0.09f

/** The ring around the badge and the mark, as a part of their own diameter. */
private const val RingOverBadge = 0.125f

/** The height of the letter of the badge and of the mark, as a part of their own diameter. */
private const val LetterOverBadge = 0.6f

private const val SignInMark = "!"

/** A ground and what is written on it. */
internal data class AvatarColors(val ground: Color, val content: Color)

/**
 * The grounds an initial may lie on, each with the ink the theme pairs with it: all six read at 4.5:1 or
 * better in the light and in the dark palette (the weakest, secondary in the light theme, is at 6.4:1).
 * Their order and their number decide which profile gets which: changing either changes the colours of the
 * profiles already on a device.
 */
internal fun ColorScheme.avatarColors(): List<AvatarColors> = listOf(
    AvatarColors(primary, onPrimary),
    AvatarColors(secondary, onSecondary),
    AvatarColors(tertiary, onTertiary),
    AvatarColors(primaryContainer, onPrimaryContainer),
    AvatarColors(secondaryContainer, onSecondaryContainer),
    AvatarColors(tertiaryContainer, onTertiaryContainer),
)

private const val FnvOffset = 0x811C9DC5.toInt()
private const val FnvPrime = 0x01000193

/**
 * Which of [count] colours [seed] gets: FNV-1a over its characters, written out here rather than
 * `String.hashCode()`, so that the pick rests on nothing a platform could define otherwise, and is the same
 * in every process for as long as [count] is.
 */
internal fun avatarColorIndex(seed: String, count: Int): Int {
    val hash = seed.fold(FnvOffset) { hash, char -> (hash xor char.code) * FnvPrime }
    return Math.floorMod(hash, count)
}

/**
 * The avatar of a profile: the account's picture when the device keeps one, otherwise its initial on a colour
 * of its own; over it, at the bottom end, the letter of its kind of server and, at the top end, a mark when
 * the profile waits for a sign-in. The badge of the kind has the kind's own colours, whatever the theme. The one
 * place a profile is drawn, so that a logo can replace the letter later here alone.
 *
 * The badge and the mark stand a little out of the avatar's square, as on the canvas: its size is the
 * diameter, whatever stands out.
 *
 * It says nothing to a screen reader: the letters are drawn, not laid out as text, and the picture has no
 * description. The caller describes the profile, in words.
 *
 * @param ringColor the ground the avatar lies on, which rings the badge and the mark to set them off from
 *   the avatar.
 */
@Composable
fun ProfileAvatar(
    model: ProfileAvatarModel,
    size: ProfileAvatarSize,
    modifier: Modifier = Modifier,
    ringColor: Color = MaterialTheme.colorScheme.surface,
) {
    val scheme = MaterialTheme.colorScheme
    val overhang = size.diameter * OverhangOverDiameter
    Box(modifier = modifier.size(size.diameter)) {
        Disc(model, size.diameter)
        AvatarBadge(
            letter = model.kindLetter,
            colors = AvatarColors(model.badgeGround, model.badgeContent),
            ringColor = ringColor,
            modifier = Modifier.align(Alignment.BottomEnd).offset(x = overhang, y = overhang),
            diameter = size.diameter * BadgeOverDiameter,
        )
        if (model.signInNeeded) {
            AvatarBadge(
                letter = SignInMark,
                colors = AvatarColors(scheme.error, scheme.onError),
                ringColor = ringColor,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = overhang, y = -overhang),
                diameter = size.diameter * BadgeOverDiameter,
            )
        }
    }
}

/** The round part: the initial on the profile's colour, and the picture over it once it is there. */
@Composable
private fun BoxScope.Disc(model: ProfileAvatarModel, diameter: Dp) {
    val colors = MaterialTheme.colorScheme.avatarColors().let { it[avatarColorIndex(model.seed, it.size)] }
    // Not the picture's own ground: one that is still loading, or cannot be read, leaves the initial.
    var pictured by remember(model.picture) { mutableStateOf(false) }
    val initial = Letter(model.initial, colors.content, InitialOverDiameter, FontWeight.Medium)
    val measurer = rememberTextMeasurer()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(CircleShape)
            .then(if (pictured) Modifier else Modifier.background(colors.ground).letter(initial, measurer)),
    ) {
        model.picture?.let { picture -> Picture(picture, diameter) { pictured = it } }
    }
}

/**
 * The picture kept in [picture], cropped to the disc and decoded at the size it is shown: the size of the
 * image asked of the loader is the one this is laid out at. Loaded by Coil's own default loader, which reads
 * a file of the device and nothing else here: never by the loader of a profile's covers, which is gone with
 * its profile while the avatar of a sleeping profile is still shown.
 *
 * @param onShown told whether the picture is drawn: not while it loads, nor when it cannot be read.
 */
@Composable
private fun Picture(picture: File, diameter: Dp, onShown: (Boolean) -> Unit) {
    val context = LocalPlatformContext.current
    val request = remember(picture, diameter, context) {
        ImageRequest.Builder(context)
            // A plain file: what is kept may be told as a file of a kind of its own, which a loader need not know.
            .data(File(picture.path))
            // The loader would tell two pictures of one path apart by the file's date alone; a picture that
            // replaced another is a file that does not equal it, and neither does its hash.
            .memoryCacheKey("profile-avatar:${picture.path}:${picture.hashCode()}:$diameter")
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        imageLoader = SingletonImageLoader.get(context),
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
        onLoading = { onShown(false) },
        onSuccess = { onShown(true) },
        onError = { onShown(false) },
    )
}

/** A small disc with one letter, ringed by [ringColor]: the kind of server, or the mark of a sign-in needed. */
@Composable
private fun AvatarBadge(letter: String, colors: AvatarColors, ringColor: Color, modifier: Modifier, diameter: Dp) {
    Box(
        modifier = modifier
            .size(diameter)
            .background(ringColor, CircleShape)
            .padding(diameter * RingOverBadge)
            .background(colors.ground, CircleShape)
            // The letter is measured against the badge, ring included; it is drawn inside the ring.
            .letter(
                Letter(letter, colors.content, LetterOverBadge / (1 - 2 * RingOverBadge), FontWeight.Bold),
                rememberTextMeasurer(),
            ),
    )
}

/** One letter of the avatar: the initial, the letter of the badge, or the mark. */
private class Letter(val text: String, val color: Color, val heightOverSize: Float, val weight: FontWeight)

/**
 * Draws [letter] in the middle of what it modifies, [Letter.heightOverSize] of its height tall. Drawn, not
 * laid out as a text: it is decoration, sized to the disc and not to the system's text size — a letter that
 * grew would be cut by the circle —, and a screen reader finds no text to read.
 */
private fun Modifier.letter(letter: Letter, measurer: TextMeasurer): Modifier = drawBehind {
    // `toSp` cancels the font scale: the letter follows the disc.
    val style = TextStyle(
        color = letter.color,
        fontSize = (size.height * letter.heightOverSize).toSp(),
        fontWeight = letter.weight,
    )
    val laidOut = measurer.measure(letter.text, style, softWrap = false, maxLines = 1)
    drawText(
        laidOut,
        topLeft = Offset((size.width - laidOut.size.width) / 2f, (size.height - laidOut.size.height) / 2f),
    )
}
