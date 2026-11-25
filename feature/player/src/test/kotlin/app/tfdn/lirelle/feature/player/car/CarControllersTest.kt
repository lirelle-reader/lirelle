package app.tfdn.lirelle.feature.player.car

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.core.testing.AndroidAutoCertificates
import app.tfdn.lirelle.core.testing.AndroidAutoPackage
import app.tfdn.lirelle.core.testing.InstalledAndroidAuto
import app.tfdn.lirelle.core.testing.SignedPackages
import app.tfdn.lirelle.feature.player.R
import app.tfdn.lirelle.feature.player.service.FakeEngine
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
@Config(shadows = [SignedPackages::class])
class CarControllersTest {

    @get:Rule
    val androidAuto = InstalledAndroidAuto()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun controller(packageName: String) =
        MediaSession.ControllerInfo.createTestOnlyControllerInfo(packageName, 0, 0, 0, 0, false, Bundle.EMPTY, false)

    @Test
    fun `the app says to the car that it is a media app`() {
        val application = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)

        assertThat(application.metaData.getInt("com.google.android.gms.car.application"))
            .isEqualTo(R.xml.automotive_app_desc)

        val uses = mutableListOf<String?>()
        val parser = context.resources.getXml(R.xml.automotive_app_desc)
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "uses") {
                uses += parser.getAttributeValue(null, "name")
            }
        }
        assertThat(uses).containsExactly("media")
    }

    private fun withSession(test: (MediaSession) -> Unit) {
        val session = MediaSession.Builder(context, FakeEngine()).setId("car-controllers-test").build()
        try {
            test(session)
        } finally {
            session.release()
        }
    }

    @Test
    fun `Android Auto is a car, a car's own system and other apps are not`() = withSession { session ->
        val cars = CarControllers.androidAuto(context.packageManager)
        listOf(AndroidAutoCertificates.Current, AndroidAutoCertificates.Older).forEach { certificate ->
            SignedPackages.sign(AndroidAutoPackage, certificate)

            assertThat(cars.isCar(session, controller(AndroidAutoPackage))).isTrue()
        }
        listOf("com.android.car.media", "com.android.car.carlauncher", "com.example.watch", "com.android.systemui")
            .forEach { assertThat(cars.isCar(session, controller(it))).isFalse() }
        assertThat(cars.isCar(session, controller(context.packageName))).isFalse()
    }

    @Test
    fun `an app that took Android Auto's name is not a car, nor is its name where it is not installed`() =
        withSession { session ->
            val cars = CarControllers.androidAuto(context.packageManager)

            SignedPackages.sign(AndroidAutoPackage, AndroidAutoCertificates.Stranger)
            assertThat(cars.isCar(session, controller(AndroidAutoPackage))).isFalse()
            SignedPackages.forget()
            assertThat(cars.isCar(session, controller(AndroidAutoPackage))).isFalse()
        }
}
