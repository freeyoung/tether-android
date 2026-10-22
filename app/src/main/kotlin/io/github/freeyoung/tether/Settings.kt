package io.github.freeyoung.tether

import android.content.Context
import android.os.Build
import android.provider.Settings

object Prefs {
    private fun prefs(context: Context) = context.getSharedPreferences("tether", Context.MODE_PRIVATE)

    // Whether Tether runs: the user turns it off with Stop.
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", true)

    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean("enabled", on).apply()

    // Whether clips from peers go on the phone's clipboard at once. Off, they
    // only go to the history, as in the iPhone app.
    fun writeClipboard(context: Context) = prefs(context).getBoolean("writeClipboard", true)

    fun setWriteClipboard(context: Context, on: Boolean) = prefs(context).edit().putBoolean("writeClipboard", on).apply()

    // Whether ClipboardWatcher sends what the user copies in other apps.
    fun autoSend(context: Context) = prefs(context).getBoolean("autoSend", false)

    fun setAutoSend(context: Context, on: Boolean) = prefs(context).edit().putBoolean("autoSend", on).apply()

    // Whether NotificationMirror sends the phone's notifications to computers.
    fun mirrorNotifications(context: Context) = prefs(context).getBoolean("mirrorNotifications", true)

    fun setMirrorNotifications(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean("mirrorNotifications", on).apply()

    // The apps whose notifications stay on the phone. Those of all other apps go out.
    fun mutedApps(context: Context): Set<String> = prefs(context).getStringSet("mutedApps", null).orEmpty().toSet()

    fun setAppMuted(context: Context, app: String, muted: Boolean) {
        val apps = if (muted) mutedApps(context) + app else mutedApps(context) - app
        prefs(context).edit().putStringSet("mutedApps", apps).apply()
    }

    // The apps that posted a notification, with their names, for the list in Settings.
    private fun notificationApps(context: Context) = context.getSharedPreferences("notificationApps", Context.MODE_PRIVATE)

    fun seenApps(context: Context): Map<String, String> =
        notificationApps(context).all.mapNotNull { (app, name) -> (name as? String)?.let { app to it } }.toMap()

    fun addSeenApp(context: Context, app: String, name: String) {
        val apps = notificationApps(context)
        if (apps.getString(app, null) != name) apps.edit().putString(app, name).apply()
    }

    // Whether computers that keep text messages get this phone's (SmsSource).
    fun syncMessages(context: Context) = prefs(context).getBoolean("syncMessages", false)

    fun setSyncMessages(context: Context, on: Boolean) = prefs(context).edit().putBoolean("syncMessages", on).apply()

    // The name peers show for this phone, when the user set one in the app.
    fun deviceName(context: Context): String? = prefs(context).getString("deviceName", null)

    fun setDeviceName(context: Context, name: String?) = prefs(context).edit().putString("deviceName", name).apply()
}

// The name peers show for this phone: the one set in the app, else the one
// the user gave the phone in Settings.
fun deviceName(context: Context): String = Prefs.deviceName(context)?.takeIf { it.isNotBlank() }
    ?: systemDeviceName(context)

fun systemDeviceName(context: Context): String =
    Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
        ?: Build.MODEL
