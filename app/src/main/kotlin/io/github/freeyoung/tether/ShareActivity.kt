package io.github.freeyoung.tether

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.freeyoung.tether.ui.DeviceChooser
import io.github.freeyoung.tether.ui.TetherTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// ShareActivity takes what the user shares to Tether. Text goes to the
// clipboards of the paired devices; files go to the device the user picks.
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = streams(intent)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (uris.isEmpty() && text.isNullOrEmpty()) {
            finish()
            return
        }
        lifecycleScope.launch {
            if (!awaitEngine()) {
                done(getString(R.string.not_running))
            } else if (uris.isEmpty()) {
                Tether.copyText(text!!)
                History.clip(text, null, null)
                done(PhoneClipboard.message(this@ShareActivity, PhoneClipboard.Result.Sent))
            } else {
                pickPeer(uris)
            }
        }
    }

    // Android may have ended the process: start the engine again, and give
    // the paired computers a moment to connect.
    private suspend fun awaitEngine(): Boolean {
        if (Tether.running) return true
        if (!Prefs.enabled(this)) return false
        TetherService.start(this)
        withTimeoutOrNull(5000) { Tether.state.first { it != null && it.connected.isNotEmpty() } }
        return Tether.running
    }

    private fun pickPeer(uris: List<Uri>) {
        setContent {
            TetherTheme {
                val snapshot by Tether.state.collectAsStateWithLifecycle()
                DeviceChooser(
                    title = resources.getQuantityString(R.plurals.share_title, uris.size, uris.size),
                    peers = snapshot?.connected.orEmpty(),
                    onDismiss = { finish() },
                ) { send(it, uris) }
            }
        }
    }

    private fun send(peer: Peer, uris: List<Uri>) {
        lifecycleScope.launch {
            Files.send(this@ShareActivity, peer.fingerprint, uris)
            done(getString(R.string.sending_to, peer.name))
        }
    }

    private fun done(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    @Suppress("DEPRECATION")
    private fun streams(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_STREAM),
        )
        Intent.ACTION_SEND_MULTIPLE ->
            (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
        else -> emptyList()
    }
}
