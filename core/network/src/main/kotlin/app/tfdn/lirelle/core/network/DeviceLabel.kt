package app.tfdn.lirelle.core.network

import android.content.Context
import android.os.Build
import android.provider.Settings

private const val Suffix = " (Lirelle)"
private const val ServerLimit = 100

/** Label shown in BookOrbit's session list, for example "Galaxy Tab S10+ (Lirelle)". */
fun formatDeviceLabel(deviceName: String): String {
    val name = deviceName.trim().take(ServerLimit - Suffix.length)
    // Cutting between the two halves of an emoji would leave an unpaired surrogate.
    val whole = if (name.lastOrNull()?.isHighSurrogate() == true) name.dropLast(1) else name
    return whole + Suffix
}

fun interface DeviceLabelProvider {
    fun label(): String
}

/** The device's own name, without the app's name that [DeviceLabelProvider.label] adds: "Galaxy Tab S10+". */
fun DeviceLabelProvider.deviceName(): String = label().removeSuffix(Suffix)

/** Uses the name the user gave the device in Android settings, falling back to the model. */
class AndroidDeviceLabelProvider(private val context: Context) : DeviceLabelProvider {
    override fun label(): String {
        val userGiven = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        return formatDeviceLabel(userGiven?.takeIf { it.isNotBlank() } ?: Build.MODEL)
    }
}
