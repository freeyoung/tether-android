package io.github.freeyoung.tether

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.util.Log
import android.widget.Toast

// SendClipboardActivity sends the clipboard to the paired devices and
// closes. It has no window content: it exists because Android lets an app
// read the clipboard only while it has the focus. The tile and the
// notification start it, and ClipboardWatcher when the user copies.
class SendClipboardActivity : Activity() {
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 1 pixel at the corner: enough to take the focus, too small to see.
        window.setLayout(1, 1)
        window.setGravity(Gravity.TOP or Gravity.START)
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
    }

    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < 34) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        if (intent.getBooleanExtra(AUTOMATIC, false)) {
            // Quietly: the user did not ask for it.
            val result = PhoneClipboard.send(this, force = false)
            Log.i(TAG, "automatic send: $result")
            finish()
            return
        }
        Toast.makeText(this, PhoneClipboard.message(this, PhoneClipboard.send(this, force = true)), Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        const val AUTOMATIC = "automatic"
    }
}
