package io.github.freeyoung.tether.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.net.toUri
import io.github.freeyoung.tether.ClipboardWatcher
import io.github.freeyoung.tether.BuildConfig
import io.github.freeyoung.tether.NotificationMirror
import io.github.freeyoung.tether.Peer
import io.github.freeyoung.tether.Prefs
import io.github.freeyoung.tether.R
import io.github.freeyoung.tether.Snapshot
import io.github.freeyoung.tether.Tether
import io.github.freeyoung.tether.TetherService
import io.github.freeyoung.tether.deviceName
import io.github.freeyoung.tether.systemDeviceName
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(s: Snapshot?, actions: AppActions) {
    val c = Tint.colors
    val context = LocalContext.current
    val res = LocalResources.current
    var unpairing by remember { mutableStateOf<Peer?>(null) }
    val engineVersion = remember(s != null) { Tether.engineVersion() }
    Page(title = stringResource(R.string.tab_settings)) {
        ListSection(stringResource(R.string.this_device)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                GradientBadge(R.drawable.sym_smartphone, c.teal, 40.dp, 22.dp, RoundedCornerShape(10.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    DeviceNameField(actions)
                    Text(stringResource(R.string.this_device_text), fontSize = 12.sp, color = c.secondary)
                }
            }
            if (s != null) {
                RowDivider(16.dp)
                Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.tls_fingerprint), fontSize = 12.sp, color = c.secondary)
                        Text(colonFingerprint(s.fingerprint), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = c.label)
                    }
                    ToolbarButton(R.drawable.sym_content_copy, stringResource(R.string.copy_fingerprint)) {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("fingerprint", s.fingerprint))
                        actions.show(res.getString(R.string.fingerprint_copied))
                    }
                }
            }
        }

        val peers = s?.peers.orEmpty()
        ListSection(stringResource(R.string.paired_devices), footer = if (peers.isNotEmpty()) stringResource(R.string.swipe_to_unpair) else null) {
            if (peers.isEmpty()) {
                EmptyState(R.drawable.sym_shield_lock, stringResource(R.string.no_paired_devices), iconSize = 24.dp, top = 20.dp)
            }
            peers.forEachIndexed { i, peer ->
                if (i > 0) RowDivider()
                UnpairableRow(peer) { unpairing = peer }
            }
        }

        ListSection(stringResource(R.string.sync), footer = stringResource(R.string.write_clipboard_footer)) {
            var write by remember { mutableStateOf(Prefs.writeClipboard(context)) }
            ToggleRow(R.drawable.sym_content_paste, stringResource(R.string.write_clipboard), stringResource(R.string.write_clipboard_text), write) {
                write = it
                Prefs.setWriteClipboard(context, it)
            }
        }

        NotificationsSection()

        ListSection(stringResource(R.string.background), footer = stringResource(R.string.send_clipboard_help)) {
            ToggleRow(
                R.drawable.sym_power_settings_new, stringResource(R.string.run_tether), stringResource(R.string.run_tether_text),
                actions.enabled.value,
            ) { actions.setEnabled(it) }
            RowDivider()
            BatteryRow()
            RowDivider()
            PanelRow(
                icon = R.drawable.sym_dashboard_customize_fill1, iconTint = c.teal,
                title = stringResource(R.string.tile_label), subtitle = stringResource(R.string.add_tile_text),
                onClick = { addTile(actions) },
                trailing = { Chevron() },
            )
        }

        AutoSendSection(actions)

        ListSection(stringResource(R.string.connection)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.status), Modifier.weight(1f), fontSize = 16.sp, color = c.label)
                StatusView(statusOf(s, actions.enabled.value))
            }
            RowDivider(16.dp)
            ValueRow(
                stringResource(R.string.listening_label),
                when {
                    s == null -> "—"
                    s.listening -> TetherService.PORT.toString()
                    else -> stringResource(R.string.not_listening, TetherService.PORT)
                },
                monospace = true,
            )
        }

        ListSection(stringResource(R.string.about), footer = stringResource(R.string.unofficial)) {
            ValueRow(stringResource(R.string.version_label), BuildConfig.VERSION_NAME)
            RowDivider(16.dp)
            ValueRow(stringResource(R.string.engine_label), engineVersion ?: "—")
            RowDivider(16.dp)
            ValueRow(stringResource(R.string.protocol_label), "TCP + mTLS")
            RowDivider(16.dp)
            ValueRow(stringResource(R.string.service_type_label), "_tether._tcp", monospace = true)
        }
    }

    unpairing?.let { peer -> UnpairDialog(peer) { unpairing = null } }
}

