package app.tfdn.lirelle.feature.player.car

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tfdn.lirelle.feature.player.R
import app.tfdn.lirelle.feature.player.service.FakeEngine
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
class CarControllersTest {

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

    @Test
    fun `Android Auto is a car, a car's own system and other apps are not`() {
        val session = MediaSession.Builder(context, FakeEngine()).setId("car-controllers-test").build()
        try {
            val cars = CarControllers.Media3
            assertThat(cars.isCar(session, controller("com.google.android.projection.gearhead"))).isTrue()
            assertThat(cars.isCar(session, controller("com.android.car.media"))).isFalse()
            assertThat(cars.isCar(session, controller("com.android.car.carlauncher"))).isFalse()
            assertThat(cars.isCar(session, controller("com.example.watch"))).isFalse()
            assertThat(cars.isCar(session, controller("com.android.systemui"))).isFalse()
            assertThat(cars.isCar(session, controller(context.packageName))).isFalse()
        } finally {
            session.release()
        }
    }
}
