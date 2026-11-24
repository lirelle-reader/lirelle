package app.tfdn.lirelle.core.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext

/**
 * The first download asked: the system's dialog for the permission to show notifications, unless the system
 * already gave it. The download does not wait for the answer, and the question is not asked again.
 *
 * @param onHandled called once the question is dealt with; `true` when the dialog was shown.
 */
@Composable
fun NotificationsQuestion(ask: Boolean, onHandled: (asked: Boolean) -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(ask) {
        if (!ask) return@LaunchedEffect
        val permission = Manifest.permission.POST_NOTIFICATIONS
        val granted = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(permission)
        onHandled(!granted)
    }
}
