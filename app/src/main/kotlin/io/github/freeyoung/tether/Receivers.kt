package io.github.freeyoung.tether

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

// Starts Tether after a reboot or an update, unless the user turned it off.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Prefs.enabled(context)) return
        try {
            TetherService.start(context)
        } catch (e: Exception) {
            Log.w(TAG, "could not start after ${intent.action}", e)
        }
    }
}

// Allow and Deny on a pairing notification.
class PairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val fingerprint = intent.getStringExtra(FINGERPRINT) ?: return
        when (intent.action) {
            ALLOW -> Tether.approve(fingerprint)
            DENY -> Tether.reject(fingerprint)
        }
        Notifications.pairingResolved(context, fingerprint)
    }

    companion object {
        const val ALLOW = "io.github.freeyoung.tether.ALLOW"
        const val DENY = "io.github.freeyoung.tether.DENY"
        const val FINGERPRINT = "fingerprint"
    }
}
