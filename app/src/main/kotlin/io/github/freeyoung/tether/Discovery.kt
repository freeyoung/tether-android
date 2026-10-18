package io.github.freeyoung.tether

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import java.net.Inet4Address
import java.util.concurrent.Executors

// Discovery publishes this phone as _tether._tcp, the way tetherd and Tether
// Desktop publish themselves, and finds the others with NsdManager. Each
// change of the list goes to onResults.
class Discovery(context: Context, private val onResults: (List<Service>) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()
    private val lock = Any()

    private var registration: NsdManager.RegistrationListener? = null
    private var browsing: NsdManager.DiscoveryListener? = null

    // By service name. A service is in the list once it resolves.
    private val found = mutableMapOf<String, Service>()
    private val watchers = mutableMapOf<String, NsdManager.ServiceInfoCallback>()

    // Before Android 14, NsdManager resolves 1 service at a time.
    private val toResolve = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun start(instance: String, port: Int, fingerprint: String) {
        synchronized(lock) {
            if (registration != null) return
            val info = NsdServiceInfo().apply {
                // A dot would split the instance name into labels.
                serviceName = instance.replace('.', '-')
                serviceType = SERVICE_TYPE
                setPort(port)
                // tetherd publishes exactly fp and v; the phone reads fp.
                setAttribute("fp", fingerprint)
                setAttribute("v", "1")
            }
            registration = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    Log.i(TAG, "advertised ${info.serviceName}")
                }
                override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {
                    Log.w(TAG, "DNS-SD registration failed: $error")
                }
                override fun onServiceUnregistered(info: NsdServiceInfo) {
                    Log.i(TAG, "stopped advertising")
                }
                override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
            }
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
            browse()
        }
    }

    fun stop() {
        synchronized(lock) {
            registration?.let { runCatching { nsd.unregisterService(it) } }
            registration = null
            stopBrowsing()
        }
    }

    // After a network change, browse again: the old results may be from
    // another network.
    fun restart() {
        synchronized(lock) {
            if (registration == null) return
            stopBrowsing()
            browse()
        }
        publish()
    }

    private fun browse() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, error: Int) {
                Log.w(TAG, "DNS-SD browsing failed: $error")
            }
            override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) = resolve(info)
            override fun onServiceLost(info: NsdServiceInfo) = lost(info.serviceName)
        }
        browsing = listener
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun stopBrowsing() {
        browsing?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        browsing = null
        if (Build.VERSION.SDK_INT >= 34) {
            watchers.values.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
        }
        watchers.clear()
        toResolve.clear()
        found.clear()
    }

    private fun resolve(info: NsdServiceInfo) {
        synchronized(lock) {
            if (browsing == null) return
            if (Build.VERSION.SDK_INT >= 34) {
                if (info.serviceName in watchers) return
                val callback = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceUpdated(updated: NsdServiceInfo) = update(info.serviceName, updated)
                    override fun onServiceLost() = lost(info.serviceName)
                    override fun onServiceInfoCallbackRegistrationFailed(error: Int) {
                        Log.w(TAG, "DNS-SD resolve failed: $error")
                    }
                    override fun onServiceInfoCallbackUnregistered() = Unit
                }
                watchers[info.serviceName] = callback
                nsd.registerServiceInfoCallback(info, executor, callback)
            } else {
                toResolve.addLast(info)
                resolveNext()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val info = toResolve.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                update(info.serviceName, resolved)
                next()
            }

            override fun onResolveFailed(failed: NsdServiceInfo, error: Int) {
                Log.w(TAG, "DNS-SD resolve failed: $error")
                next()
            }

            fun next() = synchronized(lock) {
                resolving = false
                resolveNext()
            }
        })
    }

    @Suppress("DEPRECATION")
    private fun update(name: String, info: NsdServiceInfo) {
        val addresses = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        // IPv4 only, as the Browser of tether-core-go.
        val hosts = addresses.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }.distinct().sorted()
        val fingerprint = info.attributes["fp"]?.decodeToString().orEmpty()
        synchronized(lock) {
            if (browsing == null) return
            if (fingerprint.isEmpty() || hosts.isEmpty() || info.port == 0) {
                found.remove(name)
            } else {
                found[name] = Service(name, fingerprint, hosts, info.port, isComputer = "v" in info.attributes)
            }
        }
        publish()
    }

    private fun lost(name: String) {
        synchronized(lock) {
            found.remove(name)
            if (Build.VERSION.SDK_INT >= 34) {
                watchers.remove(name)?.let { runCatching { nsd.unregisterServiceInfoCallback(it) } }
            }
        }
        publish()
    }

    private fun publish() {
        val list = synchronized(lock) { found.values.sortedBy { it.fingerprint } }
        onResults(list)
    }

    companion object {
        const val SERVICE_TYPE = "_tether._tcp"
    }
}
