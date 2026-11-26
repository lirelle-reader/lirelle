package app.tfdn.lirelle.source.kavita.testing

import app.tfdn.lirelle.core.network.DeviceLabelProvider
import app.tfdn.lirelle.core.network.NetworkLog
import app.tfdn.lirelle.core.network.formatDeviceLabel
import app.tfdn.lirelle.core.session.SessionStore
import app.tfdn.lirelle.source.kavita.network.KavitaApis
import app.tfdn.lirelle.source.kavita.network.KavitaErrors
import app.tfdn.lirelle.source.kavita.network.KavitaJson
import app.tfdn.lirelle.source.kavita.network.KavitaPublicApi
import app.tfdn.lirelle.source.kavita.network.KavitaRevocation
import app.tfdn.lirelle.source.kavita.network.KavitaSignIn
import app.tfdn.lirelle.source.kavita.network.kavitaSignInClient
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import okhttp3.OkHttpClient

/** When the tests' keys are made: 10:15:30 and a quarter of a second, in Paris. */
val KeyMadeAt: Instant = Instant.parse("2026-11-22T09:15:30.250Z")

/** The name of a key made at [KeyMadeAt] on the tests' device. */
const val KeyName = "Lirelle — Pixel 9 — 2026-11-22 10:15:30"

/** The same to the millisecond: the name tried when the account already gave [KeyName]. */
const val PreciseKeyName = "Lirelle — Pixel 9 — 2026-11-22 10:15:30.250"

/**
 * Kavita's sign-in as the app makes it, saving to [store]: on a Pixel 9, in Paris, at [now], its deletions given
 * up after a second and told to [log].
 */
internal fun kavitaSignIn(
    store: SessionStore,
    now: () -> Instant = { KeyMadeAt },
    log: NetworkLog = NetworkLog.None,
) = KavitaSignIn(
    store = store,
    api = KavitaApis(kavitaSignInClient(OkHttpClient()), KavitaJson, KavitaPublicApi::class.java),
    errors = KavitaErrors(),
    deviceLabel = DeviceLabelProvider { formatDeviceLabel("Pixel 9") },
    revocation = KavitaRevocation(OkHttpClient(), KavitaJson, timeout = Duration.ofSeconds(1), log = { log }),
    now = now,
    zone = { ZoneId.of("Europe/Paris") },
)
