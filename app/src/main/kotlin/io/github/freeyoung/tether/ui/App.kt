package io.github.freeyoung.tether.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.freeyoung.tether.ClipboardWatcher
import io.github.freeyoung.tether.Files
import io.github.freeyoung.tether.Peer
import io.github.freeyoung.tether.Prefs
import io.github.freeyoung.tether.R
import io.github.freeyoung.tether.Snapshot
import io.github.freeyoung.tether.Tether
import io.github.freeyoung.tether.TetherService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class Tab(@StringRes val label: Int, @DrawableRes val icon: Int, @DrawableRes val selectedIcon: Int) {
    Dashboard(R.string.tab_dashboard, R.drawable.sym_language, R.drawable.sym_language_fill1),
    Clipboard(R.string.tab_clipboard, R.drawable.sym_content_paste, R.drawable.sym_content_paste_fill1),
    Files(R.string.tab_files, R.drawable.sym_folder, R.drawable.sym_folder_fill1),
    Settings(R.string.tab_settings, R.drawable.sym_settings, R.drawable.sym_settings_fill1),
}

// What the screens can ask of the app.
class AppActions(
    val context: Context,
    val scope: CoroutineScope,
    val snackbar: SnackbarHostState,
    val enabled: MutableState<Boolean>,
    val pickFiles: (Peer?) -> Unit,
    val pickPhotos: (Peer?) -> Unit,
) {
    fun setEnabled(on: Boolean) {
        enabled.value = on
        if (on) TetherService.start(context) else TetherService.stop(context)
        ClipboardWatcher.onAppVisible(context)
    }

    fun show(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }
}

// The connection state that ConnectionStatusView shows, for many peers.
enum class Status { Off, Connected, Pairing, Searching, Disconnected }

fun statusOf(s: Snapshot?, enabled: Boolean): Status = when {
    !enabled || s == null -> Status.Off
    s.pending.isNotEmpty() || s.outgoing.isNotEmpty() -> Status.Pairing
    s.connected.isNotEmpty() -> Status.Connected
    s.peers.isNotEmpty() -> Status.Searching
    else -> Status.Disconnected
}

@Composable
fun StatusView(status: Status) {
    val c = Tint.colors
    val (color, text) = when (status) {
        Status.Connected -> c.green to R.string.status_connected_short
        Status.Pairing -> c.orange to R.string.status_pairing
        Status.Searching -> c.orange to R.string.status_searching
        Status.Disconnected -> c.red to R.string.status_disconnected
        Status.Off -> c.tertiary to R.string.off_title
    }
    StatusLine(color, pulsing = status == Status.Pairing || status == Status.Searching, text = stringResource(text))
}

@Composable
fun TetherAppUi() {
    val context = LocalContext.current
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val enabled = remember { mutableStateOf(Prefs.enabled(context)) }
    var tab by rememberSaveable { mutableStateOf(Tab.Dashboard) }
    val snapshot by Tether.state.collectAsStateWithLifecycle()

    // Files go to the device the user tapped, or to the one connected device,
    // or the user picks one.
    var target by remember { mutableStateOf<Peer?>(null) }
    var choosing by remember { mutableStateOf<List<Uri>?>(null) }
    fun send(uris: List<Uri>, peer: Peer) {
        scope.launch {
            Files.send(context, peer.fingerprint, uris)
            snackbar.showSnackbar(res.getString(R.string.sending_to, peer.name))
        }
    }
    fun picked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val connected = Tether.state.value?.connected.orEmpty()
        val peer = target?.takeIf { t -> connected.any { it.fingerprint == t.fingerprint } } ?: connected.singleOrNull()
        if (peer != null) send(uris, peer) else if (connected.isNotEmpty()) choosing = uris
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked(it) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { picked(it) }
    val actions = remember {
        AppActions(
            context, scope, snackbar, enabled,
            pickFiles = { target = it; filePicker.launch(arrayOf("*/*")) },
            pickPhotos = {
                target = it
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            },
        )
    }

    LaunchedEffect(Unit) {
        Tether.events.collect { e ->
            val message = when (e.event) {
                "outbound" -> when (e.kind) {
                    "accepted" -> res.getString(R.string.pairing_accepted, e.peer)
                    "rejected" -> res.getString(R.string.pairing_rejected)
                    "failed" -> res.getString(R.string.pairing_failed, e.reason)
                    else -> null
                }
                "file_sent" -> if (e.ok) res.getString(R.string.file_sent, e.name, e.peer) else null
                else -> null
            }
            if (message != null) snackbar.showSnackbar(message)
        }
    }

    Box(Modifier.fillMaxSize().background(Tint.colors.background)) {
        val s = snapshot
        when (tab) {
            Tab.Dashboard -> DashboardScreen(s, actions)
            Tab.Clipboard -> ClipboardScreen(s, actions)
            Tab.Files -> FilesScreen(s, actions)
            Tab.Settings -> SettingsScreen(s, actions)
        }
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) {
            SnackbarHost(snackbar) {
                Snackbar(it, Modifier.padding(horizontal = 4.dp), shape = RoundedCornerShape(14.dp))
            }
            TabBar(tab) { tab = it }
        }
    }

    snapshot?.let { s -> PairingSheet(s) }
    choosing?.let { uris ->
        DeviceChooser(
            title = res.getQuantityString(R.plurals.share_title, uris.size, uris.size),
            peers = snapshot?.connected.orEmpty(),
            onDismiss = { choosing = null },
        ) { peer ->
            choosing = null
            send(uris, peer)
        }
    }
}

// The floating tab bar of iOS 26: a capsule, with the selected tab in a
// capsule of its own.
@Composable
private fun TabBar(selected: Tab, onSelect: (Tab) -> Unit) {
    val c = Tint.colors
    Row(
        Modifier
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .fillMaxWidth()
            .shadow(16.dp, CircleShape, ambientColor = c.label.copy(alpha = 0.2f), spotColor = c.label.copy(alpha = 0.2f))
            .clip(CircleShape)
            .background(c.card)
            .border(0.5.dp, c.separator, CircleShape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Tab.entries.forEach { tab ->
            val on = tab == selected
            Column(
                Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (on) c.fill else c.card)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(tab) }
                    .padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Sym(if (on) tab.selectedIcon else tab.icon, if (on) c.teal else c.label, 26.dp)
                Text(
                    stringResource(tab.label),
                    fontSize = 11.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (on) c.teal else c.label,
                )
            }
        }
    }
}

// A list of connected devices to send to.
@Composable
fun DeviceChooser(title: String, peers: List<Peer>, onDismiss: () -> Unit, onChoose: (Peer) -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(20.dp)).background(Tint.colors.card).padding(vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Tint.colors.label)
            if (peers.isEmpty()) {
                Text(stringResource(R.string.share_nobody), Modifier.padding(horizontal = 20.dp), color = Tint.colors.secondary)
            }
            peers.forEach { peer ->
                PanelRow(
                    icon = iconOf(peer),
                    iconTint = Tint.colors.teal,
                    title = peer.name,
                    onClick = { onChoose(peer) },
                    trailing = { Chevron() },
                )
            }
            Box(Modifier.fillMaxWidth().padding(end = 8.dp), contentAlignment = Alignment.CenterEnd) {
                PlainButton(stringResource(android.R.string.cancel), Tint.colors.teal, onDismiss)
            }
        }
    }
}
