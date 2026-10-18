package io.github.freeyoung.tether

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import io.github.freeyoung.tether.ui.TetherAppUi
import io.github.freeyoung.tether.ui.TetherTheme

class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        // Without the local network permission the engine cannot listen:
        // start it again once the user allowed it.
        if (Build.VERSION.SDK_INT >= 37 && granted[Manifest.permission.ACCESS_LOCAL_NETWORK] == true && Prefs.enabled(this)) {
            TetherService.stop(this)
            TetherService.start(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askForPermissions()
        if (Prefs.enabled(this)) TetherService.start(this)
        setContent {
            TetherTheme {
                TetherAppUi()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Tether.visibleScreens++
        ClipboardWatcher.onAppVisible(this)
    }

    override fun onStop() {
        Tether.visibleScreens--
        ClipboardWatcher.onAppHidden(this)
        // A request the user did not answer in the app goes on in a notification.
        Tether.state.value?.pending?.forEach { r ->
            Notifications.pairingRequested(this, Event("pairing_requested", fingerprint = r.fingerprint, name = r.name, kind = r.kind))
        }
        super.onStop()
    }

    // Like the iPhone app, the app sends what the user copied when it comes to
    // the front: only then may it read the clipboard.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) PhoneClipboard.send(this, force = false)
    }

    private fun askForPermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) permissions.launch(wanted.toTypedArray())
    }
}
