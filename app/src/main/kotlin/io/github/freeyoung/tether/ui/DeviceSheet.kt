package io.github.freeyoung.tether.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.freeyoung.tether.Peer
import io.github.freeyoung.tether.R
import io.github.freeyoung.tether.Tether

// The sheet of one paired device, like its submenu in Tether Desktop: what
// it is, and what the user can do with it.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSheet(peer: Peer, actions: AppActions, onDismiss: () -> Unit) {
    val c = Tint.colors
    var unpairing by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.background) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GradientBadge(iconOf(peer), if (peer.connected) c.teal else c.secondary, 64.dp, 32.dp, RoundedCornerShape(16.dp))
                Text(peer.name, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = c.label)
                StatusLine(
                    if (peer.connected) c.green else c.tertiary, pulsing = false,
                    stringResource(if (peer.connected) R.string.connected else R.string.not_connected),
                )
                Text(shortColonFingerprint(peer.fingerprint), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = c.secondary)
            }

            if (peer.connected) {
                ListSection(stringResource(R.string.send)) {
                    PanelRow(
                        R.drawable.sym_upload_file_fill1, c.teal, stringResource(R.string.send_files),
                        onClick = { onDismiss(); actions.pickFiles(peer) }, trailing = { Chevron() },
                    )
                    RowDivider()
                    PanelRow(
                        R.drawable.sym_image_fill1, c.indigo, stringResource(R.string.send_photos),
                        onClick = { onDismiss(); actions.pickPhotos(peer) }, trailing = { Chevron() },
                    )
                    RowDivider()
                    // The engine syncs the clipboard with every device at once.
                    PanelRow(
                        R.drawable.sym_content_paste_fill1, c.teal, stringResource(R.string.send_clipboard),
                        subtitle = stringResource(R.string.send_clipboard_all),
                        onClick = { onDismiss(); sendClipboard(actions) }, trailing = { Chevron() },
                    )
                }
            } else {
                Text(stringResource(R.string.device_offline_text), Modifier.padding(horizontal = 16.dp), fontSize = 13.sp, color = c.secondary)
            }

            Panel(radius = 12.dp, onClick = { unpairing = true }) {
                Text(
                    stringResource(R.string.unpair),
                    Modifier.fillMaxWidth().padding(14.dp),
                    fontSize = 16.sp, color = c.red, textAlign = TextAlign.Center,
                )
            }
        }
    }
    if (unpairing) {
        UnpairDialog(peer) { done ->
            unpairing = false
            if (done) onDismiss()
        }
    }
}

// Asks before an unpair. onDone tells whether the device was unpaired.
@Composable
fun UnpairDialog(peer: Peer, onDone: (Boolean) -> Unit) {
    val c = Tint.colors
    AlertDialog(
        onDismissRequest = { onDone(false) },
        title = { Text(stringResource(R.string.unpair_title, peer.name)) },
        text = { Text(stringResource(R.string.unpair_text)) },
        confirmButton = {
            TextButton(onClick = { Tether.forget(peer.fingerprint); onDone(true) }) {
                Text(stringResource(R.string.unpair), color = c.red)
            }
        },
        dismissButton = { TextButton(onClick = { onDone(false) }) { Text(stringResource(android.R.string.cancel)) } },
        containerColor = c.card,
    )
}