// The name peers show. The name in Android's settings, unless the user
// gives another one here.
@Composable
private fun DeviceNameField(actions: AppActions) {
    val c = Tint.colors
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    var name by remember { mutableStateOf(deviceName(context)) }
    fun save() {
        val value = name.trim().takeIf { it.isNotEmpty() && it != systemDeviceName(context) }
        if (value == null) name = systemDeviceName(context)
        if (value == Prefs.deviceName(context)) return
        Prefs.setDeviceName(context, value)
        // The name goes out in DNS-SD and in pairing requests: start again with it.
        if (actions.enabled.value) {
            TetherService.stop(context)
            TetherService.start(context)
        }
    }
    Box {
        BasicTextField(
            name, { name = it },
            Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) save() },
            singleLine = true,
            textStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = c.label),
            cursorBrush = SolidColor(c.teal),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        )
    }
}

@Composable
private fun UnpairableRow(peer: Peer, onUnpair: () -> Unit) {
    val c = Tint.colors
    val scope = rememberCoroutineScope()
    val state = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state,
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(c.red).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterEnd) {
                Sym(R.drawable.sym_delete, Color.White)
            }
        },
        enableDismissFromStartToEnd = false,
        onDismiss = {
            onUnpair()
            scope.launch { state.reset() }
        },
    ) {
        Box(Modifier.background(c.card)) {
            PanelRow(
                icon = iconOf(peer),
                iconTint = c.teal,
                title = peer.name,
                subtitleContent = {
                    Text(shortColonFingerprint(peer.fingerprint), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = c.secondary)
                },
            )
        }
    }
}

@Composable
private fun ToggleRow(icon: Int, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Tint.colors
    PanelRow(
        icon = icon, iconTint = c.teal, title = title, subtitle = subtitle,
        onClick = { onChange(!checked) },
        trailing = { TintSwitch(checked, onChange) },
    )
}

@Composable
fun TintSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Tint.colors
    Switch(
        checked, onChange,
        colors = SwitchDefaults.colors(
            checkedTrackColor = c.green, checkedThumbColor = Color.White, checkedBorderColor = c.green,
            uncheckedTrackColor = c.fill, uncheckedThumbColor = Color.White, uncheckedBorderColor = c.fill,
        ),
    )
}

// Sends the phone's notifications to computers (NotificationMirror), and the
// steps it needs.
@Composable
private fun NotificationsSection() {
    val c = Tint.colors
    val context = LocalContext.current
    val res = LocalResources.current
    var on by remember { mutableStateOf(Prefs.mirrorNotifications(context)) }
    var access by remember { mutableStateOf(NotificationMirror.hasAccess(context)) }
    var muted by remember { mutableStateOf(Prefs.mutedApps(context).size) }
    var apps by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { access = NotificationMirror.hasAccess(context) }
    ListSection(stringResource(R.string.notifications), footer = stringResource(R.string.mirror_notifications_footer)) {
        ToggleRow(R.drawable.sym_notifications, stringResource(R.string.mirror_notifications), stringResource(R.string.mirror_notifications_text), on) {
            on = it
            Prefs.setMirrorNotifications(context, it)
            if (it && !access) openNotificationAccess(context)
        }
        if (on) {
            RowDivider()
            PanelRow(
                icon = R.drawable.sym_verified_user_fill1, iconTint = if (access) c.green else c.orange,
                title = stringResource(R.string.notification_access),
                subtitle = stringResource(if (access) R.string.granted else R.string.notification_access_text),
                onClick = { openNotificationAccess(context) },
                trailing = { Chevron() },
            )
            RowDivider()
            PanelRow(
                icon = R.drawable.sym_apps, iconTint = c.teal,
                title = stringResource(R.string.notification_apps),
                subtitle = if (muted == 0) stringResource(R.string.notification_apps_all)
                else res.getQuantityString(R.plurals.notification_apps_off, muted, muted),
                onClick = { apps = true },
                trailing = { Chevron() },
            )
        }
    }
    if (apps) {
        NotificationAppsSheet {
            apps = false
            muted = Prefs.mutedApps(context).size
        }
    }
}

