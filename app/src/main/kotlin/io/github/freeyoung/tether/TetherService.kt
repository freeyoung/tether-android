package io.github.freeyoung.tether

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File

// TetherService runs the engine while Tether is on, as a foreground service:
// it keeps the sessions with the paired devices when no screen is open.
@OptIn(FlowPreview::class)
class TetherService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var discovery: Discovery
    private var network: Network? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var smsObserver: ContentObserver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(
            Notifications.RUNNING_ID, Notifications.running(this, null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        discovery = Discovery(this) { Tether.discovered(it) }
        Files.clearStaged(this)
        val error = Tether.start(
            Tether.Config(
                supportDir = File(filesDir, "engine").path,
                downloadsDir = File(filesDir, "received").path,
                clipboardDir = File(cacheDir, "clipboard").path,
                deviceName = deviceName(this),
                port = PORT,
                provideMessages = SmsSource.enabled(this),
            ),
        ) { event -> main.post { handle(event) } }
        if (error != null) {
            Log.e(TAG, "the engine did not start: $error")
            Notifications.problem(this, getString(R.string.not_started), error)
            stopSelf()
            return
        }
        watchNetwork()
        if (SmsSource.enabled(this)) smsObserver = SmsSource.observe(this) { Tether.messagesChanged() }
        // The notification names the connected devices.
        Tether.state
            .map { it?.connected?.map { p -> p.name } }
            .distinctUntilChanged()
            .debounce(300)
            .onEach {
                getSystemService(NotificationManager::class.java)
                    .notify(Notifications.RUNNING_ID, Notifications.running(this, Tether.state.value))
            }
            .launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        smsObserver?.let { contentResolver.unregisterContentObserver(it) }
        networkCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        discovery.stop()
        ClipboardWatcher.stop(this)
        Tether.stop()
        super.onDestroy()
    }

    private fun handle(event: Event) {
        when (event.event) {
            "clipboard" -> {
                if (Prefs.writeClipboard(this)) PhoneClipboard.write(this, event)
                // A clip comes from a connected peer: when only one is connected, it is that one.
                val source = Tether.state.value?.connected?.singleOrNull()?.name.orEmpty()
                History.clip(event.text, event.png, source)
            }
            "discovery_start" -> discovery.start(event.instance, event.port, event.fingerprint)
            "discovery_stop" -> discovery.stop()
            // While the app is on screen, its pairing sheet asks; MainActivity.onStop posts
            // the notification if the request is still open then.
            "pairing_requested" -> if (Tether.visibleScreens == 0) Notifications.pairingRequested(this, event)
            "pairing_resolved" -> Notifications.pairingResolved(this, event.fingerprint)
            "open_url" -> openUrl(event.url)
            "file_received" -> scope.launch(Dispatchers.IO) {
                val name = File(event.path).name
                val size = File(event.path).length()
                try {
                    val uri = Files.moveToDownloads(this@TetherService, event.path)
                    History.file(name, event.peer, incoming = true, ok = uri != null, reason = "", size = size, uri = uri)
                    if (uri != null) Notifications.fileReceived(this@TetherService, name, event.peer, uri)
                } catch (e: Exception) {
                    Log.e(TAG, "could not move a received file to Downloads", e)
                    Notifications.problem(this@TetherService, getString(R.string.file_not_saved, name), e.message.orEmpty())
                }
            }
            "file_sent" -> {
                val size = Files.sent(this, event.name)
                History.file(event.name, event.peer, incoming = false, ok = event.ok, reason = event.reason, size = size, uri = null)
                if (!event.ok) Notifications.fileNotSent(this, event.name, event.peer, event.reason)
            }
            // A computer asks for text messages. Without access, an empty batch
            // ends its sync.
            "sms_requested" -> scope.launch(Dispatchers.IO) {
                val (messages, more) = try {
                    SmsSource.read(this@TetherService, event.since)
                } catch (e: Exception) {
                    Log.e(TAG, "could not read the text messages", e)
                    emptyList<Sms>() to false
                }
                Tether.sendMessages(event.fingerprint, messages, more)
            }
            "failed" -> Notifications.problem(this, getString(R.string.app_name), event.message)
            "peer_outdated" -> Notifications.problem(this, getString(R.string.peer_outdated, event.name), getString(R.string.peer_outdated_text, event.version))
        }
    }

    // A link from the iPhone opens at once while the app is on screen; else
    // Android allows no new activity, so a notification offers it.
    private fun openUrl(url: String) {
        if (Tether.visibleScreens > 0) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                Log.w(TAG, "could not open a link", e)
            }
        }
        Notifications.link(this, url)
    }

    // After a change of network, the sessions with computers may be dead
    // without either side knowing: the engine dials them again.
    private fun watchNetwork() {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(available: Network) {
                main.post {
                    val first = network == null
                    if (available == network) return@post
                    network = available
                    if (!first) {
                        Tether.networkChanged()
                        discovery.restart()
                    }
                }
            }
        }
        networkCallback = callback
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback)
    }

    companion object {
        const val ACTION_STOP = "io.github.freeyoung.tether.STOP"
        const val PORT = 5134

        fun start(context: Context) {
            Prefs.setEnabled(context, true)
            context.startForegroundService(Intent(context, TetherService::class.java))
        }

        fun stop(context: Context) {
            Prefs.setEnabled(context, false)
            context.stopService(Intent(context, TetherService::class.java))
        }
    }
}
