package io.github.freeyoung.tether

import kotlinx.serialization.Serializable

// The JSON of go/bridge.go.

@Serializable
data class Peer(val fingerprint: String, val name: String, val kind: String, val connected: Boolean) {
    val isPhone get() = kind == "phone"
}

@Serializable
data class PairingRequest(val fingerprint: String, val name: String, val kind: String)

@Serializable
data class Service(
    val name: String,
    val fingerprint: String,
    val hosts: List<String>,
    val port: Int,
    val isComputer: Boolean,
)

@Serializable
data class Transfer(val filename: String, val peer: String, val fraction: Double, val queued: Int)

@Serializable
data class Snapshot(
    val fingerprint: String,
    val listening: Boolean,
    val peers: List<Peer> = emptyList(),
    val pending: List<PairingRequest> = emptyList(),
    val unpaired: List<Service> = emptyList(),
    val outgoing: List<String> = emptyList(),
    val sending: List<Transfer> = emptyList(),
) {
    val connected get() = peers.filter { it.connected }
}

// One event from the engine. Which fields are set depends on `event`.
@Serializable
data class Event(
    val event: String,
    val snapshot: Snapshot? = null,
    val fingerprint: String = "",
    val name: String = "",
    val kind: String = "",
    val peer: String = "",
    val reason: String = "",
    val message: String = "",
    val url: String = "",
    val path: String = "",
    val ok: Boolean = false,
    val text: String? = null,
    val png: String? = null,
    val instance: String = "",
    val port: Int = 0,
    val version: String = "",
    val since: Long = 0,
)
