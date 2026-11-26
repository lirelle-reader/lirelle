@file:OptIn(UnstableApi::class)

package app.tfdn.lirelle.feature.player.car

import android.content.ContentResolver
import android.content.res.Resources
import android.net.Uri
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService.LibraryParams
import app.tfdn.lirelle.feature.player.R

/**
 * What a car is handed for the root, a tab, a book and a series: the media items it draws. The car chooses the
 * look; the items say what is played and what is opened, the words, the pictures, the groups of the Home tab,
 * and that books are a grid of covers and folders a list.
 *
 * @param coverAuthority the authority of the app's covers ([CarCovers.authority]).
 * @param profileId the profile the covers are those of.
 */
class CarItems(
    private val resources: Resources,
    private val coverAuthority: String,
    private val profileId: String,
) {
    private val texts = CarTexts(resources)

    /**
     * What the root answers with: the content styles, for a car that reads them of the root rather than of
     * each folder. That a search can be asked is said by Media3 itself, from the commands the car was given.
     */
    val rootParams: LibraryParams get() = LibraryParams.Builder().setExtras(contentStyles()).build()

    /** The root: the car never shows it, only what it lists. */
    fun root(): MediaItem = folder(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build().item(CarIds.of(CarPlace.Root))

    fun of(node: CarNode): MediaItem = when (node) {
        is CarNode.Tab -> folder(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            .setTitle(texts.tab(node.tab))
            .setArtworkUri(drawableUri(node.tab.icon))
        is CarNode.Series -> folder(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
            .setTitle(node.name)
            .setSubtitle(texts.seriesBooks(node.books))
        is CarNode.Book -> MediaMetadata.Builder()
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
            .setTitle(node.title)
            .setSubtitle(node.line?.let(texts::line))
            .setArtworkUri(CarCovers.uri(coverAuthority, profileId, node.id))
            .setExtras(node.section?.let(::group))
    }.build().item(CarIds.of(node))

    /** What is opened, never played: its books show as a grid of covers, its folders as a list. */
    private fun folder(mediaType: @MediaMetadata.MediaType Int): MediaMetadata.Builder = MediaMetadata.Builder()
        .setIsBrowsable(true)
        .setIsPlayable(false)
        .setMediaType(mediaType)
        .setExtras(contentStyles())

    private fun contentStyles(): Bundle = Bundle().apply {
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
    }

    /** The title the car writes above the books of [section]: neighbours with the same one make a group. */
    private fun group(section: CarSection): Bundle = Bundle().apply {
        putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, texts.section(section))
    }

    /**
     * The address of a drawable of the app, by its name: the car's host loads it from the app's resources, where
     * the number of a resource is not the one this module was compiled with.
     */
    private fun drawableUri(@DrawableRes id: Int): Uri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(resources.getResourcePackageName(id))
        .appendPath(resources.getResourceTypeName(id))
        .appendPath(resources.getResourceEntryName(id))
        .build()
}

private val CarPlace.Tab.icon: Int
    @DrawableRes get() = when (this) {
        CarPlace.Tab.Home -> R.drawable.ic_car_home
        CarPlace.Tab.Downloaded -> R.drawable.ic_car_downloaded
        CarPlace.Tab.Audiobooks -> R.drawable.ic_car_audiobooks
        CarPlace.Tab.Series -> R.drawable.ic_car_series
    }

/** The item of the media id [id] that shows this. */
internal fun MediaMetadata.item(id: String): MediaItem =
    MediaItem.Builder().setMediaId(id).setMediaMetadata(this).build()
