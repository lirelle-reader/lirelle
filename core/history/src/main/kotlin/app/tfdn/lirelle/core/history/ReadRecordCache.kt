package app.tfdn.lirelle.core.history

import app.tfdn.lirelle.core.history.model.ReadRecord
import app.tfdn.lirelle.core.ids.ReadId
import app.tfdn.lirelle.core.session.profile.ProfileScoped
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

private const val MaxRecords = 200

/** A read whose rating changed; [stars] is null when it was cleared. */
data class RatingUpdate(val id: ReadId, val stars: Int?)

/**
 * The records of the last pages loaded, so that a read's detail opens without a request. Bounded. One per
 * profile: what one account read is never there for another.
 */
@ProfileScoped
class ReadRecordCache @Inject constructor() {

    private val records = object : LinkedHashMap<ReadId, ReadRecord>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ReadId, ReadRecord>?) = size > MaxRecords
    }

    private val mutableRatingUpdates = MutableSharedFlow<RatingUpdate>(extraBufferCapacity = 16)

    /** The rating changes of cached reads, so a list showing them can follow. */
    val ratingUpdates: SharedFlow<RatingUpdate> = mutableRatingUpdates.asSharedFlow()

    fun put(list: List<ReadRecord>) = synchronized(records) {
        list.forEach {
            records.remove(it.id)
            records[it.id] = it
        }
    }

    fun get(id: ReadId): ReadRecord? = synchronized(records) { records[id] }

    /** Sets the rating of a cached read and announces it. Does nothing when [id] is not cached. */
    fun updateRating(id: ReadId, stars: Int?) {
        synchronized(records) {
            val record = records[id] ?: return
            records[id] = record.copy(rating = stars)
        }
        mutableRatingUpdates.tryEmit(RatingUpdate(id, stars))
    }
}
