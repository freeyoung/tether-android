package io.github.freeyoung.tether.ui

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.freeyoung.tether.Peer
import io.github.freeyoung.tether.PhoneClipboard
import io.github.freeyoung.tether.R
import io.github.freeyoung.tether.Snapshot
import io.github.freeyoung.tether.Tether
import io.github.freeyoung.tether.TetherService

@Composable
fun DashboardScreen(s: Snapshot?, actions: AppActions) {
    var manual by remember { mutableStateOf(false) }
    val enabled = actions.enabled.value
    Page(
        title = stringResource(R.string.launcher_name),
        actions = {
            if (enabled && s != null) {
                ToolbarPill {
                    ToolbarButton(R.drawable.sym_lan, stringResource(R.string.enter_address)) { manual = true }
                }
            }
        },
    ) {
        StatusCard(s, actions)
        if (!enabled || s == null) return@Page

        if (s.connected.isNotEmpty()) {
            Section(stringResource(R.string.quick_actions)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionTile(stringResource(R.string.send_clipboard), R.drawable.sym_content_paste_fill1, Tint.colors.teal, Modifier.weight(1f)) {
                        sendClipboard(actions)
                    }
                    ActionTile(stringResource(R.string.send_files), R.drawable.sym_upload_file_fill1, Tint.colors.indigo, Modifier.weight(1f)) {
                        actions.pickFiles(null)
                    }
                }
            }
        }

        BatteryNotice()

        if (s.peers.isNotEmpty()) {
            Section(stringResource(R.string.my_devices)) {
                s.peers.forEach { peer -> PeerPanel(peer) { actions.pickFiles(peer) } }
            }
        }

        // No spinner: discovery never ends, and an endless animation costs
        // battery. The empty state says that it searches.
        Section(stringResource(R.string.discovered_devices)) {
            if (s.unpaired.isEmpty()) {
                EmptyState(R.drawable.sym_radar, stringResource(R.string.searching_title), stringResource(R.string.searching_text))
            }
            s.unpaired.forEach { service ->
                Panel(onClick = { Tether.pairWithDiscovered(service.fingerprint) }) {
                    PanelRow(
                        icon = R.drawable.sym_computer,
                        iconTint = Tint.colors.teal,
                        title = service.name,
                        subtitleContent = {
                            Text(shortColonFingerprint(service.fingerprint), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Tint.colors.secondary)
                        },
                        trailing = { Chevron() },
                    )
                }
            }
        }
    }
    if (manual) ManualConnectSheet { manual = false }
}

// The big card at the top: a symbol in a glowing circle, what the phone is
// connected to, and the fingerprint while nothing is.
@Composable
private fun StatusCard(s: Snapshot?, actions: AppActions) {
    val c = Tint.colors
    val status = statusOf(s, actions.enabled.value)
    val (icon, color) = when (status) {
        Status.Connected -> R.drawable.sym_link to c.green
        Status.Pairing, Status.Searching -> R.drawable.sym_sync to c.orange
        Status.Disconnected -> R.drawable.sym_add_link to c.teal
        Status.Off -> R.drawable.sym_power_settings_new to c.secondary
    }
    Panel(radius = 20.dp) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            GradientBadge(icon, color, 80.dp, 34.dp, glow = true)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val title = when (status) {
                    Status.Connected -> s!!.connected.joinToString(", ") { it.name }
                    Status.Off -> stringResource(R.string.off_title)
                    else -> stringResource(R.string.no_connection)
                }
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = c.label, textAlign = TextAlign.Center)
                StatusView(status)
                if (s != null && !s.listening) {
                    Text(stringResource(R.string.not_listening, TetherService.PORT), fontSize = 13.sp, color = c.red)
                }
            }
            if (status == Status.Off) {
                Text(stringResource(R.string.off_text), fontSize = 13.sp, color = c.secondary, textAlign = TextAlign.Center)
                ProminentButton(stringResource(R.string.turn_on)) { actions.setEnabled(true) }
            } else if (status != Status.Connected && s != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.your_fingerprint).uppercase(), fontSize = 11.sp, color = c.secondary)
                    Text(colonFingerprint(s.fingerprint), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = c.secondary, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun ActionTile(title: String, icon: Int, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Tint.colors.card).clickable(onClick = onClick).padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Sym(icon, color, 28.dp)
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Tint.colors.label)
    }
}

@Composable
private fun PeerPanel(peer: Peer, onSendFiles: () -> Unit) {
    val c = Tint.colors
    Panel(onClick = if (peer.connected) onSendFiles else null) {
        PanelRow(
            icon = iconOf(peer),
            iconTint = if (peer.connected) c.teal else c.secondary,
            title = peer.name,
            subtitleContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (peer.connected) c.green else c.tertiary, pulsing = false, size = 7.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(if (peer.connected) R.string.connected else R.string.not_connected), fontSize = 12.sp, color = c.secondary)
                }
            },
            trailing = {
                Sym(R.drawable.sym_verified_user_fill1, c.green, 18.dp)
                if (peer.connected) {
                    Spacer(Modifier.width(10.dp))
                    Chevron()
                }
            },
        )
    }
}

fun sendClipboard(actions: AppActions) {
    actions.show(PhoneClipboard.message(actions.context, PhoneClipboard.send(actions.context, force = true)))
}

// Some phones stop an app's service in the background to save battery.
@Composable
private fun BatteryNotice() {
    val context = LocalContext.current
    if (rememberBatteryExempt()) return
    Panel(onClick = { requestBatteryExemption(context) }) {
        PanelRow(
            icon = null,
            iconTint = Tint.colors.orange,
            title = stringResource(R.string.battery_title),
            subtitle = stringResource(R.string.battery_text),
            leading = { GradientBadge(R.drawable.sym_bolt, Tint.colors.orange, 40.dp, 22.dp, RoundedCornerShape(10.dp)) },
            trailing = { Chevron() },
        )
    }
}

// Whether Android lets Tether run in the background without limits. The user
// changes it in Android's settings, so it is read again on each resume.
@Composable
fun rememberBatteryExempt(): Boolean {
    val context = LocalContext.current
    val power = context.getSystemService(PowerManager::class.java)
    var exempt by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { exempt = power.isIgnoringBatteryOptimizations(context.packageName) }
    return exempt
}

@Suppress("BatteryLife")
fun requestBatteryExemption(context: Context) {
    context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()))
}

// The Manual Connection sheet: pair with a computer by its address.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualConnectSheet(onDismiss: () -> Unit) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(TetherService.PORT.toString()) }
    val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val c = Tint.colors
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.background) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.manual_connection), Modifier.fillMaxWidth(), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.label, textAlign = TextAlign.Center)
            ListSection(stringResource(R.string.host), footer = stringResource(R.string.enter_address_text)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        host, { host = it.trim() },
                        Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.host_or_ip)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    )
                    OutlinedTextField(
                        port, { port = it.filter(Char::isDigit) },
                        Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.port)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            }
            val ready = host.isNotEmpty() && portNumber != null
            ProminentButton(stringResource(R.string.connect), if (ready) c.teal else c.tertiary) {
                if (ready) {
                    Tether.pairWith(host, portNumber)
                    onDismiss()
                }
            }
        }
    }
}
