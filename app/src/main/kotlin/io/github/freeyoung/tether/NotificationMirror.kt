package io.github.freeyoung.tether

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

// Sends the phone's notifications to the computers that show them: Tether
// Desktop on macOS. The user allows it under Notification access in Android's
// settings. Silent and ongoing notifications, group summaries and the apps
// that the user turned off stay on the phone.
class NotificationMirror : NotificationListenerService() {
    // The content that went out for each key: an update with the same
    // content sends nothing, and so does the removal of one that never went out.
    private val sent = HashMap<String, Int>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification
        if (!Prefs.mirrorNotifications(this) || sbn.packageName == packageName || !sbn.isClearable ||
            n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || silent(sbn)
        ) return
        val appName = appName(sbn.packageName)
        Prefs.addSeenApp(this, sbn.packageName, appName)
        if (sbn.packageName in Prefs.mutedApps(this)) return
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: n.extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val content = listOf(title, text).hashCode()
        if (sent[sbn.key] == content) return
        // null: the engine is not running. The next update tries again.
        if (Tether.postNotification(sbn.key, sbn.packageName, appName, title, text, sbn.postTime) != null) {
            sent[sbn.key] = content
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sent.remove(sbn.key) != null) Tether.removeNotification(sbn.key)
    }

    // Below the default importance, a notification makes no sound and shows no banner.
    private fun silent(sbn: StatusBarNotification): Boolean {
        val ranking = Ranking()
        return currentRanking?.getRanking(sbn.key, ranking) == true && ranking.importance < NotificationManager.IMPORTANCE_DEFAULT
    }

    private fun appName(app: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(app, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        app
    }

    companion object {
        fun hasAccess(context: Context) = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

        fun component(context: Context) = ComponentName(context, NotificationMirror::class.java)
    }
}
