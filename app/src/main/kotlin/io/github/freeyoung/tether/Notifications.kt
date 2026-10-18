package io.github.freeyoung.tether

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

object Notifications {
    // The ongoing notification, without a badge: a launcher would count it as
    // unread. "running", the first channel, had a badge, and a channel keeps
    // that setting, so this is a new one.
    private const val RUNNING = "service"
    private const val PAIRING = "pairing"
    private const val FILES = "files"
    private const val LINKS = "links"
    private const val PROBLEMS = "problems"

    const val RUNNING_ID = 1

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel("running")
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(RUNNING, context.getString(R.string.channel_running), NotificationManager.IMPORTANCE_MIN)
                    .apply { setShowBadge(false) },
                NotificationChannel(PAIRING, context.getString(R.string.channel_pairing), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(FILES, context.getString(R.string.channel_files), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(LINKS, context.getString(R.string.channel_links), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(PROBLEMS, context.getString(R.string.channel_problems), NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    private fun builder(context: Context, channel: String) =
        Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_notification)

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    // The notification of the foreground service.
    fun running(context: Context, snapshot: Snapshot?): Notification {
        val connected = snapshot?.connected.orEmpty()
        val text = when {
            snapshot == null -> context.getString(R.string.status_starting)
            connected.isEmpty() -> context.getString(R.string.status_no_device)
            else -> context.getString(R.string.status_connected, connected.joinToString(", ") { it.name })
        }
        val sendClipboard = PendingIntent.getActivity(
            context, 1, Intent(context, SendClipboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            context, 2, Intent(context, TetherService::class.java).setAction(TetherService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return builder(context, RUNNING)
            .setContentTitle(text)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.send_clipboard), sendClipboard).build())
            .addAction(Notification.Action.Builder(null, context.getString(R.string.stop), stop).build())
            .build()
    }

    fun pairingRequested(context: Context, request: Event) {
        val id = request.fingerprint.hashCode()
        fun action(action: String, code: Int) = PendingIntent.getBroadcast(
            context, id * 2 + code,
            Intent(context, PairingReceiver::class.java).setAction(action).putExtra(PairingReceiver.FINGERPRINT, request.fingerprint),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val kind = context.getString(if (request.kind == "phone") R.string.kind_phone else R.string.kind_computer)
        post(
            context, id,
            builder(context, PAIRING)
                .setContentTitle(context.getString(R.string.pairing_title, request.name))
                .setContentText(context.getString(R.string.pairing_text, kind, shortFingerprint(request.fingerprint)))
                .setContentIntent(openApp(context))
                .setCategory(Notification.CATEGORY_STATUS)
                .addAction(Notification.Action.Builder(null, context.getString(R.string.allow), action(PairingReceiver.ALLOW, 0)).build())
                .addAction(Notification.Action.Builder(null, context.getString(R.string.deny), action(PairingReceiver.DENY, 1)).build())
                .build(),
        )
    }

    fun pairingResolved(context: Context, fingerprint: String) {
        context.getSystemService(NotificationManager::class.java).cancel(fingerprint.hashCode())
    }

    fun fileReceived(context: Context, name: String, peer: String, uri: Uri) {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, Files.mimeType(name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val open = PendingIntent.getActivity(context, uri.hashCode(), view, PendingIntent.FLAG_IMMUTABLE)
        post(
            context, uri.hashCode(),
            builder(context, FILES)
                .setContentTitle(name)
                .setContentText(context.getString(R.string.file_received, peer))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun fileNotSent(context: Context, name: String, peer: String, reason: String) {
        post(
            context, (name + peer).hashCode(),
            builder(context, FILES)
                .setContentTitle(context.getString(R.string.file_not_sent, name, peer))
                .setContentText(reason)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun link(context: Context, url: String) {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val open = PendingIntent.getActivity(context, url.hashCode(), view, PendingIntent.FLAG_IMMUTABLE)
        post(
            context, url.hashCode(),
            builder(context, LINKS)
                .setContentTitle(context.getString(R.string.link_title))
                .setContentText(url)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun problem(context: Context, title: String, text: String) {
        post(
            context, title.hashCode(),
            builder(context, PROBLEMS).setContentTitle(title).setContentText(text).setAutoCancel(true).build(),
        )
    }

    private fun post(context: Context, id: Int, notification: Notification) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }
}

// The first 16 hex digits of a fingerprint, in groups of 4.
fun shortFingerprint(fingerprint: String) = fingerprint.take(16).chunked(4).joinToString(" ")
