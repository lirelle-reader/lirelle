@file:OptIn(UnstableApi::class)

package app.tfdn.lirelle.feature.player.car

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import com.google.common.collect.ImmutableList

/**
 * How a car is told why a place lists nothing ([CarNotice]): the one place that decides it. A car must never
 * show an empty screen without a word.
 */
fun interface CarNotices {
    /** What the list asked by [car] answers for [notice]; the car may be told something besides. */
    fun answer(notice: CarNotice, car: CarLink): LibraryResult<ImmutableList<MediaItem>>
}

/**
 * The list succeeds with one line, titled with the sentence, that is neither played nor opened: Android Auto
 * shows nothing of a list that fails, nor of the error sent with it.
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
