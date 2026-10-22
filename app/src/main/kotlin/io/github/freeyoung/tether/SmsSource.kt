package io.github.freeyoung.tether

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony
import kotlinx.serialization.Serializable

// A text message, as the engine sends it (core.SMS).
@Serializable
data class Sms(
    val id: Long,
    val thread: Long,
    val address: String,
    val name: String = "",
    val date: Long,
    val sent: Boolean = false,
    val body: String,
)

// SmsSource reads the phone's text messages for computers that keep them:
// Android's SMS provider, received and sent ones. Chats in Google Messages
// (RCS) are not in it.
object SmsSource {
    // As many as the engine sends in one batch (core.MessageBatchSize).
    private const val BATCH = 500

    fun hasAccess(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    // Whether Tether gives messages: the user turned it on and allowed it.
    fun enabled(context: Context) = Prefs.syncMessages(context) && hasAccess(context)

    // The messages from the date since on, the oldest first, and whether
    // there are more.
    fun read(context: Context, since: Long): Pair<List<Sms>, Boolean> {
        val names = ContactNames(context)
        val list = ArrayList<Sms>()
        var more = false
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.BODY),
            "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.TYPE} IN (${Telephony.Sms.MESSAGE_TYPE_INBOX}, ${Telephony.Sms.MESSAGE_TYPE_SENT})",
            arrayOf(since.toString()),
            "${Telephony.Sms.DATE} ASC, ${Telephony.Sms._ID} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                if (list.size == BATCH) {
                    more = true
                    break
                }
                val address = c.getString(2).orEmpty()
                list += Sms(
                    id = c.getLong(0), thread = c.getLong(1), address = address, name = names.of(address),
                    date = c.getLong(3), sent = c.getInt(4) == Telephony.Sms.MESSAGE_TYPE_SENT, body = c.getString(5).orEmpty(),
                )
            }
        }
        return list to more
    }

    // Calls onChange, at most once in 2 seconds, when the messages change.
    fun observe(context: Context, onChange: () -> Unit): ContentObserver {
        val handler = Handler(Looper.getMainLooper())
        val tell = Runnable(onChange)
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                handler.removeCallbacks(tell)
                handler.postDelayed(tell, 2000)
            }
        }
        context.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        return observer
    }

    // The names of contacts by number, while Tether may read the contacts.
    private class ContactNames(private val context: Context) {
        private val allowed = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        private val cache = HashMap<String, String>()

        fun of(address: String): String {
            if (!allowed || address.isBlank()) return ""
            return cache.getOrPut(address) {
                try {
                    context.contentResolver.query(
                        Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)),
                        arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null,
                    )?.use { c -> if (c.moveToFirst()) c.getString(0).orEmpty() else "" } ?: ""
                } catch (_: Exception) {
                    ""
                }
            }
        }
    }
}
