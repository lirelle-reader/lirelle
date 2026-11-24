package app.tfdn.lirelle.core.downloads.engine

import app.tfdn.lirelle.core.downloads.model.FormatRecord
import app.tfdn.lirelle.core.downloads.model.FormatState
import app.tfdn.lirelle.core.downloads.model.FormatUpdate
import app.tfdn.lirelle.core.downloads.model.WaitReason
import java.time.Instant

/**
 * The update of a complete format, once the user asked for it: its state then says where it is. Null while it
 * only waits for the user, and for a format being deleted.
 */
internal val FormatRecord.askedUpdate: FormatUpdate?
    get() = update?.takeIf { !removing && state is FormatState.Complete && it.state != null }

/** Its update is still to fetch: queued, on its way or waiting. */
internal val FormatRecord.isUpdatePending: Boolean
    get() = askedUpdate?.state.let { it != null && it !is FormatState.Failed && it !is FormatState.Complete }

internal val FormatRecord.isUpdateFailed: Boolean get() = askedUpdate?.state is FormatState.Failed

/** The files of its update are all fetched, beside the kept ones: they wait for the swap. */
internal val FormatRecord.isUpdateWhole: Boolean get() = askedUpdate?.state is FormatState.Complete

/**
 * Where what the queue has to do for this format is: its download, or else the update of what it keeps. Null
 * when the queue has nothing to do for it: complete with no update asked, or with one that only waits for the
 * swap, or being deleted.
 */
internal val FormatRecord.work: FormatState?
    get() = when {
        removing -> null
        state !is FormatState.Complete -> state
        else -> askedUpdate?.state?.takeUnless { it is FormatState.Complete }
    }

/** The user let what is still to fetch of this format, its download or its update, use a metered network. */
internal val FormatRecord.fetchesOnAnyNetwork: Boolean
    get() = (isPending && anyNetwork) || (isUpdatePending && update?.anyNetwork == true)

/** Null when the update of [record] may run now; otherwise what it waits for. */
internal fun updateWaitReason(record: FormatRecord, conditions: Conditions): WaitReason? =
    waitReason(record.update?.anyNetwork == true, conditions)

/** Whether the download of [record], or the update of what it keeps, may run now. */
internal fun mayRun(record: FormatRecord, conditions: Conditions): Boolean =
    (record.isPending && waitReason(record, conditions) == null) ||
        (record.isUpdatePending && updateWaitReason(record, conditions) == null)

/**
 * [this] with its update asked at [now]: it goes behind what the queue already holds, and follows the rule of
 * the network again.
 */
internal fun FormatRecord.updateQueued(now: Instant): FormatRecord =
    copy(askedAt = now, update = update?.copy(state = FormatState.Queued, anyNetwork = false))

/** [this] with its update no longer asked: it waits for the user again, with none of its pages, which go. */
internal fun FormatRecord.updateTakenBack(): FormatRecord =
    copy(update = update?.copy(state = null, anyNetwork = false, keptPages = 0))

/** The state of an update that waits for [reason]: before its first byte, what is left is what it will fetch. */
internal fun FormatUpdate.waiting(reason: WaitReason): FormatState = when (val state = state) {
    is FormatState.Downloading -> FormatState.Waiting(reason, state.receivedBytes, state.totalBytes)
    is FormatState.Waiting -> state.copy(reason = reason)
    else -> FormatState.Waiting(reason, 0, bytes)
}
