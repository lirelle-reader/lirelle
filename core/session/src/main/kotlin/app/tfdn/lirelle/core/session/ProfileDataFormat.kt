package app.tfdn.lirelle.core.session

/**
 * The format of what a profile kept in its folders before the app named books, files and the rest by ids of its
 * own: the stores keyed by the only server's numbers. A registry written then says no format: it reads as this.
 */
const val OldDataFormat = 1

/**
 * The format of what a profile keeps in its folders now, written for every profile made from now on. A profile
 * of an older format is not migrated store by store: its folders are emptied, once, when it is next opened.
 */
const val CurrentDataFormat = 2

/**
 * What whoever opens a profile tells the registry of the format of the profile's folders. A profile of an older
 * format ([Profile.dataFormat]) has its folders set aside before anything of it is built; then it is of the
 * current format, or, when they could not be, it is not opened at this start.
 */
interface ProfileDataFormats {
    /**
     * The folders of the profile [id] were emptied: from now on they hold data of the current format. Nothing
     * else of the profile changes, and nothing at all when it is gone, or already of the current format.
     *
     * @throws java.io.IOException when the registry's file could not be written: the profile is then still of
     *   its older format, and must not be opened at this start.
     */
    suspend fun dataStartedAgain(id: String)

    /**
     * The profile [id], whose folders could not be set aside, is not opened at this start: neither current nor
     * among the profiles, as one whose tokens could not be read for now. Nothing is written — its record stays
     * as it is, of its older format, current if it was — so that the next start tries again; at this one the
     * profile used last before it is current, or nobody. Its account signing in meanwhile takes it over.
     */
    suspend fun withhold(id: String)
}

/**
 * The profile [id]'s data started again: of the current format. Null when it is not ready, or already is.
 *
 * Only the format is written onto the stored entry: the session is not encoded again, so that its tokens are
 * not encrypted again. The keystore may refuse to encrypt at that moment, which no file write would explain,
 * and the profile would then be neither opened nor told apart from one whose folders could not be set aside.
 */
internal fun RegistrySnapshot.startingAgain(id: String): RegistrySnapshot? {
    val profile = ready[id]?.takeIf { it.dataFormat != CurrentDataFormat } ?: return null
    val profiles = stored.profiles.map { if (it.id == id) it.copy(dataFormat = CurrentDataFormat) else it }
    return copy(
        stored = stored.copy(profiles = profiles),
        ready = ready + (id to profile.copy(dataFormat = CurrentDataFormat)),
    )
}

/** The profile [id] out of the ready ones for this start, and nothing written of it. Null when it is not ready. */
internal fun RegistrySnapshot.withholding(id: String): RegistrySnapshot? =
    if (id in ready) copy(ready = ready - id) else null

/** What [ProfileDataFormats] changes in the registry, one change at a time with every other. */
internal class LedgerFormats(private val ledger: ProfileLedger) : ProfileDataFormats {

    override suspend fun dataStartedAgain(id: String) = ledger.change { it.startingAgain(id) }

    override suspend fun withhold(id: String) = ledger.changeUnwritten { it.withholding(id) }
}
