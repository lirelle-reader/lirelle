@file:OptIn(UnstableApi::class)

package app.tfdn.lirelle.feature.player.car

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList

/**
 * How a car is told why a place lists nothing ([CarNotice]): the one place that decides it. A car must never
 * show an empty screen without a word; which of the two ways it really shows is seen on a car, and the playback
 * service is made with that one.
 */
fun interface CarNotices {
    /** What the list asked by [car] answers for [notice]; the car may be told something besides. */
    fun answer(notice: CarNotice, car: CarLink): LibraryResult<ImmutableList<MediaItem>>
}

/**
 * The list fails, and the car is sent the sentence as an error of the session: Media3 writes it as the error
 * message of the platform's playback state for a car that reads that one — Android Auto on a phone does —, for
 * the time of one update, and hands it to a Media3 browser as it is.
 *
 * No notice has a code that Media3 repeats by itself into the platform's state
 * ([SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED], which a sign-in needed would otherwise be): that copy
 * stays until a list succeeds, on the session the phone shows too.
 */
class NoticeAsError(private val texts: CarTexts) : CarNotices {
    override fun answer(notice: CarNotice, car: CarLink): LibraryResult<ImmutableList<MediaItem>> {
        val error = SessionError(notice.code, texts.notice(notice))
        car.say(error)
        return LibraryResult.ofError(error)
    }

    private val CarNotice.code: Int
        get() = when (this) {
            CarNotice.NothingToListen -> SessionError.ERROR_NOT_SUPPORTED
            CarNotice.SignInNeeded -> SessionError.ERROR_PERMISSION_DENIED
            CarNotice.Unreachable -> SessionError.ERROR_IO
        }
}

/**
 * The list succeeds with one line, titled with the sentence, that is neither played nor opened: for a car that
 * shows nothing of an error.
 */
class NoticeAsLine(private val texts: CarTexts) : CarNotices {
    override fun answer(notice: CarNotice, car: CarLink): LibraryResult<ImmutableList<MediaItem>> {
        val line = MediaMetadata.Builder()
            .setTitle(texts.notice(notice))
            .setIsPlayable(false)
            .setIsBrowsable(false)
            // A sentence, not a cover: a line, even where the books around it are a grid.
            .setExtras(
                Bundle().apply {
                    putInt(
                        MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM,
                        MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
                    )
                },
            )
            .build()
            .item(LinePrefix + notice.name)
        return LibraryResult.ofItemList(listOf(line), null)
    }

    private companion object {
        /** The id of a line: no place and no book ([CarIds]), so that a tap on it asks for nothing. */
        const val LinePrefix = "notice/"
    }
}
