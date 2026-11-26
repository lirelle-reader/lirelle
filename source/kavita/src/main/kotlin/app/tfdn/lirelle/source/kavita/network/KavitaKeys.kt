package app.tfdn.lirelle.source.kavita.network

import app.tfdn.lirelle.core.network.ApiResult
import app.tfdn.lirelle.core.network.DeviceLabelProvider
import app.tfdn.lirelle.core.network.deviceName
import app.tfdn.lirelle.core.session.Credential
import app.tfdn.lirelle.source.kavita.network.dto.AuthKeyDto
import app.tfdn.lirelle.source.kavita.network.dto.KavitaUserDto
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import retrofit2.Response

/** When a key was made, as its name says it: to the second, which keeps two keys of one device apart. */
private val NameTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** The same to the millisecond: for the name of a key whose name to the second the account already gave. */
private val PreciseNameTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

private const val BadRequest = 400

/** How Kavita says, in the text of its refusal, that the account already gave a key that name. */
private const val NameTaken = "unique"

/** How much of a refusal is looked through for [NameTaken]: it may be a long page. */
private const val RefusalBytesRead = 4L * 1024

/**
 * The device's own key, made with the session of a sign-in — by password or through a provider — and held by the
 * profile saved with it ([KavitaProfiles]).
 *
 * Once the key is asked for, it may exist on the server: making it and saving the profile go on to the end even
 * when the screen is left.
 *
 * @param now and [zone] say when a key is made, in its name.
 */
internal class KavitaKeys(
    private val profiles: KavitaProfiles,
    private val errors: KavitaErrors,
    private val deviceLabel: DeviceLabelProvider,
    private val now: () -> Instant,
    private val zone: () -> ZoneId,
) {

    /**
     * Reads the account with [proof], on a server the app works with, then makes the device's key with its
     * session ([keyFor]). Nothing is made for an account that cannot be read, nor on a server too old.
     */
    suspend fun signInWith(serverUrl: String, proof: KavitaSessionProof): ApiResult<Unit> {
        val account = when (val read = errors.call(proof.reading) { proof.account() }) {
            is ApiResult.Success -> read.value
            is ApiResult.Failure -> return read
        }
        return errors.call(KavitaCall.Mint) { keyFor(serverUrl, proof, account) }
    }

    /**
     * Makes the key with [proof]'s session and saves the profile of [account] on [serverUrl] with it, all to the
     * end whatever happens to the caller. A name the account already gave a key — two sign-ins of one device in
     * the same second — is tried again once, to the millisecond.
     */
    private suspend fun keyFor(serverUrl: String, proof: KavitaSessionProof, account: KavitaUserDto) =
        withContext(NonCancellable) {
            val device = deviceLabel.deviceName()
            val madeAt = LocalDateTime.ofInstant(now(), zone())
            val first = proof.mint(keyName(device, madeAt))
            val made = if (first.refusesItsName()) proof.mint(preciseKeyName(device, madeAt)) else first
            val key = made.answer()
            val credential = Credential.ApiKey(key = key.key, keyId = key.id.toString(), ownedByApp = true)
            profiles.save(serverUrl, credential, account)
        }

    /**
     * Whether this answer refuses the key for its name. The text of the refusal is only looked through, for one
     * word, and no further than [RefusalBytesRead]: it goes nowhere. Retrofit has read the body of a refusal whole
     * already, as it does of every answer that is an error: the bound is on what is looked through, not on what
     * was received.
     */
    private fun Response<AuthKeyDto>.refusesItsName(): Boolean {
        if (code() != BadRequest) return false
        return errorBody()?.use { body ->
            val source = body.source()
            source.request(RefusalBytesRead)
            val read = minOf(source.buffer.size, RefusalBytesRead)
            source.buffer.readUtf8(read).contains(NameTaken, ignoreCase = true)
        } ?: false
    }
}

/**
 * What a key made on [deviceName] at [madeAt] is named, as Kavita's web interface lists it:
 * "Lirelle — Galaxy Tab S10+ — 2026-11-22 10:15:30". The app, the device, and when, to the second: Kavita refuses
 * a name the account already gave a key, and two sign-ins of one device are seldom in the same second.
 */
internal fun keyName(deviceName: String, madeAt: LocalDateTime): String =
    "Lirelle — $deviceName — ${madeAt.format(NameTime)}"

/** [keyName] to the millisecond: "Lirelle — Galaxy Tab S10+ — 2026-11-22 10:15:30.250". */
internal fun preciseKeyName(deviceName: String, madeAt: LocalDateTime): String =
    "Lirelle — $deviceName — ${madeAt.format(PreciseNameTime)}"
