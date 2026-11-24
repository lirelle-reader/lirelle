package app.tfdn.lirelle.core.testing

import org.junit.rules.ExternalResource
import org.robolectric.RuntimeEnvironment

/**
 * Sets the screen, the language and the system text size of the Robolectric device.
 *
 * It must run before the activity under test starts: give it a lower `order` than the Compose rule.
 *
 * @param qualifiers resource qualifiers in their canonical order, for example `fr-w320dp-h640dp`.
 * @param fontScale the system font scale, 1 being the default text size.
 */
class DeviceRule(
    private val qualifiers: String,
    private val fontScale: Float,
) : ExternalResource() {

    override fun before() {
        RuntimeEnvironment.setQualifiers("+$qualifiers")
        RuntimeEnvironment.setFontScale(fontScale)
    }
}
