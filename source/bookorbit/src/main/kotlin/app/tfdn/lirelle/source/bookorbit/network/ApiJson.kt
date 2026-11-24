package app.tfdn.lirelle.source.bookorbit.network

import kotlinx.serialization.json.Json

/**
 * JSON configuration for every call to the BookOrbit API.
 * Unknown fields are ignored so additive server changes do not break parsing.
 * Defaults are encoded because the server needs `clientKind` on the wire.
 */
internal val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