// The page of NotificationMirror in Android's settings, where the user allows
// it. Android 10 has only the list of all such apps.
private fun openNotificationAccess(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, NotificationMirror.component(context).flattenToString())
    } else {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun BatteryRow() {
    val c = Tint.colors
    val context = LocalContext.current
    val exempt = rememberBatteryExempt()
    PanelRow(
        icon = R.drawable.sym_bolt, iconTint = if (exempt) c.teal else c.orange,
        title = stringResource(R.string.battery_label),
        subtitle = stringResource(if (exempt) R.string.battery_unrestricted else R.string.battery_restricted),
        // Unrestricted already: the app's page in Android's settings, where the
        // user can change it back.
        onClick = {
            if (exempt) {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } else {
                requestBatteryExemption(context)
            }
        },
        trailing = { Chevron() },
    )
}

// The experimental option of ClipboardWatcher, and the steps it needs.
@Composable
private fun AutoSendSection(actions: AppActions) {
    val c = Tint.colors
    val context = LocalContext.current
    val res = LocalResources.current
    val state by ClipboardWatcher.state.collectAsStateWithLifecycle()
    var on by remember { mutableStateOf(Prefs.autoSend(context)) }
    var logs by remember { mutableStateOf(ClipboardWatcher.hasLogAccess(context)) }
    var overlay by remember { mutableStateOf(ClipboardWatcher.canOpenFromBackground(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        logs = ClipboardWatcher.hasLogAccess(context)
        overlay = ClipboardWatcher.canOpenFromBackground(context)
    }
    val status = when {
        !on -> R.string.auto_send_text
        state == ClipboardWatcher.State.Active -> R.string.auto_send_active
        state == ClipboardWatcher.State.NeedsPermission -> R.string.auto_send_needs_permission
        else -> R.string.auto_send_needs_approval
    }
    ListSection(stringResource(R.string.experimental), footer = stringResource(R.string.auto_send_footer)) {
        ToggleRow(R.drawable.sym_sync, stringResource(R.string.auto_send), stringResource(status), on) {
            on = it
            Prefs.setAutoSend(context, it)
            if (it) ClipboardWatcher.onAppVisible(context) else ClipboardWatcher.stop(context)
        }
        if (on) {
            RowDivider()
            val command = "adb shell pm grant ${context.packageName} android.permission.READ_LOGS && adb shell am force-stop ${context.packageName}"
            PanelRow(
                icon = R.drawable.sym_description, iconTint = if (logs) c.green else c.orange,
                title = stringResource(R.string.log_access),
                subtitle = stringResource(if (logs) R.string.granted else R.string.log_access_text),
                onClick = if (logs) null else ({
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("adb", command))
                    actions.show(res.getString(R.string.command_copied))
                }),
                subtitleContent = if (logs) null else ({
                    Text(command, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = c.secondary)
                }),
                trailing = { if (logs) Sym(R.drawable.sym_verified_user_fill1, c.green, 18.dp) else Sym(R.drawable.sym_content_copy, c.tertiary, 18.dp) },
            )
            RowDivider()
            PanelRow(
                icon = R.drawable.sym_power_settings_new, iconTint = if (overlay) c.green else c.orange,
                title = stringResource(R.string.overlay_access),
                subtitle = stringResource(if (overlay) R.string.granted else R.string.overlay_access_text),
                onClick = if (overlay) null else ({
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }),
                trailing = { if (overlay) Sym(R.drawable.sym_verified_user_fill1, c.green, 18.dp) else Chevron() },
            )
            RowDivider()
            // Android's message for each read: only a setting of the whole system hides it.
            PanelRow(
                icon = R.drawable.sym_history, iconTint = c.teal,
                title = stringResource(R.string.access_messages),
                subtitle = stringResource(R.string.access_messages_text),
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_PRIVACY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                trailing = { Chevron() },
            )
        }
    }
}
