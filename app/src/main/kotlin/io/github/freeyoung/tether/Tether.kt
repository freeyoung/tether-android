package io.github.freeyoung.tether

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

const val TAG = "Tether"

// Tether is the engine of the process: TetherService starts and stops it, and
// the screens read its state and call it.
object Tether {
    @Serializable
    data class Config(
        val supportDir: String,
        val downloadsDir: String,
        val clipboardDir: String,
        val deviceName: String,
        val port: Int,
        // Answer sms_requested: see SmsSource.
        val provideMessages: Boolean = false,
    )

    val json = Json { ignoreUnknownKeys = true }

    private val mutableState = MutableStateFlow<Snapshot?>(null)

    // Null while the engine is stopped.
    val state: StateFlow<Snapshot?> = mutableState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<Event>(extraBufferCapacity = 64)

    // Every event but state, for the screens; TetherService gets them first.
    val events: SharedFlow<Event> = mutableEvents.asSharedFlow()

    @Volatile private var handle = 0L

    val running get() = handle != 0L

    // Screens of the app that the user sees. Links open at once while one is visible.
    @Volatile var visibleScreens = 0

    // Starts the engine and a thread that hands each event to onEvent. Returns
    // an error message, or null.
    @Synchronized
    fun start(config: Config, onEvent: (Event) -> Unit): String? {
        if (handle != 0L) return null
        val result = json.parseToJsonElement(Native.start(json.encodeToString(Config.serializer(), config).toByteArray()).decodeToString()) as JsonObject
        result["error"]?.let { return it.jsonPrimitive.content }
        val h = result["handle"]!!.jsonPrimitive.content.toLong()
        handle = h
        Thread({
            while (true) {
                val line = Native.nextEvent(h) ?: break
                val event = try {
                    json.decodeFromString(Event.serializer(), line.decodeToString())
                } catch (e: Exception) {
                    Log.w(TAG, "bad event", e)
                    continue
                }
                if (event.event == "state") {
                    if (handle == h) mutableState.value = event.snapshot
                    continue
                }
                try {
                    onEvent(event)
                } catch (e: Exception) {
                    Log.e(TAG, "event ${event.event} failed", e)
                }
                mutableEvents.tryEmit(event)
            }
        }, "tether-events").start()
        return null
    }

    @Synchronized
    fun stop() {
        val h = handle
        if (h == 0L) return
        handle = 0L
        mutableState.value = null
        // Closes the sessions; waits up to about 1 second.
        Native.stop(h)
    }

    // Runs an operation of go/bridge.go. Returns the result, or null when the
    // engine is stopped or the operation failed.
    fun call(op: String, arg: JsonObject? = null): JsonObject? {
        val h = handle
        if (h == 0L) return null
        val out = Native.call(h, op.toByteArray(), arg?.toString()?.toByteArray())
        val result = json.parseToJsonElement(out.decodeToString()) as JsonObject
        result["error"]?.let {
            Log.w(TAG, "$op: ${it.jsonPrimitive.content}")
            return null
        }
        return result
    }

    fun approve(fingerprint: String) = call("approve", buildJsonObject { put("fingerprint", fingerprint) })

    fun reject(fingerprint: String) = call("reject", buildJsonObject { put("fingerprint", fingerprint) })

    fun forget(fingerprint: String) = call("forget", buildJsonObject { put("fingerprint", fingerprint) })

    fun pairWith(host: String, port: Int) = call("pair_host", buildJsonObject {
        put("host", host)
        put("port", port)
    })

    fun pairWithDiscovered(fingerprint: String) = call("pair_discovered", buildJsonObject { put("fingerprint", fingerprint) })

    fun cancelPairing() = call("cancel_pairing")

    fun networkChanged() = call("network_changed")

    fun sendFiles(fingerprint: String, paths: List<String>) = call("send_files", buildJsonObject {
        put("fingerprint", fingerprint)
        putJsonArray("paths") { paths.forEach { add(JsonPrimitive(it)) } }
    })

    // The user copied this on the phone: send it to the paired devices.
    fun copyText(text: String) = call("copy_text", buildJsonObject { put("text", text) })

    fun copyPng(path: String) = call("copy_png", buildJsonObject { put("path", path) })

    // A notification on this phone, for the computers that show them.
    fun postNotification(id: String, app: String, appName: String, title: String, text: String, time: Long) =
        call("post_notification", buildJsonObject {
            put("notification", buildJsonObject {
                put("id", id)
                put("app", app)
                put("appName", appName)
                put("title", title)
                put("text", text)
                put("time", time)
            })
        })

    fun removeNotification(id: String) = call("remove_notification", buildJsonObject { put("id", id) })

    // One batch of text messages for the computer that asked (sms_requested).
    fun sendMessages(fingerprint: String, messages: List<Sms>, more: Boolean) = call("sms_batch", buildJsonObject {
        put("fingerprint", fingerprint)
        put("messages", json.encodeToJsonElement(ListSerializer(Sms.serializer()), messages))
        put("more", more)
    })

    // The phone has new text messages: the computers ask for them.
    fun messagesChanged() = call("sms_changed")

    fun discovered(services: List<Service>) = call("discovered", buildJsonObject {
        put("services", json.encodeToJsonElement(ListSerializer(Service.serializer()), services))
    })

    fun engineVersion(): String? = call("version")?.get("version")?.jsonPrimitive?.content
}
